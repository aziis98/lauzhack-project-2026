package com.lauzhack.server.agent

import com.lauzhack.server.hint.HintService
import com.lauzhack.server.ocr.OcrService
import com.lauzhack.server.ocr.TranscriptionEvent
import com.lauzhack.shared.agent.AgentAction
import com.lauzhack.shared.agent.AgentOutput
import com.lauzhack.shared.agent.ConversationMessage
import com.lauzhack.shared.agent.PageChunk
import com.lauzhack.shared.agent.PageDelta
import com.lauzhack.shared.agent.PageSnapshot
import com.lauzhack.shared.hint.HintCard
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
 * @param agent null disables everything (no API key).
 */
class AgentService(
    private val agent: PageAgent?,
    private val ocr: OcrService,
    private val hints: HintService,
    private val scope: CoroutineScope,
    private val json: Json,
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
        var lastOutput: AgentOutput = AgentOutput()
    }

    private val docs = ConcurrentHashMap<String, DocState>()

    fun start(events: Flow<TranscriptionEvent>): Job = scope.launch {
        events.collect { handle(it) }
    }

    /** The agent's latest answer for the document (debug/inspection, like the OCR endpoints). */
    suspend fun snapshot(documentId: String): AgentOutput =
        docs[documentId]?.let { s -> s.mutex.withLock { s.lastOutput } } ?: AgentOutput()

    /** The conversation so far (debug/inspection). */
    suspend fun conversation(documentId: String): List<ConversationMessage> =
        docs[documentId]?.let { s -> s.mutex.withLock { s.history.toList() } } ?: emptyList()

    private suspend fun handle(event: TranscriptionEvent) {
        val s = docs.computeIfAbsent(event.documentId) { DocState() }
        s.mutex.withLock {
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
                    val pc = PageChunk(
                        id = chunk.id,
                        minX = chunk.bounds.minX,
                        minY = chunk.bounds.minY,
                        maxX = chunk.bounds.maxX,
                        maxY = chunk.bounds.maxY,
                        kind = transcription.kind.name,
                        content = transcription.content,
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
        try {
            val page = settledPage(documentId)

            // build the user message from the buffer WITHOUT consuming it: on failure the
            // buffered changes retry with the next delta instead of being lost
            var userMessage: String? = null
            var history: List<ConversationMessage> = emptyList()
            var sentAdded: List<PageChunk> = emptyList()
            var sentModified: List<PageChunk> = emptyList()
            var sentRemoved: List<String> = emptyList()
            s.mutex.withLock {
                if (self?.isCancelled == true) return
                if (!s.bootstrapped) {
                    if (page.isEmpty()) return
                    userMessage = json.encodeToString(PageSnapshot(chunks = page.map { it.toPageChunk() }))
                    bootstrap = true
                    // The snapshot covers everything buffered so far; changes that arrive
                    // DURING the model call must be buffered as deltas, not folded into it.
                    s.bootstrapped = true
                    s.knownChunks.clear()
                    page.forEach { s.knownChunks += it.chunk.id }
                    s.pendingAdded.clear()
                    s.pendingModified.clear()
                    s.pendingRemoved.clear()
                } else if (s.pendingAdded.isEmpty() && s.pendingModified.isEmpty() && s.pendingRemoved.isEmpty()) {
                    return // nothing new since the last turn
                } else {
                    sentAdded = s.pendingAdded.values.toList()
                    sentModified = s.pendingModified.values.toList()
                    sentRemoved = s.pendingRemoved.toList()
                    val delta = PageDelta(added = sentAdded, modified = sentModified, removed = sentRemoved)
                    userMessage = json.encodeToString(delta)
                }
                history = s.history.toList()
            }

            val output = agent.converse(history, userMessage!!)

            // success: commit the turn and consume exactly what was sent — entries that
            // changed AGAIN during the model call stay buffered for the next delta
            s.mutex.withLock {
                for (c in sentAdded) if (s.pendingAdded[c.id] == c) s.pendingAdded.remove(c.id)
                for (c in sentModified) if (s.pendingModified[c.id] == c) s.pendingModified.remove(c.id)
                for (id in sentRemoved) s.pendingRemoved.remove(id)
                s.history.add(ConversationMessage("user", userMessage))
                s.history.add(ConversationMessage("assistant", json.encodeToString(output)))
                trim(s.history)
                s.lastOutput = output
                committed = true
            }
            apply(documentId, page, output)
            log.info("doc {}: {} action(s)", documentId, output.actions.size)
        } catch (e: CancellationException) {
            // a newer change re-schedules the evaluation: if this was the bootstrap
            // turn and it never committed, undo it so the next run re-snapshots
            if (bootstrap && !committed) revertBootstrap(s)
            throw e
        } catch (e: Exception) {
            if (bootstrap && !committed) revertBootstrap(s)
            log.warn("agent failed for doc {}: {}", documentId, e.message)
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
            if (action is AgentAction.HintAction) {
                val trigger = byId[action.triggerChunkId] ?: continue
                val hintId = hints.request(
                    documentId = documentId,
                    triggerChunkId = action.triggerChunkId,
                    contentHash = trigger.chunk.contentHash,
                    anchor = trigger.chunk.bounds,
                ) ?: continue // already answered or dismissed for this exact circled "?"
                hints.complete(
                    documentId = documentId,
                    hintId = hintId,
                    card = HintCard(
                        title = action.title.trim(),
                        message = action.message.trim(),
                        steps = action.steps.map { it.trim() }.filter { it.isNotEmpty() },
                        relatedChunkIds = action.relatedChunkIds,
                    ),
                )
                log.info("doc {}: hint {} for {}", documentId, hintId, action.triggerChunkId)
            }
        }
    }

    /** The page once every chunk is transcribed, or as it is after the timeout. */
    private suspend fun settledPage(docId: String): List<com.lauzhack.shared.ocr.TranscribedChunk> =
        withTimeoutOrNull(settleTimeoutMillis) {
            var page = ocr.snapshot(docId)
            while (page.any { it.transcription == null }) {
                delay(settlePollMillis)
                page = ocr.snapshot(docId)
            }
            page
        } ?: ocr.snapshot(docId)

    private fun com.lauzhack.shared.ocr.TranscribedChunk.toPageChunk() = PageChunk(
        id = chunk.id,
        minX = chunk.bounds.minX,
        minY = chunk.bounds.minY,
        maxX = chunk.bounds.maxX,
        maxY = chunk.bounds.maxY,
        kind = transcription?.kind?.name ?: "UNREADABLE",
        content = transcription?.content.orEmpty(),
    )

    fun forget(documentId: String) {
        docs.remove(documentId)?.job?.cancel()
    }
}
