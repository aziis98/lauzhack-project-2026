package com.lauzhack.web.ui

import com.lauzhack.shared.api.DrawingApi
import com.lauzhack.web.markdown.renderMathMarkdown
import react.FC
import react.Props
import react.dom.html.ReactHTML
import react.useEffect
import react.useEffectOnce
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
            // words and formulas take the click; keep it from starting a stroke
            onPointerDown = { e -> if (clickTarget(e.target.asDynamic()) != null) e.stopPropagation() }
            onClick = { e ->
                val target = clickTarget(e.target.asDynamic())
                if (target != null) {
                    e.stopPropagation()
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
 * drawn behind the shapes. Mounted as tldraw's `Grid` component (shown in grid mode).
 */
val PaperGrid = FC<GridProps> { props ->
    val z = props.z
    val square = SQUARE * z
    ReactHTML.div {
        className = ClassName("paper")
        val css: dynamic = js("({})")
        css.left = "${props.x * z}px"
        css.top = "${props.y * z}px"
        css.width = "${PAGE_WIDTH * z}px"
        css.height = "${canvasPageHeight * z}px"
        css.backgroundSize = "${square}px ${square}px"
        asDynamic().style = css
    }
}
