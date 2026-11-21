package com.lauzhack.server.exercise

import com.lauzhack.shared.exercise.ConvertRegionRequest
import com.lauzhack.shared.exercise.ConvertRegionResponse
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.math.roundToInt

/**
 * Converts a selected PDF region into exercise Markdown: the model transcribes
 * text and math and locates figures; figures are cropped from the region image,
 * stored as assets and embedded as Markdown images.
 *
 * @param saveImage stores a PNG and returns the URL to embed
 */
class ExerciseImportService(
    private val converter: RegionConverter,
    private val saveImage: (png: ByteArray) -> String,
) {
    suspend fun convert(request: ConvertRegionRequest): ConvertRegionResponse {
        val png = Base64.decode(request.imagePngBase64)
        val retry = request.previousMarkdown?.let { RegionConverter.Retry(it, request.katexErrors) }
        val converted = converter.convert(png, request.pdfText, retry)
        return embedFigures(converted, png)
    }

    private fun embedFigures(converted: ConvertedRegion, png: ByteArray): ConvertRegionResponse {
        val image = ImageIO.read(ByteArrayInputStream(png)) ?: error("region is not a readable image")
        val urls = converted.figures.map { box -> crop(image, box)?.let(saveImage) }

        var markdown = PLACEHOLDER.replace(converted.markdown) { match ->
            val url = urls.getOrNull(match.groupValues[1].toInt() - 1)
            if (url != null) "![Figure ${match.groupValues[1]}]($url)" else ""
        }
        // figures the model located but forgot to place: append them at the end
        val placed = PLACEHOLDER.findAll(converted.markdown).map { it.groupValues[1].toInt() - 1 }.toSet()
        urls.forEachIndexed { i, url ->
            if (url != null && i !in placed) markdown += "\n\n![Figure ${i + 1}]($url)"
        }
        return ConvertRegionResponse(
            markdown = markdown.replace(Regex("\n{3,}"), "\n\n").trim(),
            figureCount = urls.count { it != null },
        )
    }

    companion object {
        private val PLACEHOLDER = Regex("""\[\[FIGURE (\d+)]]""")

        /** Margin around the model's box: its estimates are often a bit tight (labels, axes). */
        private const val PAD = 0.05

        /** Crops a figure; null when the box is degenerate or outside the image. */
        fun crop(image: BufferedImage, box: FigureBox): ByteArray? {
            if (box.width < 0.01 || box.height < 0.01) return null
            val x0 = ((box.x - PAD).coerceIn(0.0, 1.0) * image.width).roundToInt()
            val y0 = ((box.y - PAD).coerceIn(0.0, 1.0) * image.height).roundToInt()
            val x1 = ((box.x + box.width + PAD).coerceIn(0.0, 1.0) * image.width).roundToInt()
            val y1 = ((box.y + box.height + PAD).coerceIn(0.0, 1.0) * image.height).roundToInt()
            if (x1 - x0 < 8 || y1 - y0 < 8) return null
            return ByteArrayOutputStream().use { out ->
                ImageIO.write(image.getSubimage(x0, y0, x1 - x0, y1 - y0), "png", out)
                out.toByteArray()
            }
        }
    }
}
