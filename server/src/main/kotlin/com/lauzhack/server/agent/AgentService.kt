package com.lauzhack.server.agent

import com.lauzhack.server.db.AgentStateDao
import com.lauzhack.server.hint.HintService
import com.lauzhack.server.ocr.OcrService
import com.lauzhack.server.ocr.TranscriptionEvent
import com.lauzhack.shared.agent.AgentAction
import com.lauzhack.shared.agent.AgentOutput
import com.lauzhack.shared.agent.ConversationMessage
import com.lauzhack.shared.agent.HelpRequest
import com.lauzhack.shared.agent.PageChunk
import com.lauzhack.shared.agent.PageDelta
import com.lauzhack.shared.agent.PageSnapshot
import com.lauzhack.shared.agent.PlotWithAnchor
import com.lauzhack.shared.hint.Hint
import com.lauzhack.shared.hint.HintCard
import com.lauzhack.shared.hint.HintLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * The page agent, conversation-based: per document it keeps a running conversation
 * with the model. The first message is a full page snapshot (bootstrap), every later
 * message is the DELTA of what changed since the previous turn — and the model's
 * own previous answers stay in the conversation as its commitments, so no state is
 * re-sent.
 *
 * Changes are buffered while the page settles (OCR pending) and flushed as ONE user
 * message; a failed call leaves the buffer intact so the next change retries it.
 *
 * Hints are adopted into [HintService] (card, anchor, dismissal); plots and errors
 * are served as-is for the client to render.
 *
 * Explicit help (the toolbar Help button, "Another hint") also goes through the
 * conversation: the card is reserved in [HintService], a [HelpRequest] rides along
 * with the next message, and the model answers it with a hint carrying its requestId.
 *
 * @param agent null disables everything (no API key).
 */
