package com.lauzhack.server.hint

import com.lauzhack.server.ocr.TranscriptionEvent
import com.lauzhack.shared.chunking.BoundingBox
import com.lauzhack.shared.domain.Point
import com.lauzhack.shared.hint.Hint
import com.lauzhack.shared.hint.HintCard
import com.lauzhack.shared.hint.HintStatus
import com.lauzhack.shared.ocr.TranscribedChunk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds the hint cards shown on a page. The page agent decides what each card
 * says and reserves/fills it here; this class owns the card lifecycle (reserve,
 * complete, fail, move, dismiss) and removes a card when its circled "?" is erased.
 *
 * @param pageTranscription current chunks + transcriptions of a document (OcrService.snapshot).
 */
class HintService(
    private val scope: CoroutineScope,
    private val pageTranscription: suspend (documentId: String) -> List<TranscribedChunk>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(HintService::class.java)

    private class DocState {
        val mutex = Mutex()
        val hints = LinkedHashMap<String, Hint>()
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
     * Reserves a THINKING card for the toolbar Help button, anchored next to the
     * lowest block of work (or near the top of an empty page). No model call.
     */
    suspend fun reserveButtonHint(documentId: String): Hint {
        val lowest = pageTranscription(documentId).maxByOrNull { it.chunk.bounds.maxY }?.chunk?.bounds
        return reserveAt(documentId, lowest ?: BoundingBox(560.0, 320.0, 600.0, 360.0), "with the button")
    }

    /** Reserves a THINKING card for a clicked word of the exercise statement, next to it. */
    suspend fun reserveDefinition(documentId: String, anchor: BoundingBox): Hint =
        reserveAt(documentId, anchor, "for a word of the statement")

    private suspend fun reserveAt(documentId: String, anchor: BoundingBox, how: String): Hint {
        val s = docs.computeIfAbsent(documentId) { DocState() }
        return s.mutex.withLock {
            val hint = Hint(
                id = "hint-${s.nextId++}",
                triggerChunkId = null,
                anchor = anchor,
                status = HintStatus.THINKING,
                createdAt = clock(),
            )
            s.hints[hint.id] = hint
            log.info("doc {}: hint requested {} ({})", documentId, how, hint.id)
            hint
        }
    }

    /**
     * "Another hint": the student did not understand this card. Its current card
     * moves to [Hint.history] and the box goes back to THINKING, waiting for a new,
     * more concrete hint. Returns the hint and whether it was newly reserved
     * (false when it was already thinking); null when the hint is unknown.
     */
    suspend fun reserveMore(documentId: String, hintId: String): Pair<Hint, Boolean>? {
        val s = docs[documentId] ?: return null
        return s.mutex.withLock {
            val old = s.hints[hintId] ?: return@withLock null
            if (old.status == HintStatus.THINKING) return@withLock old to false
            val hint = old.copy(
                status = HintStatus.THINKING,
                history = old.history + listOfNotNull(old.card),
                card = null,
                error = null,
            )
            s.hints[hintId] = hint
            hint to true
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
        return s.mutex.withLock { s.hints.remove(hintId) != null }
    }

    /** Removes the cards whose circled "?" chunk disappeared (erased). */
    suspend fun handle(event: TranscriptionEvent) {
        if (event !is TranscriptionEvent.Removed) return
        val s = docs[event.documentId] ?: return
        s.mutex.withLock { removeForTrigger(s, event.chunkId) }
    }

    private fun removeForTrigger(s: DocState, chunkId: String) {
        val ids = s.hints.values.filter { it.triggerChunkId == chunkId }.map { it.id }
        for (id in ids) s.hints.remove(id)
    }

    fun forget(documentId: String) {
        docs.remove(documentId)
    }

    /**
     * Adoption path used by the page agent: reserve a THINKING hint for a circled
     * "?" without calling any model. Returns the hint id, or null when this exact
     * content was already answered (or its hint dismissed).
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

    /** Fill a reserved hint; no-op if it was removed meanwhile. */
    suspend fun complete(documentId: String, hintId: String, card: HintCard) {
        val s = docs[documentId] ?: return
        s.mutex.withLock {
            val hint = s.hints[hintId] ?: return
            s.hints[hintId] = hint.copy(status = HintStatus.READY, card = card, error = null)
        }
    }

    /** A reserved hint could not be answered: show the error in its card. */
    suspend fun fail(documentId: String, hintId: String, message: String = "Couldn't generate a hint. Please try again.") {
        val s = docs[documentId] ?: return
        s.mutex.withLock {
            val hint = s.hints[hintId] ?: return
            if (hint.status == HintStatus.THINKING) s.hints[hintId] = hint.copy(status = HintStatus.FAILED, error = message)
        }
    }
}
