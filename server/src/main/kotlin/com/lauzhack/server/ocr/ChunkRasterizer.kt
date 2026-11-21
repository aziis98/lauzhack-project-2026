package com.lauzhack.server.ocr

import com.lauzhack.shared.chunking.BoundingBox
import com.lauzhack.shared.chunking.penWidth
import com.lauzhack.shared.chunking.toPageSpacePolylines
import com.lauzhack.shared.domain.Element
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Renders the strokes of one chunk to a PNG (black ink on white), cropped to
 * the chunk bounds. Rendering from the stored geometry keeps the server
 * independent of the web editor: any client that sends strokes gets OCR.
 */
class ChunkRasterizer(
    /** Page units → pixels for normal-sized writing. */
    private val preferredScale: Double = 2.0,
    /** Longest image side; bigger chunks are scaled down to fit (vision models downscale anyway). */
    private val maxSidePx: Int = 1568,
    private val paddingPx: Int = 16,
) {
    init {
        System.setProperty("java.awt.headless", "true")
    }

    fun render(bounds: BoundingBox, strokes: List<Element>): ByteArray {
        val longest = max(bounds.width, bounds.height).coerceAtLeast(1.0)
        val scale = min(preferredScale, (maxSidePx - 2 * paddingPx) / longest)
        val w = ceil(bounds.width * scale).toInt() + 2 * paddingPx
        val h = ceil(bounds.height * scale).toInt() + 2 * paddingPx

        val image = BufferedImage(max(w, 1), max(h, 1), BufferedImage.TYPE_BYTE_GRAY)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.color = Color.WHITE
            g.fillRect(0, 0, image.width, image.height)
            g.color = Color.BLACK

            for (element in strokes) {
                val width = (penWidth(element.style?.size) * scale).toFloat().coerceAtLeast(1.5f)
                g.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                for (pts in element.toPageSpacePolylines()) {
                    val path = Path2D.Double()
                    val px = { i: Int -> (pts[i] - bounds.minX) * scale + paddingPx }
                    val py = { i: Int -> (pts[i + 1] - bounds.minY) * scale + paddingPx }
                    path.moveTo(px(0), py(0))
                    if (pts.size == 2) path.lineTo(px(0), py(0)) // a dot
                    var i = 2
                    while (i + 1 < pts.size) {
                        path.lineTo(px(i), py(i))
                        i += 2
                    }
                    g.draw(path)
                }
            }
        } finally {
            g.dispose()
        }

        return ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "png", out)
            out.toByteArray()
        }
    }
}