class AgentService(
    private val agent: PageAgent?,
    private val ocr: OcrService,
    private val hints: HintService,
    private val scope: CoroutineScope,
    private val json: Json,
    private val exercise: suspend (documentId: String) -> String = { "" },
    /** The document's hint detail level (the slider), sent with every turn. */
    private val hintLevel: suspend (documentId: String) -> HintLevel = { HintLevel.MEDIUM },
    private val settleTimeoutMillis: Long = 6000,
    private val settlePollMillis: Long = 300,
) {
    private val log = LoggerFactory.getLogger(AgentService::class.java)

    private class DocState {
        val mutex = Mutex()
        var job: Job? = null
        var bootstrapped = false
        val knownChunks = HashSet<String>()
        val history = ArrayList<ConversationMessage>()
        val pendingAdded = LinkedHashMap<String, PageChunk>()
        val pendingModified = LinkedHashMap<String, PageChunk>()
        val pendingRemoved = LinkedHashSet<String>()
        /** Explicit help requests not yet sent (or whose turn failed and will be retried). */
        val pendingRequests = ArrayList<HelpRequest>()
        var lastOutput: AgentOutput = AgentOutput()
        /** Whether the persisted conversation (if any) has been loaded. */
        var restored = false
    }

    private val docs = ConcurrentHashMap<String, DocState>()

    fun start(events: Flow<TranscriptionEvent>): Job = scope.launch {
        events.collect { handle(it) }
    }

    /**
     * Toolbar Help button: reserves a card and asks the agent for a hint about the
     * work the student is on now. Null when hints are disabled.
     */
    suspend fun requestHelp(documentId: String): Hint? {
        if (agent == null) return hints.request(documentId) // legacy generator, if configured
        val hint = hints.reserveButtonHint(documentId)
        val lowest = ocr.snapshot(documentId).maxByOrNull { it.chunk.bounds.maxY }?.chunk?.id
        enqueue(documentId, HelpRequest(requestId = hint.id, kind = "button", nearChunkId = lowest))
        return hint
    }

    /**
     * The student clicked a word (or formula) of the exercise statement: reserves a
     * card next to it and asks the agent to clarify the concept it belongs to.
     * Null when the agent is disabled.
     */
    suspend fun requestDefinition(documentId: String, request: com.lauzhack.shared.dto.DefineTermRequest): Hint? {
        if (agent == null) return null
        val hint = hints.reserveDefinition(documentId, request.anchor)
        enqueue(
            documentId,
            HelpRequest(requestId = hint.id, kind = "define", term = request.term.trim(), context = request.context.trim()),
        )
        return hint
    }

    /**
     * "Another hint": the student did not understand the card; the agent answers in
     * the same card with a more concrete hint. Null when the hint is unknown.
     */
    suspend fun requestMoreHelp(documentId: String, hintId: String): Hint? {
        if (agent == null) return hints.more(documentId, hintId)
        val (hint, fresh) = hints.reserveMore(documentId, hintId) ?: return null
        if (fresh) {
            enqueue(
                documentId,
                HelpRequest(
                    requestId = hint.id,
                    kind = "more",
                    triggerChunkId = hint.triggerChunkId,
                    previousHints = hint.history.map { "${it.title}: ${it.message}" },
                ),
            )
        }
        return hint
    }

    private suspend fun enqueue(documentId: String, request: HelpRequest) {
        val agent = agent ?: return
        val s = docs.computeIfAbsent(documentId) { DocState() }
        s.mutex.withLock {
            ensureRestoredLocked(documentId, s)
            s.pendingRequests += request
            s.job?.cancel()
            s.job = scope.launch { evaluate(documentId, s, agent) }
        }
    }

    /** The agent's latest answer for the document (debug/inspection, like the OCR endpoints). */
    suspend fun snapshot(documentId: String): AgentOutput =
        docs[documentId]?.let { s -> s.mutex.withLock { s.lastOutput } } ?: AgentOutput()

    /** The conversation so far (debug/inspection). */
    suspend fun conversation(documentId: String): List<ConversationMessage> {
        val s = docs.computeIfAbsent(documentId) { DocState() }
        return s.mutex.withLock {
            ensureRestoredLocked(documentId, s)
            s.history.toList()
        }
    }

    private suspend fun handle(event: TranscriptionEvent) {
        val s = docs.computeIfAbsent(event.documentId) { DocState() }
        s.mutex.withLock {
            ensureRestoredLocked(event.documentId, s)
            when (event) {
                is TranscriptionEvent.Removed -> {
                    s.pendingAdded.remove(event.chunkId)
                    s.pendingModified.remove(event.chunkId)
                    if (s.bootstrapped) s.pendingRemoved += event.chunkId
                }

                is TranscriptionEvent.Updated -> {
                    // OCR still pending: not part of a delta until it is transcribed
                    val transcription = event.chunk.transcription ?: return@withLock
                    val chunk = event.chunk.chunk
                    val rawContent = transcription.content
                    val cleanContent = if (rawContent.isNotEmpty()) stripBboxes(rawContent) else rawContent
                    val pc = PageChunk(
                        id = chunk.id,
                        minX = chunk.bounds.minX,
                        minY = chunk.bounds.minY,
                        maxX = chunk.bounds.maxX,
                        maxY = chunk.bounds.maxY,
                        kind = transcription.kind.name,
                        content = cleanContent,
                        raw = if (rawContent != cleanContent) rawContent else null,
                    )
                    s.pendingRemoved.remove(chunk.id)
                    if (!s.bootstrapped || s.knownChunks.add(chunk.id)) {
                        s.pendingAdded[chunk.id] = pc
                    } else {
                        s.pendingAdded.remove(chunk.id)
                        s.pendingModified[chunk.id] = pc
                    }
                }
            }
            if (agent == null) return
            s.job?.cancel()
            s.job = scope.launch { evaluate(event.documentId, s, agent) }
        }
    }

    private suspend fun evaluate(documentId: String, s: DocState, agent: PageAgent) {
        val self = currentCoroutineContext()[Job]
        var bootstrap = false
        var committed = false
        var sentRequests: List<HelpRequest> = emptyList()
        try {
            val page = settledPage(documentId)
            val level = hintLevel(documentId).name

            // build the user message from the buffer WITHOUT consuming it: on failure the
            // buffered changes retry with the next delta instead of being lost
            var userMessage: String? = null
            var history: List<ConversationMessage> = emptyList()
            var sentAdded: List<PageChunk> = emptyList()
            var sentModified: List<PageChunk> = emptyList()
            var sentRemoved: List<String> = emptyList()
            s.mutex.withLock {
                if (self?.isCancelled == true) return
                sentRequests = s.pendingRequests.toList()
                if (!s.bootstrapped) {
                    if (page.isEmpty() && sentRequests.isEmpty()) return
                    userMessage = json.encodeToString(
                        PageSnapshot(
                            chunks = page.map { it.toPageChunk() },
                            exercise = exercise(documentId),
                            requests = sentRequests,
                            hintLevel = level,
                        )
                    )
                    bootstrap = true
                    // The snapshot covers everything buffered so far; changes that arrive
                    // DURING the model call must be buffered as deltas, not folded into it.
                    s.bootstrapped = true
                    s.knownChunks.clear()
                    page.forEach { s.knownChunks += it.chunk.id }
                    s.pendingAdded.clear()
                    s.pendingModified.clear()
                    s.pendingRemoved.clear()
                } else if (s.pendingAdded.isEmpty() && s.pendingModified.isEmpty() && s.pendingRemoved.isEmpty() &&
                    sentRequests.isEmpty()
                ) {
                    return // nothing new since the last turn
                } else {
                    sentAdded = s.pendingAdded.values.toList()
                    sentModified = s.pendingModified.values.toList()
                    sentRemoved = s.pendingRemoved.toList()
                    val delta = PageDelta(
                        added = sentAdded,
                        modified = sentModified,
                        removed = sentRemoved,
                        requests = sentRequests,
                        hintLevel = level,
                    )
                    userMessage = json.encodeToString(delta)
                }
                history = s.history.toList()
            }

            val output = agent.converse(history, userMessage!!)

            // success: commit the turn, consume exactly what was sent, and persist
            // the conversation (entries that changed AGAIN during the model call
            // stay buffered for the next delta)
            s.mutex.withLock {
                for (c in sentAdded) if (s.pendingAdded[c.id] == c) s.pendingAdded.remove(c.id)
                for (c in sentModified) if (s.pendingModified[c.id] == c) s.pendingModified.remove(c.id)
                for (id in sentRemoved) s.pendingRemoved.remove(id)
                s.pendingRequests.removeAll(sentRequests.toSet())
                s.history.add(ConversationMessage("user", userMessage))
                s.history.add(ConversationMessage("assistant", json.encodeToString(output)))
                trim(s.history)
                s.lastOutput = output
                committed = true
                persist(documentId, s)
            }
            apply(documentId, page, output)
            // a request the model left unanswered would keep its card thinking forever
            val answered = output.actions.filterIsInstance<AgentAction.HintAction>().map { it.requestId }.toSet()
            for (r in sentRequests) if (r.requestId !in answered) {
                log.warn("doc {}: help request {} left unanswered", documentId, r.requestId)
                hints.fail(documentId, r.requestId)
            }
            log.info("doc {}: {} action(s)", documentId, output.actions.size)
        } catch (e: CancellationException) {
            // a newer change re-schedules the evaluation: if this was the bootstrap
            // turn and it never committed, undo it so the next run re-snapshots
            if (bootstrap && !committed) revertBootstrap(s)
            throw e
        } catch (e: Exception) {
            if (bootstrap && !committed) revertBootstrap(s)
            log.warn("agent failed for doc {}: {}", documentId, e.message)
            // page changes retry with the next delta, but a waiting card must not hang:
            // report the failure (the student can ask again)
            if (!committed && sentRequests.isNotEmpty()) {
                s.mutex.withLock { s.pendingRequests.removeAll(sentRequests.toSet()) }
                for (r in sentRequests) hints.fail(documentId, r.requestId)
            }
        } finally {
            withContext(NonCancellable) {
                s.mutex.withLock { if (s.job === self) s.job = null }
            }
        }
    }

    /** The bootstrap turn never happened: undo it so the next evaluation re-snapshots. */
    private suspend fun revertBootstrap(s: DocState) {
        s.mutex.withLock {
            s.bootstrapped = false
            s.knownChunks.clear()
        }
    }

    /** Keep the bootstrap plus the most recent turns; drop the middle if it grows too long. */
    private fun trim(history: MutableList<ConversationMessage>) {
        if (history.size > 40) {
            val tail = history.drop(history.size - 36)
            val kept = ArrayList<ConversationMessage>(37)
            kept.add(history.first()) // the bootstrap page
            kept.addAll(tail)
            history.clear()
            history.addAll(kept)
        }
    }

    /** Execute what the server owns: hints. Plots and errors are served to the client. */
    private suspend fun apply(documentId: String, page: List<com.lauzhack.shared.ocr.TranscribedChunk>, output: AgentOutput) {
        val byId = page.associateBy { it.chunk.id }
        for (action in output.actions) {
            when (action) {
                is AgentAction.HintAction -> {
                    if (action.requestId.isNotBlank()) {
                        // answer to an explicit request: the card is already reserved
                        hints.complete(documentId, action.requestId, action.toCard())
                        log.info("doc {}: hint for request {}", documentId, action.requestId)
                        continue
                    }
                    val trigger = byId[action.triggerChunkId] ?: continue
                    val hintId = hints.request(
                        documentId = documentId,
                        triggerChunkId = action.triggerChunkId,
                        contentHash = trigger.chunk.contentHash,
                        anchor = trigger.chunk.bounds,
                    ) ?: continue // already answered or dismissed for this exact circled "?"
                    hints.complete(documentId = documentId, hintId = hintId, card = action.toCard())
                    log.info("doc {}: hint {} for {}", documentId, hintId, action.triggerChunkId)
                }

                is AgentAction.PlotAction -> {
                    if (action.remove) {
                        removePlot(documentId, action.id)
                        log.info("doc {}: plot {} withdrawn", documentId, action.id)
                    } else {
                        // anchor where the function was defined: "plot-<chunkId>"
                        val anchor = byId[action.id.removePrefix("plot-")]?.chunk?.bounds
                        putPlot(documentId, PlotWithAnchor(action, anchor))
                        log.info("doc {}: plot {} on {} (+{} point(s))", documentId, action.id, action.expression, action.points.size)
                    }
                }

                is AgentAction.ErrorAction -> Unit // not rendered yet
            }
        }
    }

    private fun AgentAction.HintAction.toCard() = HintCard(
        title = title.trim(),
        message = message.trim(),
        steps = steps.map { it.trim() }.filter { it.isNotEmpty() },
        relatedChunkIds = relatedChunkIds,
    )

    /** The page once every chunk is transcribed, or as it is after the timeout. */
    private suspend fun settledPage(docId: String): List<com.lauzhack.shared.ocr.TranscribedChunk> {
        val start = System.nanoTime()
        val settled = withTimeoutOrNull(settleTimeoutMillis) {
            var page = ocr.snapshot(docId)
            while (page.any { it.transcription == null }) {
                delay(settlePollMillis)
                page = ocr.snapshot(docId)
            }
            page
        } ?: ocr.snapshot(docId)
        log.info(
            "agent-settle doc {}: waited={}ms (timeout={}ms) pending={}",
            docId,
            (System.nanoTime() - start) / 1_000_000,
            settleTimeoutMillis,
            settled.count { it.transcription == null },
        )
        return settled
    }

    private fun com.lauzhack.shared.ocr.TranscribedChunk.toPageChunk(): PageChunk {
        val rawContent = transcription?.content.orEmpty()
        val clean = if (rawContent.isNotEmpty()) stripBboxes(rawContent) else rawContent
        return PageChunk(
            id = chunk.id,
            minX = chunk.bounds.minX,
            minY = chunk.bounds.minY,
            maxX = chunk.bounds.maxX,
            maxY = chunk.bounds.maxY,
            kind = transcription?.kind?.name ?: "UNREADABLE",
            content = clean,
            raw = if (rawContent != clean) rawContent else null,
        )
    }

    companion object {
        /**
         * Strips \bbox[a,b,c,d]{…} markers (the OCR pipeline's per-glyph bounding
         * boxes) keeping the inner content, with proper brace matching for nested
         * LaTeX. Deterministic Kotlin — the model never has to do the stripping.
         */
        fun stripBboxes(text: String): String {
            val sb = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                if (text.startsWith("\\bbox[", i)) {
                    val bracketEnd = text.indexOf(']', i)
                    if (bracketEnd > 0 && bracketEnd + 1 < text.length && text[bracketEnd + 1] == '{') {
                        var depth = 1
                        var j = bracketEnd + 2
                        while (j < text.length && depth > 0) {
                            if (text[j] == '{') depth++
                            else if (text[j] == '}') depth--
                            j++
                        }
                        sb.append(text, bracketEnd + 2, j - 1)
                        i = j
                    } else {
                        sb.append(text[i])
                        i++
                    }
                } else {
                    sb.append(text[i])
                    i++
                }
            }
            return sb.toString().trim()
        }
    }

    fun forget(documentId: String) {
        docs.remove(documentId)?.job?.cancel()
        synchronized(plotRegistry) { plotRegistry.remove(documentId) }
    }

    // ---------------------------------------------------------------
    // Persistence: the conversation is saved per document, so the model's
    // memory (hints, mistakes, functions) survives server restarts; every
    // document keeps its own conversation.
    // ---------------------------------------------------------------

    /**
     * Loads the stored conversation once per document per server session. The
     * restored history is the model's memory; the page then flows back in as a
     * fresh page snapshot (bootstrapped stays false), so plots and hints are
     * re-emitted against current chunk ids — never stale ones.
     * Callers must hold [DocState.mutex].
     */
    private fun ensureRestoredLocked(documentId: String, s: DocState) {
        if (s.restored) return
        s.restored = true
        val stored = runCatching { AgentStateDao.load(documentId) }.getOrNull()
        if (stored.isNullOrEmpty()) return
        s.history.clear()
        s.history.addAll(stored)
        log.info("doc {}: restored conversation ({} messages)", documentId, stored.size)
    }

    /** Saves the conversation; a failed save never breaks the agent loop. */
    private fun persist(documentId: String, s: DocState) {
        runCatching { AgentStateDao.save(documentId, s.history.toList()) }
            .onFailure { log.warn("doc {}: could not persist conversation: {}", documentId, it.message) }
    }

    // ---------------------------------------------------------------
    // Plots: the active set per document (add/replace by id, remove-aware)
    // ---------------------------------------------------------------

    private val plotRegistry = java.util.concurrent.ConcurrentHashMap<String, LinkedHashMap<String, PlotWithAnchor>>()

    /** The plots currently active for the document, in answer order. */
    fun plots(documentId: String): List<PlotWithAnchor> {
        val s = docs.computeIfAbsent(documentId) { DocState() }
        runBlocking { s.mutex.withLock { ensureRestoredLocked(documentId, s) } }
        return synchronized(plotRegistry) { plotRegistry[documentId]?.values?.toList() ?: emptyList() }
    }

    private fun putPlot(documentId: String, entry: PlotWithAnchor) =
        synchronized(plotRegistry) {
            plotRegistry.getOrPut(documentId) { LinkedHashMap() }[entry.plot.id] = entry
        }

    private fun removePlot(documentId: String, id: String) =
        synchronized(plotRegistry) { plotRegistry[documentId]?.remove(id) }
}
