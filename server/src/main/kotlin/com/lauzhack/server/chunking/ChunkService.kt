package com.lauzhack.server.chunking

import com.lauzhack.server.db.listElements
import com.lauzhack.shared.chunking.Chunk
import com.lauzhack.shared.chunking.ChunkTracker
import com.lauzhack.shared.chunking.ChunkUpdate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps one [ChunkTracker] per document, re-chunks the page after every
 * element write, and pushes the resulting [ChunkUpdate]s to subscribers
 * (the OCR pipeline / reasoning agent) via [updates].
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
        val flow = MutableSharedFlow<ChunkUpdate>(extraBufferCapacity = 64)
    }

    private val docs = ConcurrentHashMap<String, DocState>()

    private fun state(documentId: String) = docs.computeIfAbsent(documentId) { DocState() }

    /** Re-chunks the document from the stored elements. Call after every element write. */
    suspend fun refresh(documentId: String): ChunkUpdate {
        val s = state(documentId)
        return s.mutex.withLock { refreshLocked(documentId, s) }
    }

    private fun refreshLocked(documentId: String, s: DocState): ChunkUpdate {
        val update = s.tracker.update(listElements(documentId))
        s.initialized = true
        if (!update.isEmpty) {
            s.flow.tryEmit(update)
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

    /**
     * Live stream of non-empty updates for one document. Not replayed: a late
     * subscriber should read [snapshot] first, then apply the updates.
     */
    fun updates(documentId: String): SharedFlow<ChunkUpdate> = state(documentId).flow.asSharedFlow()

    fun forget(documentId: String) {
        docs.remove(documentId)
    }
}
