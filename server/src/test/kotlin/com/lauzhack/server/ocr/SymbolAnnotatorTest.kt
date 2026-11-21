package com.lauzhack.server.ocr

import com.lauzhack.shared.chunking.BoundingBox
import com.lauzhack.shared.domain.Element
import com.lauzhack.shared.domain.ElementData
import com.lauzhack.shared.domain.Segment
import com.lauzhack.shared.domain.Style
import com.lauzhack.shared.ocr.stripBboxes
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SymbolAnnotatorTest {

    private val rasterizer = ChunkRasterizer()
    private val annotator = SymbolAnnotator()

    private val bounds = BoundingBox(minX = 0.0, minY = 0.0, maxX = 600.0, maxY = 400.0)

    private var nextId = 0

    /** A freehand stroke from page-space (x1,y1) through the given points; a single point is a dot. */
    private fun stroke(vararg points: Double): Element {
        require(points.size >= 2 && points.size % 2 == 0) { "points must be (x,y) pairs" }
        val pts = points.toList()
        return Element(
            id = "shape:${nextId++}",
            type = "stroke",
            x = 0.0,
            y = 0.0,
            zIndex = "a${nextId}",
            style = Style(size = "m"),
            data = ElementData.Stroke(segments = listOf(Segment(points = pts)), complete = true),
        )
    }

    private fun annotate(vararg strokes: Element) = annotator.annotate(rasterizer.render(bounds, strokes.toList()))

    @Test
    fun separatedStrokesYieldSeparateFragments() {
        val fragments = annotate(
            stroke(50.0, 100.0, 150.0, 100.0),   // left symbol
            stroke(350.0, 100.0, 450.0, 100.0), // far right symbol — beyond the dilation radius
        )
        assertEquals(2, fragments.size)
        // reading order: left fragment first, boxes do not overlap
        assertTrue(fragments[0].maxX < fragments[1].minX, "expected left-then-right, got $fragments")
        assertTrue(fragments[0].id == 0 && fragments[1].id == 1)
    }

    @Test
    fun nearbyDotsMergeIntoOneFragment() {
        val fragments = annotate(
            stroke(100.0, 100.0),               // a dot
            stroke(103.0, 102.0),               // a second dot a few px away — inside the dilation radius
            stroke(400.0, 300.0, 410.0, 305.0), // a separate stroke far away
        )
        assertEquals(2, fragments.size)
        assertTrue(fragments[0].width in 1..80, "merged fragment should be compact, got $fragments")
    }

    @Test
    fun tinySpeckIsFilteredAsNoise() {
        // a lone, single-point dot: a few pixels of ink, well below minArea
        val fragments = annotate(stroke(300.0, 200.0))
        assertTrue(fragments.isEmpty(), "a single speck should be dropped, got $fragments")
    }

    @Test
    fun renderAnnotatedProducesAPng() {
        val png = rasterizer.render(bounds, listOf(stroke(50.0, 100.0, 150.0, 100.0)))
        val annotated = annotator.renderAnnotated(png, annotator.annotate(png))
        assertTrue(annotated.size > png.size, "annotation should add pixels, not shrink the image")
    }

    @Test
    fun bboxBackgroundTintsPaperButKeepsInk() {
        val png = rasterizer.render(bounds, listOf(stroke(50.0, 100.0, 150.0, 100.0)))
        val fragments = annotator.annotate(png)
        assertTrue(fragments.isNotEmpty(), "expected a fragment")
        val f = fragments[0]

        val before = ImageIO.read(ByteArrayInputStream(png))
        val after = ImageIO.read(ByteArrayInputStream(annotator.renderBboxBackground(png, fragments)))

        // just above the tight bbox (inside the 2px wash margin): paper becomes gray
        val px = f.minX + f.width / 2
        val py = f.minY - 1
        assertEquals(0xFFFFFF, before.getRGB(px, py) and 0xFFFFFF, "expected white paper before the wash")
        assertEquals(0xEDEDED, after.getRGB(px, py) and 0xFFFFFF, "expected light gray behind the bbox")

        // the ink inside the bbox stays dark
        val cy = f.minY + f.height / 2
        val inkLum = lum(after, px, cy)
        assertTrue(inkLum < 120, "expected dark ink inside the bbox, got luminance $inkLum")

        // far from the fragment the paper is untouched
        assertEquals(0xFFFFFF, after.getRGB(0, 0) and 0xFFFFFF, "paper outside the bbox must stay white")
    }

    private fun lum(image: java.awt.image.BufferedImage, x: Int, y: Int): Int {
        val rgb = image.getRGB(x, y)
        return ((rgb shr 16 and 0xff) + (rgb shr 8 and 0xff) + (rgb and 0xff)) / 3
    }

    @Test
    fun stripBboxesKeepsInnerContent() {
        val augmented = "\\bbox[12,34,80,61]{\\int_0^1} \\bbox[96,40,130,63]{x^2} \\, dx = \\bbox[148,44,190,66]{\\frac{1}{3}}"
        assertEquals(
            "\\int_0^1 x^2 \\, dx = \\frac{1}{3}",
            augmented.stripBboxes(),
        )
    }

    @Test
    fun stripBboxesLeavesPlainContentAlone() {
        val plain = "Per il teorema di Rolle esiste \$c\$ tale che \$f'(c)=0\$"
        assertEquals(plain, plain.stripBboxes())
    }
}
