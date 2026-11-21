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
 * Hint cards for the circled "?" on the page. Mounted as tldraw's `OnTheCanvas`
 * component, so it lives in page coordinates: cards sit next to the "?" that
 * asked for them and pan/zoom with the page.
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

    for (hint in hints) {
        HintCardView {
            key = Key(hint.id)
            this.hint = hint
            onDismiss = {
                hints = hints.filter { it.id != hint.id }
                canvasDocumentId?.let { doc -> MainScope().launch { runCatching { hintApi.dismissHint(doc, hint.id) } } }
            }
        }
    }
}

private external interface HintCardProps : Props {
    var hint: Hint
    var onDismiss: () -> Unit
}

private val HintCardView = FC<HintCardProps> { props ->
    val hint = props.hint
    val anchor = hint.anchor
    // Right of the "?", or left of it when there is no room before the page edge.
    val left = if (anchor.maxX + CARD_GAP + CARD_WIDTH <= PAGE_WIDTH) anchor.maxX + CARD_GAP
    else maxOf(0.0, anchor.minX - CARD_GAP - CARD_WIDTH)

    ReactHTML.div {
        className = ClassName("hint-card hint-${hint.status.name.lowercase()}")
        val css: dynamic = js("({})")
        css.left = "${left}px"
        css.top = "${anchor.minY}px"
        css.width = "${CARD_WIDTH}px"
        asDynamic().style = css
        // keep clicks on the card from starting a stroke on the canvas
        onPointerDown = { it.stopPropagation() }

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
                onClick = { props.onDismiss() }
                ReactHTML.span { className = ClassName("ph"); +"ph-x" }
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
    }
}
