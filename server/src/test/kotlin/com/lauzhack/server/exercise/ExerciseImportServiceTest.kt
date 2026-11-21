package com.lauzhack.server.exercise

import com.lauzhack.shared.exercise.ConvertRegionRequest
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExerciseImportServiceTest {
    private fun png(w: Int = 200, h: Int = 100): String {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().use { ImageIO.write(img, "png", it); Base64.encode(it.toByteArray()) }
    }

    private val saved = mutableListOf<ByteArray>()
    private val save: (ByteArray) -> String = { bytes -> saved += bytes; "/api/assets/a${saved.size}" }

    private fun service(result: ConvertedRegion, seen: MutableList<RegionConverter.Retry?> = mutableListOf()) =
        ExerciseImportService({ _, _, retry -> seen += retry; result }, save)

    @Test
    fun figuresAreCroppedStoredAndEmbeddedWherePlaced() = runBlocking {
        val result = ConvertedRegion(
            markdown = "Let ${'$'}f(x)=x^2${'$'}.\n\n[[FIGURE 1]]\n\nFind ${'$'}f'(x)${'$'}.",
            figures = listOf(FigureBox(0.5, 0.0, 0.5, 1.0)),
        )
        val out = service(result).convert(ConvertRegionRequest(png()))

        assertEquals("Let ${'$'}f(x)=x^2${'$'}.\n\n![Figure 1](/api/assets/a1)\n\nFind ${'$'}f'(x)${'$'}.", out.markdown)
        assertEquals(1, out.figureCount)
        // right half of a 200×100 image, plus a 5% margin on the left, clamped at the edges
        val crop = ImageIO.read(ByteArrayInputStream(saved.single()))
        assertEquals(110, crop.width)
        assertEquals(100, crop.height)
    }

    @Test
    fun unplacedFiguresAreAppendedAndBadBoxesDropped() = runBlocking {
        val result = ConvertedRegion(
            markdown = "Text [[FIGURE 2]]",
            figures = listOf(FigureBox(0.1, 0.1, 0.3, 0.3), FigureBox(0.9, 0.9, 0.0, 0.0)),
        )
        val out = service(result).convert(ConvertRegionRequest(png()))
        assertEquals("Text \n\n![Figure 1](/api/assets/a1)", out.markdown)
        assertEquals(1, out.figureCount)
    }

    @Test
    fun noFiguresMeansPlainMarkdown() = runBlocking {
        val out = service(ConvertedRegion("${'$'}${'$'}\\int_0^1 x\\,dx${'$'}${'$'}")).convert(ConvertRegionRequest(png()))
        assertEquals("${'$'}${'$'}\\int_0^1 x\\,dx${'$'}${'$'}", out.markdown)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun retryInfoReachesTheConverter() = runBlocking {
        val seen = mutableListOf<RegionConverter.Retry?>()
        val svc = service(ConvertedRegion("ok"), seen)
        svc.convert(ConvertRegionRequest(png()))
        svc.convert(ConvertRegionRequest(png(), previousMarkdown = "${'$'}\\frac{1}${'$'}", katexErrors = listOf("Expected group")))
        assertNull(seen[0])
        assertEquals(listOf("Expected group"), seen[1]?.katexErrors)
    }
}
