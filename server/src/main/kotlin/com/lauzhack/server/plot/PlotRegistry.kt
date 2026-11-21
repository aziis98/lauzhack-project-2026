package com.lauzhack.server.plot

import com.lauzhack.shared.plot.Highlight
import com.lauzhack.shared.plot.HighlightKind
import com.lauzhack.shared.plot.Interval
import com.lauzhack.shared.plot.MathExpression
import com.lauzhack.shared.plot.PlotSpec
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sign

/**
 * Holds the current plots of each document; each agent evaluation REPLACES the
 * document's plot set, so plots follow what is currently on the page.
 */
class PlotRegistry {
    private val docs = ConcurrentHashMap<String, List<PlotSpec>>()

    fun replaceAll(documentId: String, specs: List<PlotSpec>) {
        docs[documentId] = specs
    }

    fun snapshot(documentId: String): List<PlotSpec> = docs[documentId] ?: emptyList()

    fun plot(documentId: String, id: String): PlotSpec? = snapshot(documentId).find { it.id == id }

    companion object {
        /** Stable plot id from the expression it renders. */
        fun idOf(expression: String, variable: String): String {
            val md = MessageDigest.getInstance("MD5").digest("$variable|$expression".toByteArray())
            return "plot:" + md.joinToString("") { "%02x".format(it) }.take(12)
        }
    }
}

/**
 * Builds the deterministic [PlotSpec] from a validated expression: parse the safe
 * DSL (a plot that cannot be parsed is never rendered), then find roots and local
 * extrema numerically. No model is involved past choosing the expression.
 */
fun buildPlotSpec(
    documentId: String,
    functionName: String?,
    variable: String,
    expression: String,
    domain: List<Interval>,
): PlotSpec {
    val parsed = MathExpression.parse(expression, variable) // throws on unsafe DSL: the caller drops the action
    val clean = domain
        .map { Interval(minOf(it.from, it.to), maxOf(it.from, it.to)) }
        .filter { it.to > it.from }
        .ifEmpty { listOf(Interval(-5.0, 5.0)) }
    return PlotSpec(
        id = PlotRegistry.idOf(expression, variable),
        documentId = documentId,
        functionName = functionName,
        expression = expression,
        variable = variable,
        domain = clean,
        highlights = computeHighlights(parsed, clean),
    )
}

/** Roots and local extrema found by sampling — no symbolic algebra, purely numeric. */
internal fun computeHighlights(
    parsed: MathExpression,
    domain: List<Interval>,
    samples: Int = 1200,
): List<Highlight> {
    data class Sample(val x: Double, val y: Double, val d: Double)

    fun f(x: Double): Double = parsed.evaluate(x)
    fun d(x: Double): Double {
        val h = 1e-6 * maxOf(1.0, abs(x))
        return (f(x + h) - f(x - h)) / (2 * h)
    }

    val out = ArrayList<Highlight>()
    for (interval in domain) {
        val n = maxOf(16, (samples / domain.size).coerceAtLeast(16))
        val dx = (interval.to - interval.from) / n
        val xs = ArrayList<Sample>(n + 1)
        for (i in 0..n) {
            val x = interval.from + i * dx
            val y = f(x)
            if (y.isFinite()) {
                val dv = d(x)
                if (dv.isFinite()) xs.add(Sample(x, y, dv))
            }
        }
        for (i in 1 until xs.size) {
            val a = xs[i - 1]
            val b = xs[i]
            if (a.y == 0.0 || (a.y * b.y < 0 && a.y.sign != b.y.sign)) {
                val x = refineSignChange(::f, a.x, b.x)
                addUnique(out, Highlight(x, 0.0, HighlightKind.ROOT, "root"), dx)
            }
            if (a.d * b.d < 0 && a.d.sign != b.d.sign) {
                val x = refineSignChange(::d, a.x, b.x)
                val isMax = d(x - (b.x - a.x) / 4) > 0
                addUnique(
                    out,
                    Highlight(x, f(x), HighlightKind.CRITICAL, if (isMax) "max" else "min"),
                    dx,
                )
            }
        }
    }
    return out
}

/** Bisection on a sign change of [g] inside `[lo, hi]`. */
private fun refineSignChange(g: (Double) -> Double, lo: Double, hi: Double): Double {
    var a = lo
    var b = hi
    var fa = g(a)
    for (i in 0 until 40) {
        val m = (a + b) / 2
        val fm = g(m)
        if (fa * fm <= 0) b = m else { a = m; fa = fm }
        if (b - a < 1e-12 * maxOf(1.0, abs(m))) break
    }
    return (a + b) / 2
}

private fun addUnique(out: MutableList<Highlight>, h: Highlight, tolerance: Double) {
    if (out.size >= 12) return
    if (out.none { abs(it.x - h.x) < tolerance * 4 }) out.add(h)
}
