package com.lauzhack.server.hint

import com.lauzhack.server.ocr.TranscriptionEvent
import com.lauzhack.shared.chunking.BoundingBox
import com.lauzhack.shared.domain.Point
import com.lauzhack.shared.hint.Hint
import com.lauzhack.shared.hint.HintCard
import com.lauzhack.shared.hint.HintStatus
import com.lauzhack.shared.ocr.ChunkKind
import com.lauzhack.shared.ocr.TranscribedChunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Watches OCR results and answers every circled "?" ([ChunkKind.HINT_REQUEST])
 * with a hint from [generator].
 *
 * - A request is answered once per content: redrawing the same circled ? does
 *   not ask again; a dismissed hint does not come back.
 * - Before asking the model it waits (up to [settleTimeoutMillis]) for the rest
 *   of the page to be transcribed, so the hint sees the latest work.
 * - Erasing the circled ? removes its hint.
 * - Hints already given on the page are passed along, so asking again gets a
 *   more concrete hint.
 *
 * @param generator null disables hints (no API key).
 * @param pageTranscription current chunks + transcriptions of a document (OcrService.snapshot).
 */
class HintService(
    private val generator: HintGenerator?,
    private val scope: CoroutineScope,
    private val pageTranscription: suspend (documentId: String) -> List<TranscribedChunk>,
    private val exercise: suspend (documentId: String) -> String = { "" },
    private val settleTimeoutMillis: Long = 6000,
    private val settlePollMillis: Long = 300,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(HintService::class.java)

    private class DocState {
        val mutex = Mutex()
        val hints = LinkedHashMap<String, Hint>()
        val jobs = HashMap<String, Job>()
        /** Content hashes of circled ? already answered (or dismissed). */
        val handled = HashSet<String>()
        var nextId = 1
    }

    private val docs = ConcurrentHashMap<String, DocState>()

    fun start(events: Flow<TranscriptionEvent>): Job = scope.launch {
        events.collect { handle(it) }
    }

    suspend fun hints(documentId: String): List<Hint> {
        val s = docs[documentId] ?: return emptyList()
        return s.mutex.withLock { s.hints.values.sortedBy { it.createdAt } }
    }

    /**
     * Help asked with the toolbar button (no circled ?). The card is anchored
     * next to the lowest block of work, or near the top of an empty page.
     * Returns null when hints are disabled.
     */
    suspend fun request(documentId: String): Hint? {
        val generator = generator ?: return null
        val lowest = pageTranscription(documentId).maxByOrNull { it.chunk.bounds.maxY }?.chunk?.bounds
        val s = docs.computeIfAbsent(documentId) { DocState() }
        return s.mutex.withLock {
            val hint = Hint(
                id = "hint-${s.nextId++}",
                triggerChunkId = null,
                anchor = lowest ?: BoundingBox(560.0, 320.0, 600.0, 360.0),
                status = HintStatus.THINKING,
                createdAt = clock(),
            )
            s.hints[hint.id] = hint
            s.jobs[hint.id] = scope.launch { answer(documentId, s, hint, generator) }
            log.info("doc {}: hint requested with the button ({})", documentId, hint.id)
            hint
        }
    }

    /**
     * "Another hint": the student did not understand this one. The current card
     * moves to [Hint.history] and a new, more concrete one is generated in the same box.
     */
    suspend fun more(documentId: String, hintId: String): Hint? {
        val generator = generator ?: return null
        val s = docs[documentId] ?: return null
        return s.mutex.withLock {
            val old = s.hints[hintId] ?: return@withLock null
            if (old.status == HintStatus.THINKING) return@withLock old
            val hint = old.copy(
                status = HintStatus.THINKING,
                history = old.history + listOfNotNull(old.card),
                card = null,
                error = null,
            )
            s.hints[hintId] = hint
            s.jobs[hintId] = scope.launch { answer(documentId, s, hint, generator) }
            hint
        }
    }

    /** Stores where the student dragged the card (UI state only; the AI never sees it). */
    suspend fun move(documentId: String, hintId: String, position: Point): Hint? {
        val s = docs[documentId] ?: return null
        return s.mutex.withLock {
            val hint = s.hints[hintId] ?: return@withLock null
            hint.copy(position = position).also { s.hints[hintId] = it }
        }
    }

    /** Removes a hint; it will not be generated again for the same circled ?. */
    suspend fun dismiss(documentId: String, hintId: String): Boolean {
        val s = docs[documentId] ?: return false
        return s.mutex.withLock {
            s.jobs.remove(hintId)?.cancel()
            s.hints.remove(hintId) != null
        }
    }

    suspend fun handle(event: TranscriptionEvent) {
        val s = docs.computeIfAbsent(event.documentId) { DocState() }
        s.mutex.withLock {
            when (event) {
                is TranscriptionEvent.Removed -> removeForTrigger(s, event.chunkId)
                is TranscriptionEvent.Updated -> {
                    if (generator == null) return@withLock // the page agent adopts hints instead
                    val chunk = event.chunk.chunk
                    val kind = event.chunk.transcription?.kind ?: return@withLock // OCR pending
                    if (kind != ChunkKind.HINT_REQUEST) {
                        removeForTrigger(s, chunk.id) // it was a circled ?, not anymore
                        return@withLock
                    }
                    if (!s.handled.add(chunk.contentHash)) return@withLock
                    removeForTrigger(s, chunk.id) // older answer to a previous version
                    val hint = Hint(
                        id = "hint-${s.nextId++}",
                        triggerChunkId = chunk.id,
                        anchor = chunk.bounds,
                        status = HintStatus.THINKING,
                        createdAt = clock(),
                    )
                    s.hints[hint.id] = hint
                    s.jobs[hint.id] = scope.launch { answer(event.documentId, s, hint, generator) }
                    log.info("doc {}: hint requested at {} ({})", event.documentId, chunk.id, hint.id)
                }
            }
        }
    }

    private fun removeForTrigger(s: DocState, chunkId: String) {
        val ids = s.hints.values.filter { it.triggerChunkId == chunkId }.map { it.id }
        for (id in ids) {
            s.jobs.remove(id)?.cancel()
            s.hints.remove(id)
        }
    }

    private suspend fun answer(docId: String, s: DocState, hint: Hint, generator: HintGenerator) {
        val result = try {
            val page = settledPage(docId)
            val trigger = hint.triggerChunkId?.let { id ->
                page.find { it.chunk.id == id } ?: return // the circled ? is gone
            }
            val previous = s.mutex.withLock {
                s.hints.values
                    .filter { it.id != hint.id && it.status == HintStatus.READY }
                    .sortedBy { it.createdAt }
                    .mapNotNull { it.card }
            } + hint.history // what this box already said comes last: the next hint must go further
            val card = generator.generate(HintContext(page, trigger, previous, exercise(docId)))
            log.info("doc {} {}: {} — {}", docId, hint.id, card.title, card.message)
            hint.copy(status = HintStatus.READY, card = card)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("hint {} failed for doc {}: {}", hint.id, docId, e.message)
            hint.copy(status = HintStatus.FAILED, error = "Couldn't generate a hint. Please try again.")
        }
        s.mutex.withLock {
            // keep a position the student set while the model was thinking
            s.hints[hint.id]?.let { current -> s.hints[hint.id] = result.copy(position = current.position) }
            s.jobs.remove(hint.id)
        }
    }

    /** The page once every chunk is transcribed, or as it is after the timeout. */
    private suspend fun settledPage(docId: String): List<TranscribedChunk> =
        withTimeoutOrNull(settleTimeoutMillis) {
            var page = pageTranscription(docId)
            while (page.any { it.transcription == null }) {
                delay(settlePollMillis)
                page = pageTranscription(docId)
            }
            page
        } ?: pageTranscription(docId)

    fun forget(documentId: String) {
        docs.remove(documentId)?.jobs?.values?.forEach { it.cancel() }
    }

    /**
     * Adoption path used by the page agent: reserve a THINKING hint for a circled
     * "?" without calling any model. Returns the hint id, or null when this exact
     * content was already answered (or its hint dismissed) — the same semantics as
     * [handle] gives the generator path.
     */
    suspend fun request(documentId: String, triggerChunkId: String, contentHash: String, anchor: BoundingBox): String? {
        val s = docs.computeIfAbsent(documentId) { DocState() }
        return s.mutex.withLock {
            if (!s.handled.add(contentHash)) return null
            removeForTrigger(s, triggerChunkId)
            val hint = Hint(
                id = "hint-${s.nextId++}",
                triggerChunkId = triggerChunkId,
                anchor = anchor,
                status = HintStatus.THINKING,
                createdAt = clock(),
            )
            s.hints[hint.id] = hint
            hint.id
        }
    }

    /** Fill a hint reserved by [request]; no-op if it was removed meanwhile. */
    suspend fun complete(documentId: String, hintId: String, card: HintCard) {
        val s = docs[documentId] ?: return
        s.mutex.withLock {
            val hint = s.hints[hintId] ?: return
            s.hints[hintId] = hint.copy(status = HintStatus.READY, card = card)
        }
    }
}
