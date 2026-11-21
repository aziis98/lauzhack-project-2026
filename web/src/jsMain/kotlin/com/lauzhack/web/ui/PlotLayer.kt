package com.lauzhack.web.ui

import com.lauzhack.shared.agent.AgentAction
import com.lauzhack.shared.agent.PlotInterval
import com.lauzhack.shared.agent.PlotWithAnchor
import com.lauzhack.shared.api.DrawingApi
import kotlinx.coroutines.delay
import kotlin.math.pow
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML
import react.useState
import react.useEffectOnce
import web.cssom.ClassName

private val plotApi = DrawingApi()

private const val POLL_MILLIS = 1500L
internal const val PLOT_PANEL_WIDTH = 340.0
internal const val PLOT_PANEL_HEIGHT = 268.0
private const val BTN_GAP = 12.0

/**
 * The plot actions of the page agent: for each active plot a popup button
 * appears anchored near the chunk that defined the function; clicking it opens
 * a draggable SVG panel where KOTLIN samples the JS expression over the domain
 * and draws the curve, marking the points the STUDENT computed on the page
 * (never points invented by the model or computed by us).
 */
val PlotLayer = FC<Props> {
    var plots by useState<List<PlotWithAnchor>>(emptyList())
    // plots the user closed with the ✕ on the button: hidden until the agent
    // re-emits them with new content (then they are new information)
    var suppressed by useState<Map<String, String>>(emptyMap())

    useEffectOnce {
        val documentId = canvasDocumentId ?: return@useEffectOnce
        var last: List<PlotWithAnchor>? = null
        while (true) {
            runCatching { plotApi.getPlots(documentId) }
                .onSuccess { if (it != last) { last = it; plots = it } }
            delay(POLL_MILLIS)
        }
    }

    val visible = plots.filter { suppressed[it.plot.id] != it.plot.toString() }

    for (entry in visible) {
        PlotEntry {
            key = Key(entry.plot.id)
            this.entry = entry
            this.onHide = { suppressed = suppressed + (entry.plot.id to entry.plot.toString()) }
        }
    }
}

private external interface PlotEntryProps : Props {
    var entry: PlotWithAnchor
    var onHide: () -> Unit
}

private val PlotEntry = FC<PlotEntryProps> { props ->
    val plot = props.entry.plot
    var open by useState(false)
    var dragged by useState<Pair<Double, Double>?>(null)

    val anchor = props.entry.anchor
    // right of the defining chunk, or a fallback spot under the exercise statement
    val btnLeft = if (anchor != null) anchor.maxX + BTN_GAP else 64.0
    val btnTop = if (anchor != null) anchor.minY else 160.0
    val (panelLeft, panelTop) = dragged ?: (btnLeft to btnTop + 56.0)

    val fnName = plot.functionName ?: "f"
    val is3D = plot.variableY != null
    val panelWidth = if (is3D) PLOT3D_CANVAS_WIDTH + 28.0 else PLOT_PANEL_WIDTH

    ReactHTML.button {
        className = ClassName("plot-btn")
        val css: dynamic = js("({})")
        css.left = "${btnLeft}px"
        css.top = "${btnTop}px"
        asDynamic().style = css
        // never let the canvas see this pointer: tldraw captures the pointer on
        // canvas pointerdown (useCanvasEvents), which would steal the pointerup
        // and the click would never complete
        onPointerDown = { it.stopPropagation() }
        onClick = { open = !open }
        ReactHTML.div {
            className = ClassName("plot-btn-top")
            ReactHTML.span { className = ClassName("ph"); +"ph-chart-line-up" }
            +"Plot"
        }
        ReactHTML.span {
            className = ClassName("plot-btn-label")
            +"$fnName(${plot.variable}${plot.variableY?.let { ", $it" } ?: ""})"
        }
        ReactHTML.span {
            className = ClassName("plot-btn-close")
            title = "Hide this plot"
            asDynamic()["aria-label"] = "Hide plot button"
            onPointerDown = { it.stopPropagation() }
            onClick = {
                it.stopPropagation() // don't toggle the panel open
                props.onHide()
            }
            +"✕"
        }
    }

    if (open) {
        ReactHTML.div {
            className = ClassName("plot-card")
            val css: dynamic = js("({})")
            css.left = "${panelLeft}px"
            css.top = "${panelTop}px"
            css.width = "${panelWidth}px"
            asDynamic().style = css

            // drag the panel like a hint card; its buttons and the 3D canvas
            // (OrbitControls owns pointer gestures there) are left alone
            onPointerDown = { event ->
                event.stopPropagation() // never start a stroke under the panel
                val target: dynamic = event.target
                if (target.closest("button, canvas") == null) {
                    event.preventDefault()
                    val zoom = canvasEditor?.getZoomLevel() ?: 1.0
                    val startX = event.clientX
                    val startY = event.clientY
                    var pos = panelLeft to panelTop
                    val win: dynamic = js("window")
                    lateinit var onMove: (dynamic) -> Unit
                    lateinit var onUp: (dynamic) -> Unit
                    onMove = { e ->
                        pos = (panelLeft + (e.clientX as Double - startX) / zoom) to
                            (panelTop + (e.clientY as Double - startY) / zoom)
                        dragged = pos
                    }
                    onUp = { _ ->
                        win.removeEventListener("pointermove", onMove)
                        win.removeEventListener("pointerup", onUp)
                    }
                    win.addEventListener("pointermove", onMove)
                    win.addEventListener("pointerup", onUp)
                }
            }

            ReactHTML.div {
                className = ClassName("plot-head")
                ReactHTML.span { className = ClassName("ph"); +"ph-chart-line-up" }
                ReactHTML.span {
                    className = ClassName("plot-expr")
                    +"$fnName(${plot.variable}${plot.variableY?.let { ", $it" } ?: ""}) = ${plot.expression}"
                }
                ReactHTML.button {
                    className = ClassName("hint-close")
                    title = "Close"
                    asDynamic()["aria-label"] = "Close plot"
                    // keep the canvas from capturing the pointer (see .plot-btn)
                    onPointerDown = { it.stopPropagation() }
                    onClick = { open = false }
                    +"✕"
                }
            }

            if (is3D) {
                // content key: the 3D scene remounts (and rebuilds) whenever the
                // action's content changes — new points, new domain, new expression
                Plot3D {
                    key = Key(
                        plot.id + "|" + plot.expression + "|" +
                            plot.domain.toString() + "|" + (plot.domainY?.toString() ?: "") + "|" +
                            plot.points.toString() + "|" + plot.studentDomain.toString()
                    )
                    this.plot = plot
                }
            } else {
                PlotSvg { this.plot = plot }
            }
        }
    }
}

