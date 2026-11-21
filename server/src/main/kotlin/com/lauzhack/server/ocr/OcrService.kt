package com.lauzhack.server.ocr

import com.lauzhack.server.chunking.ChunkEvent
import com.lauzhack.shared.chunking.Chunk
import com.lauzhack.shared.chunking.ChunkTracker
import com.lauzhack.shared.domain.Element
import com.lauzhack.shared.ocr.ChunkTranscription
import com.lauzhack.shared.ocr.TranscribedChunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** What the reasoning agent receives from the OCR stage. */
sealed interface TranscriptionEvent {
    val documentId: String

    /** A chunk was added or changed; [chunk] carries its transcription, or null while OCR is pending. */
    data class Updated(override val documentId: String, val chunk: TranscribedChunk) : TranscriptionEvent

    data class Removed(override val documentId: String, val chunkId: String) : TranscriptionEvent
}

/**
 * Transcribes chunks as they are added or modified.
 *
 * - Only added/modified chunks are processed; untouched chunks keep their result.
 * - Results are cached by [Chunk.contentHash], so identical content (e.g. after
 *   undo) is never sent to the model twice.
 * - Each chunk waits [debounceMillis] before OCR and a newer version cancels the
 *   pending/in-flight job, so a block being written is not OCR'd at every save.
 * - A result that arrives for an outdated version of a chunk is cached but not published.
 *
 * @param recognizer null disables OCR (no API key): chunks stay pending.
 */
class OcrService(
    private val recognizer: ChunkRecognizer?,
    private val scope: CoroutineScope,
    private val rasterizer: ChunkRasterizer = ChunkRasterizer(),
    /** Segments the chunk image into symbol fragments injected into the OCR prompt. */
    private val annotator: SymbolAnnotator = SymbolAnnotator(),
    private val debounceMillis: Long = 1000,
    maxConcurrent: Int = 4,
    cacheSize: Int = 5000,
) {
    private val log = LoggerFactory.getLogger(OcrService::class.java)
    private val permits = Semaphore(maxConcurrent)

    private val cache = object : LinkedHashMap<String, ChunkTranscription>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ChunkTranscription>) = size > cacheSize
    }

    private class DocState {
        val mutex = Mutex()
        val chunks = HashMap<String, Chunk>()
        val jobs = HashMap<String, Job>()
    }

    private val docs = ConcurrentHashMap<String, DocState>()
    private val _events = MutableSharedFlow<TranscriptionEvent>(extraBufferCapacity = 256)

    /** Live stream of transcription changes for every document. Not replayed. */
    val events: SharedFlow<TranscriptionEvent> = _events.asSharedFlow()

    fun start(chunkEvents: Flow<ChunkEvent>): Job = scope.launch {
        chunkEvents.collect { handle(it) }
    }

    /** Cached transcription for this exact chunk content, if any. */
    fun cached(contentHash: String): ChunkTranscription? = synchronized(cache) { cache[contentHash] }

    /** Every known chunk of the document with its transcription (null = pending), in reading order. */
    suspend fun snapshot(documentId: String): List<TranscribedChunk> {
        val s = docs[documentId] ?: return emptyList()
        return s.mutex.withLock {
            s.chunks.values.sortedWith(ChunkTracker.chunkReadingOrder).map { TranscribedChunk(it, cached(it.contentHash)) }
        }
    }

    suspend fun handle(event: ChunkEvent) {
        val docId = event.documentId
        val s = docs.computeIfAbsent(docId) { DocState() }
        s.mutex.withLock {
            for (id in event.update.removed) {
                s.jobs.remove(id)?.cancel()
                s.chunks.remove(id)
                _events.emit(TranscriptionEvent.Removed(docId, id))
            }
            for (chunk in event.update.added + event.update.modified) {
                s.jobs.remove(chunk.id)?.cancel()
                s.chunks[chunk.id] = chunk
                val hit = cached(chunk.contentHash)
                _events.emit(TranscriptionEvent.Updated(docId, TranscribedChunk(chunk, hit)))
                if (hit == null && recognizer != null) {
                    s.jobs[chunk.id] = scope.launch { transcribe(docId, s, chunk, event.strokesOf(chunk), recognizer) }
                }
            }
        }
    }

    private suspend fun transcribe(
        docId: String,
        s: DocState,
        chunk: Chunk,
        strokes: List<Element>,
        recognizer: ChunkRecognizer,
    ) {
        val self = currentCoroutineContext()[Job]
        try {
            delay(debounceMillis)
            val png = withContext(Dispatchers.Default) { rasterizer.render(chunk.bounds, strokes) }
            val result = permits.withPermit {
                // pre-segmentation: symbol fragment coordinates ground the model's bboxes
                val fragments = withContext(Dispatchers.Default) { annotator.annotate(png) }
                recognizer.recognize(png, fragments)
            }
            synchronized(cache) { cache[chunk.contentHash] = result }
            log.info("doc {} {} v{}: {} {}", docId, chunk.id, chunk.version, result.kind, result.content)

            s.mutex.withLock {
                if (s.chunks[chunk.id]?.contentHash == chunk.contentHash) {
                    _events.emit(TranscriptionEvent.Updated(docId, TranscribedChunk(chunk, result)))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("OCR failed for doc {} {}: {}", docId, chunk.id, e.message)
        } finally {
            withContext(NonCancellable) {
                s.mutex.withLock { if (s.jobs[chunk.id] === self) s.jobs.remove(chunk.id) }
            }
        }
    }

    fun forget(documentId: String) {
        docs.remove(documentId)?.jobs?.values?.forEach { it.cancel() }
    }
}
