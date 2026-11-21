package com.lauzhack.web.ui

import com.lauzhack.shared.agent.ErrorMark
import com.lauzhack.shared.api.DrawingApi
import kotlinx.coroutines.delay
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

private val errorApi = DrawingApi()

private const val POLL_MILLIS = 1500L
private const val CARD_WIDTH = 280.0
private const val GAP = 12.0
private const val HALO = 5.0

/**
 * The page agent's error marks: for each mistake a red box is drawn over the
 * flagged symbol (in page coordinates) with a small badge that opens a popup
 * describing the error. Mounted as tldraw's `OnTheCanvas` component, so the
 * boxes and cards pan and zoom with the page.
 *
 * A mark the student closes with the ✕ stays hidden until the agent re-emits it
 * with changed content (the same suppression behaviour as the plot buttons).
 */
val ErrorLayer = FC<Props> {
    var errors by useState<List<ErrorMark>>(emptyList())
    // error id -> full content of the dismissed mark: hidden until it changes
    var suppressed by useState<Map<String, String>>(emptyMap())

    useEffectOnce {
        val documentId = canvasDocumentId ?: return@useEffectOnce
        var last: List<ErrorMark>? = null
        while (true) {
            runCatching { errorApi.getErrors(documentId) }
                .onSuccess { if (it != last) { last = it; errors = it } }
            delay(POLL_MILLIS)
        }
    }

    val visible = errors.filter { suppressed[it.id] != it.toString() }

    for (mark in visible) {
        ErrorMarkView {
            key = Key(mark.id)
            this.mark = mark
            this.onHide = { suppressed = suppressed + (mark.id to mark.toString()) }
        }
    }
}

private external interface ErrorMarkProps : Props {
    var mark: ErrorMark
    var onHide: () -> Unit
}

private val ErrorMarkView = FC<ErrorMarkProps> { props ->
    val mark = props.mark
    val b = mark.bounds
    var open by useState(false)
    var dragged by useState<Pair<Double, Double>?>(null)

    // badge right of the flagged symbol; card below it, or where the student dragged it
    val btnLeft = b.maxX + GAP
    val btnTop = b.minY
    val (cardLeft, cardTop) = dragged ?: (btnLeft to btnTop + 40.0)

    // red overlay on the flagged symbol (never steals pointer events)
    ReactHTML.div {
        className = ClassName("error-box")
        val css: dynamic = js("({})")
        css.left = "${b.minX - HALO}px"
        css.top = "${b.minY - HALO}px"
        css.width = "${b.width + 2 * HALO}px"
        css.height = "${b.height + 2 * HALO}px"
        asDynamic().style = css
    }

    ReactHTML.button {
        className = ClassName(if (open) "error-btn open" else "error-btn")
        val css: dynamic = js("({})")
        css.left = "${btnLeft}px"
        css.top = "${btnTop}px"
        asDynamic().style = css
        title = "Show the mistake"
        // never let the canvas capture the pointer: tldraw's useCanvasEvents
        // grabs it on canvas pointerdown and the click would never complete
        onPointerDown = { it.stopPropagation() }
        onClick = { open = !open }
        ReactHTML.span { className = ClassName("ph"); +"ph-warning-circle" }
        +"Error"
        ReactHTML.span {
            className = ClassName("error-btn-close")
            title = "Hide this mark"
            asDynamic()["aria-label"] = "Hide error mark"
            onPointerDown = { it.stopPropagation() }
            onClick = {
                it.stopPropagation() // don't toggle the popup open
                props.onHide()
            }
            +"✕"
        }
    }

    if (open) {
        ReactHTML.div {
            className = ClassName("error-card")
            val css: dynamic = js("({})")
            css.left = "${cardLeft}px"
            css.top = "${cardTop}px"
            css.width = "${CARD_WIDTH}px"
            asDynamic().style = css

            // drag the card like a hint/plot card; its buttons are left alone
            onPointerDown = { event ->
                event.stopPropagation() // never start a stroke under the card
                val target: dynamic = event.target
                if (target.closest("button") == null) {
                    event.preventDefault()
                    val zoom = canvasEditor?.getZoomLevel() ?: 1.0
                    val startX = event.clientX
                    val startY = event.clientY
                    var pos = cardLeft to cardTop
                    val win: dynamic = js("window")
                    lateinit var onMove: (dynamic) -> Unit
                    lateinit var onUp: (dynamic) -> Unit
                    onMove = { e ->
                        pos = (cardLeft + (e.clientX as Double - startX) / zoom) to
                            (cardTop + (e.clientY as Double - startY) / zoom)
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
                className = ClassName("error-head")
                ReactHTML.span { className = ClassName("ph"); +"ph-warning-circle" }
                ReactHTML.span {
                    className = ClassName("error-title")
                    val title = "Check this" + (mark.symbol?.let { ": $it" } ?: "")
                    +title
                }
                ReactHTML.button {
                    className = ClassName("error-close")
                    title = "Close"
                    asDynamic()["aria-label"] = "Close error"
                    onPointerDown = { it.stopPropagation() }
                    onClick = { open = false }
                    +"✕"
                }
            }

            ReactHTML.p { className = ClassName("error-message"); +mark.message }
            mark.suggestion?.let {
                ReactHTML.p { className = ClassName("error-suggestion"); +"Hint: $it" }
            }
        }
    }
}