// ---------------------------------------------------------------
// The SVG: Kotlin computes the geometry AND builds the markup
// ---------------------------------------------------------------

private external interface PlotSvgProps : Props {
    var plot: AgentAction.PlotAction
}

private val PlotSvg = FC<PlotSvgProps> { props ->
    val svg = buildPlotSvg(props.plot, width = PLOT_PANEL_WIDTH - 24.0, height = PLOT_PANEL_HEIGHT)
    if (svg == null) {
        ReactHTML.p { className = ClassName("plot-error"); +"Cannot plot this expression" }
        return@FC
    }
    ReactHTML.div {
        className = ClassName("plot-svg")
        val inner: dynamic = js("({})")
        inner.__html = svg
        asDynamic().dangerouslySetInnerHTML = inner
    }
}

// ---------------------------------------------------------------
// Geometry + SVG markup (pure Kotlin string building)
// ---------------------------------------------------------------

internal class Tick(val value: Double, val screen: Double)

/** Builds the whole `<svg>…</svg>` for the plot; null when the expression is unusable. */
internal fun buildPlotSvg(plot: AgentAction.PlotAction, width: Double, height: Double): String? {
    val f = compileExpression(plot.expression, plot.variable) ?: return null
    val intervals = plot.domain.filter { it.to > it.from }
        .ifEmpty { listOf(PlotInterval(-5.0, 5.0)) }
    val x0 = intervals.minOf { it.from }
    val x1 = intervals.maxOf { it.to }

    // the number-line diagram under the axis (the student's written domain)
    val hasDiagram = plot.studentDomain.isNotEmpty()
    val diagramTop = height - 32.0
    val chartHeight = if (hasDiagram) diagramTop - 4.0 else height

    val padL = 36.0
    val padR = 10.0
    val padT = 8.0
    val padB = 22.0

    // sample each interval, breaking the curve at non-finite values
    val samples = 400
    data class Pt(val x: Double, val y: Double)
    val curves = mutableListOf<List<Pt>>()
    for (iv in intervals) {
        var cur = mutableListOf<Pt>()
        for (i in 0..samples) {
            val x = iv.from + (iv.to - iv.from) * i / samples
            val y = f(x)
            if (y != null && y.isFinite()) cur.add(Pt(x, y)) else {
                if (cur.size > 1) curves.add(cur)
                cur = mutableListOf()
            }
        }
        if (cur.size > 1) curves.add(cur)
    }
    if (curves.isEmpty()) return null

    // y-range from finite samples, percentile-clamped against asymptotes
    val sorted = curves.flatten().map { it.y }.sorted()
    var y0 = sorted[(sorted.size * 0.02).toInt().coerceIn(sorted.indices)]
    var y1 = sorted[((sorted.size * 0.98).toInt()).coerceIn(sorted.indices)]
    if (y1 - y0 < 1e-9) { y0 -= 1.0; y1 += 1.0 }
    val padY = (y1 - y0) * 0.08
    y0 -= padY
    y1 += padY

    fun sx(x: Double) = padL + (x - x0) / (x1 - x0) * (width - padL - padR)
    fun sy(y: Double) = chartHeight - padB - (y - y0) / (y1 - y0) * (chartHeight - padT - padB)
    fun syClamped(y: Double) = sy(y).coerceIn(0.0, chartHeight)

    val sb = StringBuilder()
    sb.append("<svg width=\"$width\" height=\"$height\" viewBox=\"0 0 $width $height\" xmlns=\"http://www.w3.org/2000/svg\">")

    // grid + tick labels
    for (t in niceTicks(x0, x1, 6) { sx(it) }) {
        sb.append("<line x1=\"${fmt(t.screen)}\" y1=\"0\" x2=\"${fmt(t.screen)}\" y2=\"${fmt(chartHeight)}\" stroke=\"#e7e2d8\" stroke-width=\"1\"/>")
        sb.append("<text x=\"${fmt(t.screen - 8)}\" y=\"${fmt(chartHeight - 6)}\" fill=\"#555\" font-size=\"9.5\">${fmt(t.value)}</text>")
    }
    for (t in niceTicks(y0, y1, 4) { sy(it) }) {
        sb.append("<line x1=\"0\" y1=\"${fmt(t.screen)}\" x2=\"${fmt(width)}\" y2=\"${fmt(t.screen)}\" stroke=\"#e7e2d8\" stroke-width=\"1\"/>")
        sb.append("<text x=\"2\" y=\"${fmt(t.screen + 3)}\" fill=\"#555\" font-size=\"9.5\">${fmt(t.value)}</text>")
    }

    // axes on zero when visible, else on the frame
    val axisY = if (y0 <= 0.0 && y1 >= 0.0) sy(0.0) else chartHeight - padB
    val axisX = if (x0 <= 0.0 && x1 >= 0.0) sx(0.0) else padL
    sb.append("<line x1=\"0\" y1=\"${fmt(axisY)}\" x2=\"${fmt(width)}\" y2=\"${fmt(axisY)}\" stroke=\"#55504a\" stroke-width=\"1.2\"/>")
    sb.append("<line x1=\"${fmt(axisX)}\" y1=\"0\" x2=\"${fmt(axisX)}\" y2=\"${fmt(chartHeight)}\" stroke=\"#55504a\" stroke-width=\"1.2\"/>")

    // the curve
    sb.append("<path d=\"")
    for (curve in curves) {
        curve.forEachIndexed { i, p ->
            if (i == 0) sb.append("M ${fmt(sx(p.x))} ${fmt(syClamped(p.y))}")
            else sb.append(" L ${fmt(sx(p.x))} ${fmt(syClamped(p.y))}")
        }
    }
    sb.append("\" fill=\"none\" stroke=\"#1a5fb4\" stroke-width=\"2.2\" stroke-linejoin=\"round\" stroke-linecap=\"round\"/>")

    // the points the STUDENT computed; y evaluated only when they wrote just the x
    for (p in plot.points) {
        if (p.x < x0 || p.x > x1) continue
        val y = p.y ?: f(p.x) ?: continue
        if (y < y0 || y > y1) continue
        val cx = fmt(sx(p.x))
        val cy = fmt(sy(y))
        sb.append("<circle cx=\"$cx\" cy=\"$cy\" r=\"4\" fill=\"#c62828\"/>")
        val label = "(${fmt(p.x)}, ${fmt(y)})${p.label?.let { " $it" } ?: ""}"
        sb.append("<text x=\"${fmt(sx(p.x) + 6)}\" y=\"${fmt(sy(y) - 6)}\" fill=\"#c62828\" font-size=\"10\" font-weight=\"700\">")
        sb.append(label.xmlEscape())
        sb.append("</text>")
    }

    // the number-line diagram: the domain as the student wrote it
    if (hasDiagram) {
        val lineY = diagramTop + 16.0
        sb.append("<line x1=\"0\" y1=\"${fmt(lineY)}\" x2=\"${fmt(width)}\" y2=\"${fmt(lineY)}\" stroke=\"#55504a\" stroke-width=\"1.1\"/>")
        for (d in plot.studentDomain) {
            val fromX = d.from?.let { sx(it) }?.coerceIn(padL * 0.4, width - padR) ?: padL * 0.4
            val toX = d.to?.let { sx(it) }?.coerceIn(padL * 0.4, width - padR) ?: width - padR
            // included span
            sb.append("<rect x=\"${fmt(fromX)}\" y=\"${fmt(lineY - 5)}\" width=\"${fmt((toX - fromX).coerceAtLeast(0.0))}\" height=\"10\" fill=\"#dcd0ff\" fill-opacity=\"0.85\" stroke=\"#1a1a1a\" stroke-width=\"1\"/>")
            // boundaries: filled circle = closed end, empty circle = strict inequality
            if (d.from != null) {
                sb.append(
                    if (d.fromClosed) "<circle cx=\"${fmt(fromX)}\" cy=\"${fmt(lineY)}\" r=\"4\" fill=\"#1a1a1a\"/>"
                    else "<circle cx=\"${fmt(fromX)}\" cy=\"${fmt(lineY)}\" r=\"4\" fill=\"${'#'}fff8ec\" stroke=\"#1a1a1a\" stroke-width=\"1.5\"/>"
                )
            } else {
                sb.append("<text x=\"4\" y=\"${fmt(lineY + 3)}\" fill=\"#1a1a1a\" font-size=\"11\" font-weight=\"700\">∞</text>")
            }
            if (d.to != null) {
                sb.append(
                    if (d.toClosed) "<circle cx=\"${fmt(toX)}\" cy=\"${fmt(lineY)}\" r=\"4\" fill=\"#1a1a1a\"/>"
                    else "<circle cx=\"${fmt(toX)}\" cy=\"${fmt(lineY)}\" r=\"4\" fill=\"${'#'}fff8ec\" stroke=\"#1a1a1a\" stroke-width=\"1.5\"/>"
                )
            } else {
                sb.append("<text x=\"${fmt(width - 14)}\" y=\"${fmt(lineY + 3)}\" fill=\"#1a1a1a\" font-size=\"11\" font-weight=\"700\">∞</text>")
            }
        }
    }

    sb.append("</svg>")
    return sb.toString()
}

