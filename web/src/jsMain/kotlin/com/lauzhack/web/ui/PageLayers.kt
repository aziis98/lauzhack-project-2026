package com.lauzhack.web.ui

import com.lauzhack.shared.api.DrawingApi
import com.lauzhack.web.markdown.renderMathMarkdown
import react.FC
import react.Props
import react.dom.html.ReactHTML
import react.useEffect
import react.useEffectOnce
import react.useLayoutEffect
import react.useRef
import com.lauzhack.shared.chunking.BoundingBox
import com.lauzhack.shared.dto.DefineTermRequest
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.useState
import web.cssom.ClassName

/*
 * Layers drawn by tldraw's component slots. Slot components get no props, so
 * the open document is shared through these module-level values (set by Canvas).
 */

/** Document currently open in the canvas. */
internal var canvasDocumentId: String? = null

/** Editor of the open canvas (for zoom while dragging hint cards). */
internal var canvasEditor: com.lauzhack.web.tldraw.Editor? = null

/** Current height of the page (it grows with the content). */
internal var canvasPageHeight: Double = 0.0

/** The open document's hint detail level (the slider); hint cards render this level. */
internal var canvasHintLevel: com.lauzhack.shared.hint.HintLevel = com.lauzhack.shared.hint.HintLevel.MEDIUM

private val pageApi = DrawingApi()

/** Size of one square of the paper grid, in page units. */
private const val SQUARE = 24.0

/** Inner margin of the exercise statement from the page edges. */
private const val EXERCISE_MARGIN = 32.0

/** Top of the exercise statement: clear of the floating tool pill when the page opens. */
private const val EXERCISE_TOP = 72.0

/**
 * Everything that lives on the page in page coordinates (pans and zooms with
 * it): the exercise statement at the top and the AI hint cards.
 * Mounted as tldraw's `OnTheCanvas` component.
 */
val PageOverlay = FC<Props> {
    ExerciseStatement()
    HintLayer()
    PlotLayer()
    ErrorLayer()
    if (debugMode) DebugLayer()
}

/**
 * The exercise statement (Markdown + KaTeX) printed at the top of the page.
 * Its words and formulas are clickable: a click asks the agent to clarify the
 * concept (the card appears next to the word). The rest of the box lets
 * pointer events through, so the student can still write around it.
 */
private val ExerciseStatement = FC<Props> {
    var html by useState<String?>(null)
    val bodyRef = useRef<web.html.HTMLDivElement>(null)

    // after each render of the statement, make every word its own clickable span
    useEffect(html) {
        bodyRef.current?.let { wrapWords(it.asDynamic()) }
    }

    useEffectOnce {
        val documentId = canvasDocumentId ?: return@useEffectOnce
        val exercise = runCatching { pageApi.getDocument(documentId)?.exercise }.getOrNull()
        if (!exercise.isNullOrBlank()) html = renderMathMarkdown(exercise)
    }

    val content = html ?: return@FC
    ReactHTML.div {
        className = ClassName("exercise")
        val css: dynamic = js("({})")
        css.left = "${EXERCISE_MARGIN}px"
        css.top = "${EXERCISE_TOP}px"
        css.width = "${PAGE_WIDTH - 2 * EXERCISE_MARGIN}px"
        asDynamic().style = css
        ReactHTML.div { className = ClassName("exercise-label"); +"Exercise" }
        ReactHTML.div {
            className = ClassName("exercise-body")
            ref = bodyRef
            // words and formulas take the tap: stop pointerdown so tldraw does not
            // capture the pointer (or start a stroke), then act on pointerup — iOS
            // Safari's click synthesis is unreliable inside the canvas (see HintLayer)
            onPointerDown = { e -> if (clickTarget(e.target.asDynamic()) != null) e.stopPropagation() }
            onPointerUp = { e ->
                val target = clickTarget(e.target.asDynamic())
                if (target != null) {
                    e.stopPropagation()
                    e.preventDefault()
                    defineClicked(target)
                }
            }
            val inner: dynamic = js("({})")
            inner.__html = content
            asDynamic().dangerouslySetInnerHTML = inner
        }
    }
}

