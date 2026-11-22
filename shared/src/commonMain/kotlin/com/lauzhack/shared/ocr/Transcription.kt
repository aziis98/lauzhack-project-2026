package com.lauzhack.shared.ocr

import com.lauzhack.shared.chunking.Chunk
import kotlinx.serialization.Serializable

/** What a chunk of handwriting turned out to be. */
@Serializable
enum class ChunkKind {
    /** A formula, equation or calculation step. */
    MATH,

    /** Prose (words), possibly with inline math. */
    TEXT,

    /** A lone question mark, not circled. */
    QUESTION_MARK,

    /** A question mark enclosed in a circle: the student asks for a hint. */
    HINT_REQUEST,

    /** A sketch, graph, arrow or other non-textual mark. */
    DRAWING,

    /** Illegible or empty. */
    UNREADABLE,
}

/**
 * OCR result for one chunk.
 *
 * @property content LaTeX for [ChunkKind.MATH]; plain text with inline `$...$`
 *   math for [ChunkKind.TEXT]; a short description for [ChunkKind.DRAWING];
 *   empty otherwise. Transcribed literally, mistakes included.
 * @property plainContent Clean transcription without `\bbox` markers, produced
 *   by the OCR model before annotating symbol bounding boxes.
 */
@Serializable
data class ChunkTranscription(
    val kind: ChunkKind,
    val content: String,
    val plainContent: String? = null,
)

/**
 * A chunk with its (possibly still pending) transcription. This is what the
 * reasoning model sees: the content of each block plus where it sits on the page.
 *
 * @property transcription null while OCR for the current [Chunk.version] is pending
 */
@Serializable
data class TranscribedChunk(
    val chunk: Chunk,
    val transcription: ChunkTranscription? = null,
)
