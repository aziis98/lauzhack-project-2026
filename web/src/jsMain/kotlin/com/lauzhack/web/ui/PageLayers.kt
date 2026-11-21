package com.lauzhack.web.ui

import com.lauzhack.shared.api.DrawingApi
import com.lauzhack.web.markdown.renderMathMarkdown
import react.FC
import react.Props
import react.dom.html.ReactHTML
import react.useEffectOnce
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
}

/** The exercise statement (Markdown + KaTeX) printed at the top of the page. */
private val ExerciseStatement = FC<Props> {
    var html by useState<String?>(null)

    useEffectOnce {
        val documentId = canvasDocumentId ?: return@useEffectOnce
        val exercise = runCatching { pageApi.getDocument(documentId).exercise }.getOrNull()
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
            val inner: dynamic = js("({})")
            inner.__html = content
            asDynamic().dangerouslySetInnerHTML = inner
        }
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
