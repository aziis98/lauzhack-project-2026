package com.lauzhack.shared.debug

import com.lauzhack.shared.agent.ConversationMessage
import com.lauzhack.shared.chunking.Chunk
import com.lauzhack.shared.hint.Hint
import com.lauzhack.shared.ocr.ChunkTranscription
import com.lauzhack.shared.ocr.SymbolFragment
import kotlinx.serialization.Serializable

/**
 * Wire-level debug view of the whole pipeline for one document, served by
 * `GET /api/documents/{id}/debug` and shown when the app runs with `?debug`.
 */

@Serializable
data class DebugPayload(
    val agent: DebugAgent,
    val ocr: DebugOcr,
    /** Macro-block chunks in page coordinates (for the canvas overlay). */
    val chunks: List<Chunk>,
    val hints: List<Hint>,
)

@Serializable
data class DebugAgent(
    val systemPrompt: String,
    val turns: List<DebugTurn>,
)

@Serializable
data class DebugTurn(
    val role: String,
    /** "page" | "delta" | "assistant" */
    val label: String,
    val content: String,
)

@Serializable
data class DebugOcr(
    val systemPrompt: String,
    val chunks: List<DebugOcrChunk>,
)

@Serializable
data class DebugOcrChunk(
    val chunkId: String,
    /** The exact user text sent to the OCR model (fragment list included). */
    val userText: String,
    /** Segmenter fragments in image pixel space (what the model was given). */
    val fragments: List<SymbolFragment>,
    /** OCR result for this chunk; null while pending. */
    val response: ChunkTranscription?,
    /** The model's `\bbox` symbols mapped into page coordinates. */
    val modelBoxes: List<DebugBox>,
    /** The segmenter fragments mapped into page coordinates. */
    val fragmentBoxes: List<DebugBox>,
)

/** A labeled rectangle in PAGE coordinates, drawn by the debug overlay. */
@Serializable
data class DebugBox(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
    val label: String,
)

/** Labels a conversation message for display ("page", "delta" or "assistant"). */
fun labelOf(message: ConversationMessage): String = when {
    message.role == "assistant" -> "assistant"
    message.content.trimStart().startsWith("""{"type":"page"""") -> "page"
    else -> "delta"
}
