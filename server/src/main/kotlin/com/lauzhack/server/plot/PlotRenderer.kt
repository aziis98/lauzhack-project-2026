package com.lauzhack.server.plot

import com.lauzhack.shared.plot.Highlight
import com.lauzhack.shared.plot.MathExpression
import com.lauzhack.shared.plot.PlotSpec
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * Renders a [PlotSpec] to a PNG, deterministically: parse the DSL, sample the
 * expression over each domain interval, draw axes/grid/curve and the computed
 * highlights. Nothing here consults a model.
 */
object PlotRenderer {

    private const val W = 960
    private const val H = 640
    private const val M = 56.0 // margin

    private val BG = Color(0xFA, 0xFA, 0xF7)
    private val GRID = Color(0xE4, 0xE4, 0xDE)
    private val AXIS = Color(0x55, 0x55, 0x50)
    private val CURVE = Color(0x1A, 0x5F, 0xB4)
    private val INK = Color(0x22, 0x22, 0x20)
    private val RED = Color(0xC6, 0x28, 0x28)

    fun render(spec: PlotSpec): ByteArray {
        val parsed = MathExpression.parse(spec.expression, spec.variable)
        fun f(x: Double): Double = parsed.evaluate(x)

        // ---- sample the whole domain, per interval (a non-finite value breaks the curve) ----
        data class Poly(val points: List<Pair<Double, Double>>)

        val polys = ArrayList<Poly>()
        var current = ArrayList<Pair<Double, Double>>()
        val ys = ArrayList<Double>()
        for (interval in spec.domain) {
            val n = maxOf(2, spec.samples / maxOf(1, spec.domain.size))
            for (i in 0..n) {
                val x = interval.from + (interval.to - interval.from) * i / n
                val y = f(x)
                if (y.isFinite()) {
                    current.add(x to y)
                    ys.add(y)
                } else {
                    if (current.size > 1) polys.add(Poly(current))
                    current = ArrayList()
                }
            }
            if (current.size > 1) polys.add(Poly(current))
            current = ArrayList()
        }

        // ---- ranges: x from the domain, y from finite samples clipped to sane percentiles ----
        val x0 = spec.domain.minOf { it.from }
        val x1 = spec.domain.maxOf { it.to }
        val sorted = ys.sorted()
        val yLo0 = sorted.getOrNull((sorted.size * 0.02).toInt().coerceIn(sorted.indices)) ?: -1.0
        val yHi0 = sorted.getOrNull((sorted.size * 0.98).toInt().coerceIn(sorted.indices)) ?: 1.0
        val pad = 0.08 * (yHi0 - yLo0).coerceAtLeast(1e-9)
        var yLo = yLo0 - pad
        var yHi = yHi0 + pad
        if (yHi - yLo < 1e-9) { yLo -= 1.0; yHi += 1.0 }
        // always show y = 0 when it is close, so roots make sense visually
        if (yLo > 0 && yLo < (yHi - yLo)) yLo = -0.05 * (yHi - yLo)
        if (yHi < 0 && yHi > -(yHi - yLo)) yHi = 0.05 * (yHi - yLo)

        fun px(x: Double) = M + (x - x0) / (x1 - x0) * (W - 2 * M)
        fun py(y: Double) = H - M - (y - yLo) / (yHi - yLo) * (H - 2 * M)

        // ---- raster ----
        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = BG
            g.fillRect(0, 0, W, H)
            g.font = Font(Font.MONOSPACED, Font.PLAIN, 13)

            fun ticks(lo: Double, hi: Double, count: Int): List<Double> {
                val raw = (hi - lo) / count
                val mag = Math.pow(10.0, Math.floor(Math.log10(raw)))
                val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * mag }.first { it >= raw }
                return generateSequence(Math.floor(lo / step) * step) { it + step }.takeWhile { it <= hi + 1e-9 }.toList()
            }

            // grid
            g.color = GRID
            for (tx in ticks(x0, x1, 9)) {
                val X = px(tx)
                g.drawLine(X.toInt(), M.toInt(), X.toInt(), (H - M).toInt())
                g.color = AXIS.darker()
                g.drawString(fmt(tx), (X - 14).toInt().coerceAtLeast(2), (H - M + 22).toInt())
                g.color = GRID
            }
            for (ty in ticks(yLo, yHi, 7)) {
                val Y = py(ty).toInt()
                g.drawLine(M.toInt(), Y, (W - M).toInt(), Y)
                g.color = AXIS.darker()
                g.drawString(fmt(ty), 6, Y + 4)
                g.color = GRID
            }

            // axes on zero when visible, else on the frame
            g.color = AXIS
            val axY = py(0.0).toInt().coerceIn(M.toInt(), (H - M).toInt())
            val axX = px(0.0).toInt().coerceIn(M.toInt(), (W - M).toInt())
            g.drawLine(M.toInt(), axY, (W - M).toInt(), axY)
            g.drawLine(axX, M.toInt(), axX, (H - M).toInt())

            // the function
            g.color = CURVE
            g.stroke = java.awt.BasicStroke(2.4f)
            for (poly in polys) {
                var first = true
                var prevX = 0.0
                var prevY = 0.0
                for ((x, y) in poly.points) {
                    val Y = py(y)
                    if (Y < -H || Y > 2 * H) { first = true; continue } // clipped: break the line
                    if (!first) g.drawLine(px(prevX).toInt(), py(prevY).toInt(), px(x).toInt(), Y.toInt())
                    prevX = x; prevY = y; first = false
                }
            }

            // highlights
            val mark = Font(Font.MONOSPACED, Font.BOLD, 12)
            for (h in spec.highlights) {
                val y = h.y ?: f(h.x)
                if (!y.isFinite()) continue
                val X = px(h.x).toInt()
                val Y = py(y).toInt()
                if (Y < 0 || Y > H) continue
                g.color = RED
                g.fillOval(X - 4, Y - 4, 8, 8)
                g.font = mark
                val label = h.label ?: h.kind.name.lowercase()
                val tx = (X + 8).coerceAtMost(W - 90)
                g.drawString("(${fmt(h.x)}, ${fmt(y)}) $label", tx, (Y - 8).coerceAtLeast(16))
            }

            // caption
            g.color = INK
            g.font = Font(Font.MONOSPACED, Font.BOLD, 15)
            val name = spec.functionName?.let { "$it(${spec.variable}) = " } ?: ""
            g.drawString("$name${spec.expression}", M.toInt(), (M - 22).toInt().coerceAtLeast(18))
            g.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            val dom = spec.domain.joinToString(" ∪ ") { "[${fmt(it.from)}, ${fmt(it.to)}]" }
            g.drawString(dom, M.toInt(), (H - M + 40).toInt().coerceAtMost(H - 10))
        } finally {
            g.dispose()
        }

        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    private fun fmt(v: Double): String =
        if (v == Math.floor(v) && abs(v) < 1e7) v.toInt().toString()
        else String.format(java.util.Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')
}
