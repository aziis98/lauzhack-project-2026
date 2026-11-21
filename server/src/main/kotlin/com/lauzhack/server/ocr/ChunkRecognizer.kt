package com.lauzhack.server.ocr

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import com.lauzhack.shared.ocr.ChunkKind
import com.lauzhack.shared.ocr.ChunkTranscription
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/** Turns the image of one chunk into a [ChunkTranscription]. */
fun interface ChunkRecognizer {
    suspend fun recognize(png: ByteArray): ChunkTranscription
}

/** [ChunkRecognizer] backed by a light vision model through Koog, with structured (JSON) output. */
class KoogChunkRecognizer(
    private val executor: PromptExecutor,
    private val model: LLModel,
) : ChunkRecognizer {

    /** Wire format the model fills in; kept separate from the shared domain type. */
    @Serializable
    private data class OcrOutput(val kind: ChunkKind, val content: String)

    override suspend fun recognize(png: ByteArray): ChunkTranscription {
        val request = prompt("chunk-ocr") {
            system(SYSTEM_PROMPT)
            user {
                text("Transcribe this block.")
                image(AttachmentSource.Image(content = AttachmentContent.Binary.Bytes(png), format = "png"))
            }
        }
        val output = executor.executeStructured(request, model, serializer<OcrOutput>(), examples = EXAMPLES)
            .getOrThrow().data
        return ChunkTranscription(output.kind, output.content.trim())
    }

    private companion object {
        /** Shown to models without native JSON-schema support (Koog's manual structured mode). */
        val EXAMPLES = listOf(
            OcrOutput(ChunkKind.MATH, "\\int_0^1 x^2 \\, dx = \\frac{1}{3}"),
            OcrOutput(ChunkKind.TEXT, "Per il teorema di Rolle esiste ${'$'}c${'$'} tale che ${'$'}f'(c)=0${'$'}"),
            OcrOutput(ChunkKind.HINT_REQUEST, ""),
        )

        val SYSTEM_PROMPT = """
            You transcribe handwriting from a student's math worksheet. Each image is ONE block
            of handwriting cropped from the page (black ink on white).

            Classify the block as `kind`:
            - MATH: a formula, equation or calculation step.
            - TEXT: words or sentences, possibly with inline math.
            - HINT_REQUEST: a question mark "?" enclosed in a hand-drawn circle (or a closed loop
              around it). This is how the student asks for help.
            - QUESTION_MARK: a lone question mark "?" that is NOT circled.
            - DRAWING: a sketch, graph, arrow, box or other non-textual mark.
            - UNREADABLE: illegible or empty.

            Fill `content`:
            - MATH: LaTeX, without $ delimiters. Use \\ to separate lines when there are several.
            - TEXT: plain text, with inline math as ${'$'}...${'$'}.
            - DRAWING: a short description of what is drawn.
            - HINT_REQUEST, QUESTION_MARK, UNREADABLE: empty string.

            Transcribe LITERALLY: never fix mistakes, never complete or simplify expressions, never
            guess content that is not there. A wrong calculation must stay wrong in the transcription.
        """.trimIndent()
    }
}
