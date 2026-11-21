package com.lauzhack.server.agent

import com.lauzhack.server.hint.HintService
import com.lauzhack.server.ocr.OcrService
import com.lauzhack.server.ocr.TranscriptionEvent
import com.lauzhack.server.plot.PlotRegistry
import com.lauzhack.server.plot.buildPlotSpec
import com.lauzhack.shared.agent.AgentAction
import com.lauzhack.shared.agent.AgentOutput
import com.lauzhack.shared.agent.PageChunk
import com.lauzhack.shared.agent.PageContext
import com.lauzhack.shared.hint.HintCard
import com.lauzhack.shared.hint.HintStatus
import com.lauzhack.shared.ocr.TranscribedChunk
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
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The page agent: watches the OCR stage and, once the page has settled, asks the
 * model ONCE per changed page state what to do about it. The answer is an
 * [AgentOutput] — an empty list when no action is needed — and each action is
 * executed deterministically:
 *
 * - [AgentAction.HintAction] → adopted into [HintService] (card, anchor, dismissal);
 * - [AgentAction.PlotAction] → validated with the safe DSL → [PlotRegistry].
 *
 * Cost control: the context hash (all chunk content hashes) is compared to the
 * last evaluated state, so an unchanged page is never sent to the model twice.
 *
 * @param agent null disables everything (no API key): no hints, no plots.
 */
class AgentService(
    private val agent: PageAgent?,
    private val ocr: OcrService,
    private val hints: HintService,
    private val plots: PlotRegistry,
    private val scope: CoroutineScope,
    private val settleTimeoutMillis: Long = 6000,
    private val settlePollMillis: Long = 300,
) {
    private val log = LoggerFactory.getLogger(AgentService::class.java)

    private class DocState {
        val mutex = Mutex()
        var job: Job? = null
        var lastContextHash: String? = null
        var lastOutput: AgentOutput = AgentOutput()
    }

    private val docs = ConcurrentHashMap<String, DocState>()

    fun start(events: Flow<TranscriptionEvent>): Job = scope.launch {
        events.collect { handle(it) }
    }

    /** The agent's latest answer for the document (debug/inspection, like the OCR endpoints). */
    suspend fun snapshot(documentId: String): AgentOutput =
        docs[documentId]?.let { s -> s.mutex.withLock { s.lastOutput } } ?: AgentOutput()

    private suspend fun handle(event: TranscriptionEvent) {
        val s = docs.computeIfAbsent(event.documentId) { DocState() }
        s.mutex.withLock {
            if (agent == null) return
            s.job?.cancel()
            s.job = scope.launch { evaluate(event.documentId, s, agent) }
        }
    }

    private suspend fun evaluate(documentId: String, s: DocState, agent: PageAgent) {
        val self = currentCoroutineContext()[Job]
        try {
            val page = settledPage(documentId)

            // one call per changed page state, never for the same content twice
            val contextHash = hashOf(page)
            s.mutex.withLock {
                if (contextHash == s.lastContextHash) return
                if (self?.isCancelled == true) return
            }

            val given = hints.hints(documentId)
                .filter { it.status == HintStatus.READY }
                .mapNotNull { it.card }
                .map { com.lauzhack.shared.agent.GivenHint(it.title, it.message) }
            val context = PageContext(
                documentId = documentId,
                chunks = page.map { it.toPageChunk() },
                givenHints = given,
            )

            val output = agent.decide(context)
            apply(documentId, page, output)
            s.mutex.withLock {
                s.lastContextHash = contextHash
                s.lastOutput = output
            }
            log.info("doc {}: {} action(s) [{}]", documentId, output.actions.size, contextHash.take(8))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("agent failed for doc {}: {}", documentId, e.message)
        } finally {
            withContext(NonCancellable) {
                s.mutex.withLock { if (s.job === self) s.job = null }
            }
        }
    }

    /** Execute each action deterministically; anything invalid is dropped, never guessed. */
    private suspend fun apply(documentId: String, page: List<TranscribedChunk>, output: AgentOutput) {
        val byId = page.associateBy { it.chunk.id }

        val specs = ArrayList<com.lauzhack.shared.plot.PlotSpec>()
        for (action in output.actions) {
            when (action) {
                is AgentAction.HintAction -> {
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

                is AgentAction.PlotAction -> {
                    try {
                        specs += buildPlotSpec(
                            documentId = documentId,
                            functionName = action.functionName,
                            variable = action.variable,
                            expression = action.expression,
                            domain = action.domain,
                        )
                    } catch (e: Exception) {
                        log.warn("doc {}: plot action dropped (bad DSL: \"{}\")", documentId, action.expression)
                    }
                }
            }
        }
        plots.replaceAll(documentId, specs)
    }

    /** The page once every chunk is transcribed, or as it is after the timeout. */
    private suspend fun settledPage(docId: String): List<TranscribedChunk> =
        withTimeoutOrNull(settleTimeoutMillis) {
            var page = ocr.snapshot(docId)
            while (page.any { it.transcription == null }) {
                delay(settlePollMillis)
                page = ocr.snapshot(docId)
            }
            page
        } ?: ocr.snapshot(docId)

    private fun hashOf(page: List<TranscribedChunk>): String {
        val md = MessageDigest.getInstance("MD5")
        page.sortedBy { it.chunk.id }
            .forEach { md.update("${it.chunk.id}:${it.chunk.contentHash};".toByteArray()) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun TranscribedChunk.toPageChunk() = PageChunk(
        id = chunk.id,
        minX = chunk.bounds.minX,
        minY = chunk.bounds.minY,
        maxX = chunk.bounds.maxX,
        maxY = chunk.bounds.maxY,
        kind = transcription?.kind?.name ?: "PENDING",
        content = transcription?.content.orEmpty(),
    )

    fun forget(documentId: String) {
        docs.remove(documentId)?.job?.cancel()
    }
}