private fun String.xmlEscape(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** 1-2-5 nice ticks across `[lo, hi]`. */
private fun niceTicks(lo: Double, hi: Double, count: Int, screen: (Double) -> Double): List<Tick> {
    val raw = (hi - lo) / count
    val mag = 10.0.pow(kotlin.math.floor(kotlin.math.log10(raw)))
    val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * mag }.firstOrNull { it >= raw } ?: mag * 10
    val first = kotlin.math.ceil(lo / step) * step
    val out = ArrayList<Tick>()
    var v = first
    while (v <= hi + 1e-9) {
        out.add(Tick(v, screen(v)))
        v += step
    }
    return out
}

private fun fmt(v: Double): String {
    if (kotlin.math.abs(v) < 1e-10) return "0"
    if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e9) return v.toInt().toString()
    return fmt3(v)
}

private fun fmt3(v: Double): String = js("Number(v.toPrecision(3)).toString()")

// ---------------------------------------------------------------
// Evaluation: the model's JS expression, computed from Kotlin
// ---------------------------------------------------------------

/** Compiles the JS expression to a function of the variable; null when unsafe. */
internal fun compileExpression(expression: String, variable: String): ((Double) -> Double?)? {
    if (!expressionIsSafe(expression, variable)) return null
    val f: dynamic = makeJsFunction(variable, expression)
    return { x -> (f(x) as? Number)?.toDouble() }
}

private fun makeJsFunction(variable: String, expr: String): dynamic =
    js("new Function(variable, 'return (' + expr + ');')")

private val KNOWN_MATH = setOf(
    "sqrt", "abs", "exp", "log", "sin", "cos", "tan", "asin", "acos", "atan",
    "pow", "min", "max", "floor", "ceil", "round", "sign", "PI", "E",
)

/** Whitelist: Math.<known> tokens, the declared variables, numbers and arithmetic only. */
internal fun expressionIsSafe(expr: String, vararg variables: String): Boolean {
    if (expr.isEmpty() || expr.length > 300) return false
    Regex("""Math\.([A-Za-z]+)""").findAll(expr).forEach {
        if (it.groupValues[1] !in KNOWN_MATH) return false
    }
    val withoutMath = expr.replace(Regex("""Math\.[A-Za-z]+"""), "")
    var withoutVars = withoutMath
    for (v in variables) {
        withoutVars = withoutVars.replace(Regex("""\b${Regex.escape(v)}\b"""), "")
    }
    if (Regex("""[A-Za-z_$]""").containsMatchIn(withoutVars)) return false
    if (Regex("""[;={}[\]`'"<>?!&|:,]""").containsMatchIn(withoutVars)) return false
    return true
}
