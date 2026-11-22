package com.lauzhack.server.ocr

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import com.lauzhack.shared.ocr.ChunkKind
import com.lauzhack.shared.ocr.ChunkTranscription
import com.lauzhack.shared.ocr.SymbolFragment
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/** Turns the image of one chunk into a [ChunkTranscription]. */
fun interface ChunkRecognizer {
    suspend fun recognize(png: ByteArray, fragments: List<SymbolFragment>): ChunkTranscription
}

/** The exact user text sent to the OCR model alongside the image. */
internal fun buildOcrUserText(fragments: List<SymbolFragment>): String {
    val fragmentList = if (fragments.isEmpty()) {
        "(none detected)"
    } else {
        fragments.joinToString(" ") { "(${it.minX},${it.minY},${it.maxX},${it.maxY})" }
    }
    return "Transcribe this block.\n" +
        "Symbol fragments detected in the image (minx,miny,maxx,maxy), left to right:\n" +
        fragmentList
}

/** System prompt of the OCR model — single source of truth (also shown by ?debug). */
internal val OCR_SYSTEM_PROMPT = """
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

    The message also lists the symbol fragments detected in the image, as
    (minx,miny,maxx,maxy) in image pixel space, left to right. Each fragment is one
    connected handwriting blob (strokes of the same symbol are already merged).

    First, fill `plainContent` with the clean markdown + LaTeX transcription WITHOUT any
    `\bbox` markers:
    - MATH: ${'$'}${'$'} no; use LaTeX without ${'$'} delimiters, e.g. `\int_0^1 x^2 \, dx = \frac{1}{3}`.
      Use \\ to separate lines when there are several.
    - TEXT: plain text with inline math as ${'$'}...${'$'}, e.g.
      `By Rolle's theorem there exists ${'$'}c${'$'} such that ${'$'}f'(c)=0${'$'}`.
    - DRAWING: a short description of what is drawn.
    - HINT_REQUEST, QUESTION_MARK, UNREADABLE: empty string.

    Then, fill `content` with the exact same transcription as `plainContent`, using this
    AUGMENTED syntax: wrap every symbol you transcribe in a bbox marker, e.g.:
    - MATH: ${'$'}${'$'} no; use LaTeX without ${'$'} delimiters, one bbox per symbol:
      \bbox[12,34,80,61]{\int_0^1} \bbox[96,40,130,63]{x^2} \, dx = \bbox[148,44,190,66]{\frac{1}{3}}
      Use \\ to separate lines when there are several.
    - TEXT: plain text with inline math as ${'$'}...${'$'}, the math symbols inside bboxes:
      By Rolle's theorem there exists ${'$'}\bbox[31,40,58,63]{c}${'$'} such that ${'$'}\bbox[70,41,110,64]{f'(c)=0}${'$'}
    - DRAWING: a short description of what is drawn (no bboxes needed).
    - HINT_REQUEST, QUESTION_MARK, UNREADABLE: empty string.

    Bbox rules — this is the critical part:
    - COPY the coordinates VERBATIM from the provided fragment list; never invent, guess
      or modify a coordinate.
    - Transcribe symbol by symbol: each bbox marker contains ONE symbol (e.g. one
      relation, variable, digit or operator), not a whole expression.
    - Use ONLY the fragments you actually transcribe. Skip fragments that are noise,
      specks, parts of a drawing, or ink you cannot read — leftover fragments in the
      list are intentionally ignored.
    - Order the bbox markers following the image's reading order.

    Transcribe LITERALLY: never fix mistakes, never complete or simplify expressions, never
    guess content that is not there. A wrong calculation must stay wrong in the transcription.
""".trimIndent()

/**
 * [ChunkRecognizer] backed by a light vision model through Koog, with structured (JSON) output.
 *
 * @param reasoningEnabled DeepSeek V4.1 Flash is a hybrid thinking-by-default model on Together;
 *   `reasoning={"enabled": false}` (Together-specific, passed via additionalProperties) is the
 *   documented way to skip the chain of thought. `reasoning_effort` is NOT used here: for
 *   DeepSeek V4 it only accepts "high"/"max", and Together maps everything else to "high" —
 *   so a low effort value would still think at full depth.
 */
class KoogChunkRecognizer(
    private val executor: PromptExecutor,
    private val model: LLModel,
    private val reasoningEnabled: Boolean = false,
) : ChunkRecognizer {

    private val log = org.slf4j.LoggerFactory.getLogger(KoogChunkRecognizer::class.java)

    /** Wire format the model fills in; `plainContent` comes before `content` so the model transcribes cleanly first. */
    @Serializable
    private data class OcrOutput(
        val kind: ChunkKind,
        val plainContent: String,
        val content: String,
    )

    override suspend fun recognize(png: ByteArray, fragments: List<SymbolFragment>): ChunkTranscription {
        val request = prompt("chunk-ocr") {
            system(OCR_SYSTEM_PROMPT)
            user {
                text(buildOcrUserText(fragments))
                image(AttachmentSource.Image(content = AttachmentContent.Binary.Bytes(png), format = "png"))
            }
        }.withParams(
            OpenAIChatParams(
                additionalProperties = mapOf(
                    // Together-specific reasoning toggle; see the class doc
                    "reasoning" to buildJsonObject { put("enabled", reasoningEnabled) },
                ),
            ),
        )
        val start = System.nanoTime()
        val output = executor.executeStructured(request, model, serializer<OcrOutput>(), examples = EXAMPLES)
            .getOrThrow().data
        log.info(
            "ocr-model-timing: model={}ms reasoning_enabled={} model_id={} fragments={}",
            (System.nanoTime() - start) / 1_000_000,
            reasoningEnabled,
            model.id,
            fragments.size,
        )
        return ChunkTranscription(output.kind, output.content.trim(), output.plainContent.trim())
    }

    private companion object {
        /** Shown to models without native JSON-schema support (Koog's manual structured mode). */
        val EXAMPLES = listOf(
            OcrOutput(
                ChunkKind.MATH,
                "\\int_0^1 x^2 \\, dx = \\frac{1}{3}",
                "\\bbox[12,34,80,61]{\\int_0^1} \\bbox[96,40,130,63]{x^2} \\, dx = \\bbox[148,44,190,66]{\\frac{1}{3}}",
            ),
            OcrOutput(
                ChunkKind.TEXT,
                "By Rolle's theorem there exists ${'$'}c${'$'} such that ${'$'}f'(c)=0${'$'}",
                "By Rolle's theorem there exists ${'$'}\\bbox[31,40,58,63]{c}${'$'} such that ${'$'}\\bbox[70,41,110,64]{f'(c)=0}${'$'}",
            ),
            OcrOutput(ChunkKind.HINT_REQUEST, "", ""),
        )

    }
}