/** The clickable element under a click: a wrapped word or a KaTeX formula. */
private fun clickTarget(target: dynamic): dynamic =
    if (target == null || jsTypeOf(target.closest) != "function") null else target.closest(".ex-word, .katex")

/** Wraps every word of the statement's text (outside formulas) in `<span class="ex-word">`. */
private fun wrapWords(root: dynamic) {
    val doc: dynamic = js("document")
    val walker = doc.createTreeWalker(root, 4) // NodeFilter.SHOW_TEXT
    val nodes = mutableListOf<dynamic>()
    var node = walker.nextNode()
    while (node != null) {
        val parent: dynamic = node.parentElement
        val inside: Boolean = parent == null || parent.closest(".katex, .ex-word") != null
        val text: String = node.textContent as String
        if (!inside && text.isNotBlank()) nodes.add(node)
        node = walker.nextNode()
    }
    fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-'
    for (textNode in nodes) {
        val text = textNode.textContent as String
        val fragment = doc.createDocumentFragment()
        var i = 0
        while (i < text.length) {
            val word = isWordChar(text[i])
            var j = i
            while (j < text.length && isWordChar(text[j]) == word) j++
            val piece = text.substring(i, j)
            if (word) {
                val span = doc.createElement("span")
                span.className = "ex-word"
                span.textContent = piece
                fragment.appendChild(span)
            } else {
                fragment.appendChild(doc.createTextNode(piece))
            }
            i = j
        }
        textNode.parentNode.replaceChild(fragment, textNode)
    }
}

/** LaTeX source of a rendered KaTeX formula. */
private fun texOf(katexEl: dynamic): String =
    (katexEl.querySelector("annotation")?.textContent as? String)?.trim().orEmpty()

/**
 * Sends the clicked word (or formula) with its sentence to the agent, and marks it.
 * The card is anchored to the word's position on the page.
 */
private fun defineClicked(target: dynamic) {
    val documentId = canvasDocumentId ?: return
    val isFormula = (target.classList.contains("katex") as Boolean)
    val term = if (isFormula) texOf(target) else (target.textContent as String).trim()
    if (term.isBlank()) return

    // the sentence/block around it, with formulas as $…$ (KaTeX's HTML is not readable text)
    val block = target.closest("p, li, h1, h2, h3, h4, td") ?: target.closest(".exercise-body")
    val clone = block.cloneNode(true)
    for (k in js("Array.from")(clone.querySelectorAll(".katex")) as Array<dynamic>) {
        k.replaceWith(js("document").createTextNode("\$" + texOf(k) + "\$"))
    }
    val context = (clone.textContent as String).replace(Regex("\\s+"), " ").trim().take(600)

    // page coordinates of the word: the statement box is at (EXERCISE_MARGIN, EXERCISE_TOP)
    val box = target.closest(".exercise").getBoundingClientRect()
    val r = target.getBoundingClientRect()
    val zoom = (box.width as Double) / (PAGE_WIDTH - 2 * EXERCISE_MARGIN)
    val anchor = BoundingBox(
        minX = EXERCISE_MARGIN + ((r.left as Double) - (box.left as Double)) / zoom,
        minY = EXERCISE_TOP + ((r.top as Double) - (box.top as Double)) / zoom,
        maxX = EXERCISE_MARGIN + ((r.right as Double) - (box.left as Double)) / zoom,
        maxY = EXERCISE_TOP + ((r.bottom as Double) - (box.top as Double)) / zoom,
    )

    val previous = js("document").querySelectorAll(".ex-active")
    for (el in js("Array.from")(previous) as Array<dynamic>) el.classList.remove("ex-active")
    target.classList.add("ex-active")

    MainScope().launch {
        runCatching { pageApi.defineTerm(documentId, DefineTermRequest(term, context, anchor)) }
    }
}

/** Props tldraw passes to its `Grid` slot: camera position, zoom and grid size. */
external interface GridProps : Props {
    var x: Double
    var y: Double
    var z: Double
    var size: Double
}

