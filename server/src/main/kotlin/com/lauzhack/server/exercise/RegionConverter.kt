package com.lauzhack.server.exercise

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.model.executeStructured
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/** A figure's position inside the region image, as fractions (0..1) of its width/height. */
@Serializable
data class FigureBox(val x: Double, val y: Double, val width: Double, val height: Double)

/**
 * @property markdown Markdown + LaTeX with `[[FIGURE n]]` placeholders (1-based) where figures go
 * @property figures bounding box of figure n at index n-1
 */
@Serializable
data class ConvertedRegion(val markdown: String, val figures: List<FigureBox> = emptyList())

/** Turns the image of a PDF region into exercise Markdown. */
fun interface RegionConverter {
    suspend fun convert(png: ByteArray, pdfText: String, retry: Retry?): ConvertedRegion

    /** A second attempt after the first transcription had KaTeX errors. */
    data class Retry(val previousMarkdown: String, val katexErrors: List<String>)
}

/** [RegionConverter] backed by a vision model through Koog, with structured (JSON) output. */
class KoogRegionConverter(
    private val executor: PromptExecutor,
    private val model: LLModel,
) : RegionConverter {

    override suspend fun convert(png: ByteArray, pdfText: String, retry: RegionConverter.Retry?): ConvertedRegion {
        val request = prompt("exercise-import") {
            system(SYSTEM_PROMPT)
            user {
                text(buildString {
                    appendLine("Convert this region of an exercise sheet.")
                    if (pdfText.isNotBlank()) {
                        appendLine()
                        appendLine("Text the PDF reports inside the region (exact words; math may be garbled or missing):")
                        appendLine("<<<")
                        appendLine(pdfText.trim())
                        appendLine(">>>")
                    }
                    if (retry != null) {
                        appendLine()
                        appendLine("Your previous answer was:")
                        appendLine("<<<")
                        appendLine(retry.previousMarkdown)
                        appendLine(">>>")
                        appendLine("KaTeX could not render it: ${retry.katexErrors.joinToString("; ")}")
                        appendLine("Return the corrected conversion (same content, valid KaTeX LaTeX).")
                    }
                })
                image(AttachmentSource.Image(content = AttachmentContent.Binary.Bytes(png), format = "png"))
            }
        }
        return executor.executeStructured(request, model, serializer<ConvertedRegion>(), examples = EXAMPLES)
            .getOrThrow().data
    }

    private companion object {
        val EXAMPLES = listOf(
            ConvertedRegion(
                markdown = "**Exercise 3.** Let ${'$'}f(x) = x^3 - 3x${'$'}.\n\n1. Find the critical points of ${'$'}f${'$'}.\n2. Compute\n\n" +
                    "${'$'}${'$'}\\int_0^1 f(x)\\,dx${'$'}${'$'}\n\n[[FIGURE 1]]",
                figures = listOf(FigureBox(x = 0.10, y = 0.62, width = 0.55, height = 0.35)),
            ),
        )

        val SYSTEM_PROMPT = """
            You convert a region of a printed exercise sheet (an image cropped from a PDF) into Markdown
            with LaTeX math, to be rendered with KaTeX.

            `markdown`:
            - Reproduce the text EXACTLY, in its original language: same words, numbering, lists, bold
              labels such as "Exercise 2". Do not solve, summarize, translate or add anything.
            - Inline math as ${'$'}...${'$'}, displayed formulas as ${'$'}${'$'}...${'$'}${'$'} on their own line.
              Use only LaTeX that KaTeX supports (e.g. \frac, \sqrt, \int, \lim, \begin{cases},
              \begin{pmatrix}); no \label, \eqref or custom macros.
            - Use the PDF text, when given, for exact wording and numbers; rebuild the math structure
              (fractions, exponents, indices) from the image.
            - Every figure, graph, diagram, drawing or picture: put a placeholder line `[[FIGURE n]]`
              (n = 1, 2, … in reading order) where it appears, and do NOT transcribe text inside it.

            `figures`: for figure n, at index n-1, its bounding box in the image as fractions of the image
            width and height: x, y = top-left corner, width, height (all between 0 and 1). Include the
            whole figure with its labels, and nothing else. Empty list when there are no figures.
        """.trimIndent()
    }
}
