package com.lauzhack.server.ocr

import boofcv.abst.filter.binary.BinaryLabelContourFinderChang2004
import boofcv.alg.filter.binary.BinaryImageOps
import boofcv.alg.filter.binary.ThresholdImageOps
import boofcv.io.image.ConvertBufferedImage
import boofcv.struct.image.GrayS32
import boofcv.struct.image.GrayU8
import com.lauzhack.shared.ocr.SymbolFragment
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Segments a rasterized chunk (black ink on white) into symbol fragments,
 * so the OCR model can be given the coordinates of every handwriting blob.
 *
 * Pipeline (per the team's algorithm, using BoofCV for the image ops):
 * 1. grayscale → ink mask: `gray < threshold`
 * 2. dilate the mask with a square kernel of radius [dilateRadius], so strokes
 *    within that distance of each other become one connected blob
 * 3. label connected components of the dilated mask (8-connectivity)
 * 4. tight bounding boxes per component, computed on the ORIGINAL ink so the
 *    dilation does not inflate the boxes
 * 5. drop components smaller than [minArea] pixels
 *
 * The fragments are sorted in reading order (minY, then minX) and renumbered.
 */
class SymbolAnnotator(
    /** Ink threshold on grayscale; pixels below are handwriting. */
    private val threshold: Int = 160,
    /** Dilation radius (pixels): fragments of one symbol merge within this distance. */
    private val dilateRadius: Int = 6,
    /** Components with fewer original-ink pixels than this are dropped as noise. */
    private val minArea: Int = 80,
) {
    init {
        System.setProperty("java.awt.headless", "true")
    }

    /** Segments a chunk PNG. Returns empty for unreadable or blank images. */
    fun annotate(png: ByteArray): List<SymbolFragment> {
        val image = runCatching { ImageIO.read(ByteArrayInputStream(png)) }.getOrNull()
            ?: return emptyList()
        return annotate(image)
    }

    fun annotate(image: BufferedImage): List<SymbolFragment> {
        val gray = ConvertBufferedImage.convertFrom(image, null as GrayU8?)

        // 1) ink mask: down = true → pixels BELOW the threshold become 1
        val ink = ThresholdImageOps.threshold(gray, GrayU8(gray.width, gray.height), threshold, true)

        // 2) merge strokes of the same symbol (square kernel ≈ Chebyshev distance r)
        val blob = BinaryImageOps.dilate8(ink, dilateRadius, null)

        // 3) label connected components (Chang 2004); labels come back as
        //    ContourPacked.id — 1-based and not guaranteed contiguous
        val labeled = GrayS32(gray.width, gray.height)
        val alg = BinaryLabelContourFinderChang2004()
        alg.process(blob, labeled)
        val contours = alg.contours
        if (contours.isEmpty()) return emptyList()

        val labelToIndex = HashMap<Int, Int>(contours.size * 2)
        contours.forEachIndexed { index, contour -> labelToIndex[contour.id] = index }

        val count = contours.size
        val minX = IntArray(count) { Int.MAX_VALUE }
        val minY = IntArray(count) { Int.MAX_VALUE }
        val maxX = IntArray(count) { Int.MIN_VALUE }
        val maxY = IntArray(count) { Int.MIN_VALUE }
        val area = IntArray(count)

        // 4) tight bbox per component, from the ORIGINAL ink (undoes the dilation)
        for (y in 0 until gray.height) {
            for (x in 0 until gray.width) {
                if (ink.unsafe_get(x, y) == 0) continue
                val index = labelToIndex[labeled.unsafe_get(x, y)] ?: continue
                if (x < minX[index]) minX[index] = x
                if (y < minY[index]) minY[index] = y
                if (x > maxX[index]) maxX[index] = x
                if (y > maxY[index]) maxY[index] = y
                area[index]++
            }
        }

        // 5) drop noise, sort in reading order, renumber
        return (0 until count)
            .filter { area[it] >= minArea && minX[it] <= maxX[it] }
            .map { SymbolFragment(it, minX[it], minY[it], maxX[it], maxY[it], area[it]) }
            .sortedWith(compareBy({ it.minY }, { it.minX }))
            .mapIndexed { index, fragment -> fragment.copy(id = index) }
    }

    /** Debug render: the original image with a colored box + id label per fragment. */
    fun renderAnnotated(png: ByteArray, fragments: List<SymbolFragment>): ByteArray {
        val original = runCatching { ImageIO.read(ByteArrayInputStream(png)) }.getOrNull()
            ?: return png
        val image = BufferedImage(original.width, original.height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.drawImage(original, 0, 0, null)
            g.stroke = BasicStroke(2f)
            for ((index, f) in fragments.withIndex()) {
                g.color = PALETTE[index % PALETTE.size]
                // 2px margin so the outline clears the ink
                g.drawRect(f.minX - 2, f.minY - 2, f.width + 4, f.height + 4)
                g.drawString("#${f.id}", (f.minX - 2).coerceAtLeast(2), (f.minY - 6).coerceAtLeast(12))
            }
        } finally {
            g.dispose()
        }
        return ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "png", out)
            out.toByteArray()
        }
    }

    private companion object {
        val PALETTE = listOf(
            Color(0xE63946), // red
            Color(0x2563EB), // blue
            Color(0x2A9D8F), // teal
            Color(0xB58400), // amber
            Color(0x7C3AED), // violet
            Color(0x0F766E), // pine
        )
    }
}
