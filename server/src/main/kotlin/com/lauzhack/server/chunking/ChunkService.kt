package com.lauzhack.server.chunking

import com.lauzhack.server.db.listElements
import com.lauzhack.shared.chunking.Chunk
import com.lauzhack.shared.chunking.ChunkTracker
import com.lauzhack.shared.chunking.ChunkUpdate
import com.lauzhack.shared.domain.Element
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * A change in one document's chunks, with the stroke elements of every
 * added/modified chunk attached so consumers (OCR) need no extra DB read.
 */
data class ChunkEvent(
    val documentId: String,
    val update: ChunkUpdate,
    val strokes: Map<String, Element>,
) {
    fun strokesOf(chunk: Chunk): List<Element> = chunk.strokeIds.mapNotNull { strokes[it] }
}

/**
 * Keeps one [ChunkTracker] per document, re-chunks the page after every
 * element write, and pushes the resulting [ChunkEvent]s to subscribers
 * (OCR pipeline, reasoning agent) via [events].
 *
 * State is in memory: after a server restart chunk ids start over and the
 * first refresh of a document reports all its chunks as added.
 */
object ChunkService {
    private val log = LoggerFactory.getLogger(ChunkService::class.java)

    private class DocState {
        val mutex = Mutex()
        val tracker = ChunkTracker()
        var initialized = false
    }

    private val docs = ConcurrentHashMap<String, DocState>()
    private val _events = MutableSharedFlow<ChunkEvent>(extraBufferCapacity = 256)

    /** Live stream of non-empty chunk changes, for every document. Not replayed. */
    val events: SharedFlow<ChunkEvent> = _events.asSharedFlow()

    private fun state(documentId: String) = docs.computeIfAbsent(documentId) { DocState() }

    /** Re-chunks the document from the stored elements. Call after every element write. */
    suspend fun refresh(documentId: String): ChunkUpdate {
        val s = state(documentId)
        return s.mutex.withLock { refreshLocked(documentId, s) }
    }

    private suspend fun refreshLocked(documentId: String, s: DocState): ChunkUpdate {
        val elements = listElements(documentId)
        val update = s.tracker.update(elements)
        s.initialized = true
        if (!update.isEmpty) {
            val needed = (update.added + update.modified).flatMapTo(HashSet()) { it.strokeIds }
            val strokes = elements.filter { it.id in needed }.associateBy { it.id }
            _events.emit(ChunkEvent(documentId, update, strokes))
            log.info(
                "doc {}: +{} ~{} -{} chunks",
                documentId, update.added.size, update.modified.size, update.removed.size,
            )
        }
        return update
    }

    /** Current chunk layout (with page coordinates); chunks the document on first access. */
    suspend fun snapshot(documentId: String): List<Chunk> {
        val s = state(documentId)
        return s.mutex.withLock {
            if (!s.initialized) refreshLocked(documentId, s)
            s.tracker.snapshot()
        }
    }

    fun forget(documentId: String) {
        docs.remove(documentId)
    }
}