/**
 * Squared paper: a white sheet the width of the page with a 24-unit grid,
 * drawn behind the shapes on a viewport-sized canvas (avoids iPad Safari
 * sub-pixel CSS gradient flicker during pan/zoom).
 */
val PaperGrid = FC<GridProps> { props ->
    val canvasRef = useRef<web.html.HTMLCanvasElement>(null)
    val latest = useRef(props)
    latest.current = props

    fun draw(p: GridProps) {
        val canvas: dynamic = canvasRef.current ?: return
        val vw = (canvas.clientWidth as Number).toDouble()
        val vh = (canvas.clientHeight as Number).toDouble()
        if (vw <= 0.0 || vh <= 0.0) return

        val dpr = (js("window.devicePixelRatio || 1") as Number).toDouble()
        val pw = kotlin.math.round(vw * dpr).toInt()
        val ph = kotlin.math.round(vh * dpr).toInt()
        if ((canvas.width as Int) != pw) canvas.width = pw
        if ((canvas.height as Int) != ph) canvas.height = ph

        val ctx: dynamic = canvas.getContext("2d") ?: return
        ctx.setTransform(dpr, 0.0, 0.0, dpr, 0.0, 0.0)
        ctx.clearRect(0.0, 0.0, vw, vh)

        val z = p.z
        val left = p.x * z
        val top = p.y * z
        val width = PAGE_WIDTH * z
        val height = canvasPageHeight * z
        if (left + width < 0.0 || left > vw || top + height < 0.0 || top > vh) return

        ctx.save()
        ctx.shadowColor = "rgba(0, 0, 0, 0.08)"
        ctx.shadowBlur = 24.0
        ctx.shadowOffsetY = 8.0
        ctx.fillStyle = "#ffffff"
        ctx.fillRect(left, top, width, height)
        ctx.restore()

        val step = SQUARE * z
        if (step < 4.0) return

        ctx.save()
        ctx.beginPath()
        ctx.rect(left, top, width, height)
        ctx.clip()

        ctx.strokeStyle = "rgba(37, 99, 235, 0.12)"
        ctx.lineWidth = 1.0 / dpr
        val halfPx = 0.5 / dpr
        val visLeft = kotlin.math.max(0.0, left)
        val visRight = kotlin.math.min(vw, left + width)
        val visTop = kotlin.math.max(0.0, top)
        val visBottom = kotlin.math.min(vh, top + height)

        val startCol = kotlin.math.ceil((visLeft - left) / step).toInt().coerceAtLeast(1)
        val endCol = kotlin.math.floor((visRight - left) / step).toInt()
        val startRow = kotlin.math.ceil((visTop - top) / step).toInt().coerceAtLeast(1)
        val endRow = kotlin.math.floor((visBottom - top) / step).toInt()

        ctx.beginPath()
        for (col in startCol..endCol) {
            val gx = kotlin.math.round((left + col * step) * dpr) / dpr + halfPx
            ctx.moveTo(gx, visTop)
            ctx.lineTo(gx, visBottom)
        }
        for (row in startRow..endRow) {
            val gy = kotlin.math.round((top + row * step) * dpr) / dpr + halfPx
            ctx.moveTo(visLeft, gy)
            ctx.lineTo(visRight, gy)
        }
        ctx.stroke()
        ctx.restore()
    }

    useLayoutEffect(props.x, props.y, props.z, canvasPageHeight) {
        draw(props)
    }

    useEffectOnce {
        val el: dynamic = canvasRef.current ?: return@useEffectOnce
        // re-draw with latest camera props when viewport resizes
        val win: dynamic = js("window")
        val onResize: () -> Unit = { latest.current?.let { draw(it) } }
        val ro = js("new ResizeObserver(onResize)")
        ro.observe(el)
        win.addEventListener("resize", onResize)
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            ro.disconnect()
            win.removeEventListener("resize", onResize)
        }
    }

    ReactHTML.canvas {
        ref = canvasRef
        className = ClassName("paper-canvas")
    }
}
