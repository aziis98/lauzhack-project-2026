package com.lauzhack.web.ui

import com.lauzhack.shared.api.DrawingApi
import com.lauzhack.shared.hint.Hint
import com.lauzhack.shared.hint.HintStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.MainScope
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

private val hintApi = DrawingApi()


private const val POLL_MILLIS = 1500L
private const val CARD_WIDTH = 280.0
private const val CARD_GAP = 16.0

/**
 * Hint cards (circled "?" or the toolbar Help button). Mounted as tldraw's
 * `OnTheCanvas` component, so they live in page coordinates and pan/zoom with
 * the page. They sit above every stroke (see `.hint-card` z-index) and can be
 * dragged; the position is saved on the server (UI only, never sent to the AI).
 */
val HintLayer = FC<Props> {
    var hints by useState<List<Hint>>(emptyList())

    // Polls the server; the effect's coroutine is cancelled when the canvas unmounts.
    useEffectOnce {
        val documentId = canvasDocumentId ?: return@useEffectOnce
        var last: List<Hint>? = null
        while (true) {
            runCatching { hintApi.getHints(documentId) }
                .onSuccess { if (it != last) { last = it; hints = it } }
            delay(POLL_MILLIS)
        }
    }

    fun act(block: suspend (String) -> Unit) {
        canvasDocumentId?.let { doc -> MainScope().launch { runCatching { block(doc) } } }
    }

    for (hint in hints) {
        HintCardView {
            key = Key(hint.id)
            this.hint = hint
            onDismiss = {
                hints = hints.filter { it.id != hint.id }
                act { doc -> hintApi.dismissHint(doc, hint.id) }
            }
            onMore = {
                hints = hints.map { if (it.id == hint.id) it.copy(status = HintStatus.THINKING) else it }
                act { doc -> hintApi.moreHint(doc, hint.id) }
            }
            onMoved = { x, y -> act { doc -> hintApi.moveHint(doc, hint.id, x, y) } }
        }
    }
}

private external interface HintCardProps : Props {
    var hint: Hint
    var onDismiss: () -> Unit
    var onMore: () -> Unit
    var onMoved: (Double, Double) -> Unit
}

private val HintCardView = FC<HintCardProps> { props ->
    val hint = props.hint
    val anchor = hint.anchor
    // Default: right of the "?", or left of it when there is no room before the page edge.
    val defaultLeft = if (anchor.maxX + CARD_GAP + CARD_WIDTH <= PAGE_WIDTH) anchor.maxX + CARD_GAP
    else maxOf(0.0, anchor.minX - CARD_GAP - CARD_WIDTH)
    // Position while/after dragging, until the server echoes it back.
    var dragged by useState<Pair<Double, Double>?>(null)
    val (left, top) = dragged ?: hint.position?.let { it.x to it.y } ?: (defaultLeft to anchor.minY)

    ReactHTML.div {
        className = ClassName("hint-card hint-${hint.status.name.lowercase()}")
        val css: dynamic = js("({})")
        css.left = "${left}px"
        css.top = "${top}px"
        css.width = "${CARD_WIDTH}px"
        asDynamic().style = css

        // Drag the card like a shape; clicks on its buttons are left alone.
        onPointerDown = { event ->
            event.stopPropagation() // never start a stroke under the card
            val target: dynamic = event.target
            if (target.closest("button") == null) {
                event.preventDefault()
                val zoom = canvasEditor?.getZoomLevel() ?: 1.0
                val startX = event.clientX
                val startY = event.clientY
                var pos = left to top
                val win: dynamic = js("window")
                lateinit var onMove: (dynamic) -> Unit
                lateinit var onUp: (dynamic) -> Unit
                onMove = { e ->
                    pos = (left + (e.clientX as Double - startX) / zoom) to (top + (e.clientY as Double - startY) / zoom)
                    dragged = pos
                }
                onUp = { _ ->
                    win.removeEventListener("pointermove", onMove)
                    win.removeEventListener("pointerup", onUp)
                    if (pos != (left to top)) props.onMoved(pos.first, pos.second)
                }
                win.addEventListener("pointermove", onMove)
                win.addEventListener("pointerup", onUp)
            }
        }

        ReactHTML.div {
            className = ClassName("hint-head")
            ReactHTML.span { className = ClassName("ph"); +"ph-lightbulb" }
            ReactHTML.span {
                className = ClassName("hint-title")
                +when (hint.status) {
                    HintStatus.THINKING -> "Thinking…"
                    HintStatus.READY -> hint.card?.title ?: "Hint"
                    HintStatus.FAILED -> "Oops"
                }
            }
            ReactHTML.button {
                className = ClassName("hint-close")
                title = "Close"
                asDynamic()["aria-label"] = "Close hint"
                // keep the canvas from capturing the pointer: tldraw's
                // useCanvasEvents captures on canvas pointerdown, which would
                // steal the pointerup and the click would never complete
                onPointerDown = { it.stopPropagation() }
                onClick = { props.onDismiss() }
                +"✕"
            }
        }

        when (hint.status) {
            HintStatus.THINKING -> ReactHTML.div { className = ClassName("hint-dots"); +"• • •" }
            HintStatus.FAILED -> ReactHTML.p { className = ClassName("hint-message"); +(hint.error ?: "Error") }
            HintStatus.READY -> hint.card?.let { card ->
                ReactHTML.p { className = ClassName("hint-message"); +card.message }
                if (card.steps.isNotEmpty()) {
                    ReactHTML.ul {
                        className = ClassName("hint-steps")
                        for (step in card.steps) ReactHTML.li { +step }
                    }
                }
            }
        }

        if (hint.status != HintStatus.THINKING) {
            ReactHTML.button {
                className = ClassName("hint-more")
                // keep the canvas from capturing the pointer (see the close button)
                onPointerDown = { it.stopPropagation() }
                onClick = { props.onMore() }
                ReactHTML.span { className = ClassName("ph"); +"ph-question" }
                +(if (hint.status == HintStatus.FAILED) "Try again" else "I don't get it — another hint")
            }
        }
    }
}
