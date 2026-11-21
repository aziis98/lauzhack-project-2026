package com.lauzhack.web.ui

import com.lauzhack.shared.api.DrawingApi
import com.lauzhack.shared.debug.DebugPayload
import com.lauzhack.shared.ocr.stripBboxes
import com.lauzhack.web.markdown.renderMathMarkdown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName

private val debugApi = DrawingApi()

external interface DebugDrawerProps : Props {
    var documentId: String
}

private external interface DebugToggleProps : Props {
    var label: String
    var active: Boolean
    var onToggle: ((Boolean) -> Unit)?
}

private val DebugToggle = FC<DebugToggleProps> { props ->
    ReactHTML.button {
        className = ClassName(if (props.active) "debug-toggle active" else "debug-toggle")
        onClick = { _ -> props.onToggle?.invoke(!props.active) }
        +props.label
    }
}

/** Colored kind badge for a chunk's OCR classification. */
private fun kindBadgeClass(kind: String?): String = when (kind) {
    "MATH" -> "badge badge-math"
    "TEXT" -> "badge badge-text"
    "HINT_REQUEST" -> "badge badge-hint"
    "DRAWING" -> "badge badge-drawing"
    "UNREADABLE" -> "badge badge-unreadable"
    else -> "badge badge-pending"
}

/**
 * Floating pipeline inspector window (?debug). Hidden at first: only a 🐛 tab
 * floats top-right; clicking it opens a draggable, closable window. Polling
 * continues with the window hidden so canvas boxes remain up to date.
 */
val DebugDrawer = FC<DebugDrawerProps> { props ->
    var open by useState(DebugState.drawerOpen)
    var showPrompts by useState(false)
    var expanded by useState<String?>(null)
    val refreshRequests by useState { Channel<Unit>(Channel.CONFLATED) }
    useDebugUpdates()

    // This wrapper's effect coroutine is cancelled on document change/unmount.
    // Refresh wakes the same loop, so requests cannot race each other.
    useEffect(props.documentId) {
        val documentId = props.documentId
        DebugState.reset(documentId)
        try {
            while (true) {
                try {
                    val next = debugApi.debug(documentId)
                    currentCoroutineContext().ensureActive()
                    if (next == null) {
                        DebugState.error = "Could not load pipeline. Retrying…"
                        DebugState.notifyChanged()
                    } else if (DebugState.payload != next || DebugState.error != null) {
                        DebugState.payload = next
                        DebugState.error = null
                        DebugState.notifyChanged()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    DebugState.error = "Could not load pipeline. Retrying…"
                    DebugState.notifyChanged()
                }
                withTimeoutOrNull(2500L) { refreshRequests.receive() }
            }
        } finally {
            if (DebugState.documentId == documentId) DebugState.reset(null)
        }
    }

    // window position (page px); null until the user drags it
    var pos by useState<Pair<Double, Double>?>(null)
    val origin = useRef(Pair(0.0, 0.0))
    if (!open) {
        ReactHTML.button {
            className = ClassName("debug-tab")
            title = "Open debug inspector"
            onClick = { _ ->
                DebugState.drawerOpen = true
                open = true
            }
            ReactHTML.span { className = ClassName("ph"); +"ph-bug" }
        }
        return@FC
    }

    val payload = DebugState.payload.takeIf { DebugState.documentId == props.documentId }

    ReactHTML.div {
        className = ClassName("debug-window")
        val css: dynamic = js("({})")
        val p = pos
        if (p != null) {
            css.left = "${p.first}px"
            css.top = "${p.second}px"
        } else {
            css.right = "16px"
            css.top = "72px"
        }
        asDynamic().style = css

        // ---- header: drag handle + close ----
        ReactHTML.div {
            className = ClassName("debug-head")
            ReactHTML.span {
                className = ClassName("debug-handle")
                title = "Drag to move"
                onPointerDown = { event: react.dom.events.PointerEvent<web.html.HTMLSpanElement> ->
                    val start = Pair(event.clientX.toDouble(), event.clientY.toDouble())
                    val baseLeft = pos?.first
                        ?: js("window.innerWidth").unsafeCast<Double>() - 446.0
                    val baseTop = pos?.second ?: 72.0
                    origin.current = Pair(baseLeft, baseTop)

                    val onMove: (dynamic) -> Unit = { ev: dynamic ->
                        val nx = baseLeft + ev.clientX.unsafeCast<Double>() - start.first
                        val ny = baseTop + ev.clientY.unsafeCast<Double>() - start.second
                        pos = Pair(nx, ny)
                    }
                    var onUp: (dynamic) -> Unit = { _: dynamic -> }
                    onUp = { _: dynamic ->
                        Window.removeEventListener("pointermove", onMove)
                        Window.removeEventListener("pointerup", onUp)
                    }
                    Window.addEventListener("pointermove", onMove)
                    Window.addEventListener("pointerup", onUp)
                }
                ReactHTML.span { className = ClassName("ph"); +"ph-dots-six-vertical" }
            }
            ReactHTML.span { className = ClassName("ph"); +"ph-bug" }
            ReactHTML.span { className = ClassName("debug-title"); +"Debug" }
            ReactHTML.div { className = ClassName("grow") }
            ReactHTML.button {
                className = ClassName("debug-close")
                title = "Close"
                onClick = { _ ->
                    DebugState.drawerOpen = false
                    open = false
                }
                ReactHTML.span { className = ClassName("ph"); +"ph-x" }
                +" Close"
            }
        }

        // ---- body ----
        ReactHTML.div {
            className = ClassName("debug-body")

            // toggles
            ReactHTML.div {
                className = ClassName("debug-toggles")
                DebugToggle {
                    label = "chunks"
                    active = DebugState.showChunkBoxes
                    onToggle = { DebugState.showChunkBoxes = it; DebugState.notifyChanged() }
                }
                DebugToggle {
                    label = "fragments"
                    active = DebugState.showFragmentBoxes
                    onToggle = { DebugState.showFragmentBoxes = it; DebugState.notifyChanged() }
                }
                DebugToggle {
                    label = "model bboxes"
                    active = DebugState.showModelBoxes
                    onToggle = { DebugState.showModelBoxes = it; DebugState.notifyChanged() }
                }
                DebugToggle {
                    label = "prompts"
                    active = showPrompts
                    onToggle = { showPrompts = it }
                }
                ReactHTML.button {
                    className = ClassName("debug-toggle")
                    onClick = { _ -> refreshRequests.trySend(Unit) }
                    ReactHTML.span { className = ClassName("ph"); +"ph-arrows-clockwise" }
                    +" refresh"
                }
            }

            if (DebugState.error != null) {
                ReactHTML.div { className = ClassName("debug-empty"); +DebugState.error!! }
            }
            if (payload == null && DebugState.error == null) {
                ReactHTML.div { className = ClassName("debug-empty"); +"Loading pipeline…" }
            }
            if (payload != null) {
            // ================ OCR (top section) ================
            DebugOcrSection {
                this.payload = payload
                documentId = props.documentId
                this.showPrompts = showPrompts
                expandedChunk = expanded
                onExpand = { id -> expanded = if (expanded == id) null else id }
            }

            // ================ Agent conversation ================
            DebugAgentSection {
                this.payload = payload
                this.showPrompts = showPrompts
            }

            // ================ Hints ================
            DebugHintsSection { this.payload = payload }
            }
        }
    }
}

// ---------------------------------------------------------------
// OCR section
// ---------------------------------------------------------------
private external interface DebugOcrSectionProps : Props {
    var payload: DebugPayload
    var documentId: String
    var showPrompts: Boolean
    var expandedChunk: String?
    var onExpand: ((String) -> Unit)?
}

private val DebugOcrSection = FC<DebugOcrSectionProps> { props ->
    val payload = props.payload

    ReactHTML.section {
        className = ClassName("debug-section")
        key = Key("ocr")
        ReactHTML.h3 { +"OCR — ${payload.ocr.chunks.size} chunk(s)" }
        if (props.showPrompts) {
            DebugCollapsiblePrompt {
                title = "OCR system prompt"
                content = payload.ocr.systemPrompt
            }
        }

        for (chunk in payload.ocr.chunks) {
            val isExpanded = props.expandedChunk == chunk.chunkId
            val kind = chunk.response?.kind?.name

            ReactHTML.div {
                className = ClassName("debug-chunk-card")
                key = Key(chunk.chunkId)

                // header row: id + kind badge + fragment count
                ReactHTML.button {
                    className = ClassName("debug-chunk-head")
                    onClick = { _ -> props.onExpand?.invoke(chunk.chunkId) }
                    ReactHTML.span {
                        className = ClassName(kindBadgeClass(kind))
                        +(kind ?: "pending")
                    }
                    +" ${chunk.chunkId} · ${chunk.fragments.size} fragments"
                    if (!isExpanded) {
                        ReactHTML.span {
                            className = ClassName("debug-expand-hint")
                            +" ▸"
                        }
                    }
                }

                if (isExpanded) {
                    // transcription (rendered, clean LaTeX)
                    val transcription = chunk.response?.content
                    if (transcription != null && transcription.isNotBlank()) {
                        ReactHTML.div {
                            className = ClassName("debug-transcription")
                            ReactHTML.h4 { +"transcription" }
                            val clean = transcription.stripBboxes()
                            if (clean.isNotBlank()) {
                                val html = renderMathMarkdown(clean)
                                val inner: dynamic = js("({})")
                                inner.__html = html
                                ReactHTML.div {
                                    className = ClassName("debug-math")
                                    asDynamic().dangerouslySetInnerHTML = inner
                                }
                            }
                            ReactHTML.details {
                                className = ClassName("debug-raw")
                                ReactHTML.summary { +"raw augmented" }
                                ReactHTML.pre { +transcription }
                            }
                        }
                    } else if (kind == "UNREADABLE") {
                        ReactHTML.div {
                            className = ClassName("debug-empty")
                            +"unreadable"
                        }
                    } else {
                        ReactHTML.div {
                            className = ClassName("debug-empty")
                            +"pending…"
                        }
                    }

                    // model bboxes table
                    if (chunk.modelBoxes.isNotEmpty()) {
                        ReactHTML.div {
                            className = ClassName("debug-boxes")
                            ReactHTML.h4 { +"model bboxes (${chunk.modelBoxes.size})" }
                            ReactHTML.table {
                                className = ClassName("debug-box-table")
                                ReactHTML.thead {
                                    ReactHTML.tr {
                                        ReactHTML.th { +"symbol" }
                                        ReactHTML.th { +"page box" }
                                        ReactHTML.th { +"size" }
                                    }
                                }
                                ReactHTML.tbody {
                                    for (box in chunk.modelBoxes) {
                                        ReactHTML.tr {
                                            key = Key("${chunk.chunkId}-box-${box.label}-${box.minX.toInt()}")
                                            ReactHTML.td { +box.label }
                                            ReactHTML.td {
                                                className = ClassName("mono")
                                                +"(${box.minX.toInt()},${box.minY.toInt()})–(${box.maxX.toInt()},${box.maxY.toInt()})"
                                            }
                                            ReactHTML.td {
                                                className = ClassName("mono")
                                                +"${(box.maxX - box.minX).toInt()}×${(box.maxY - box.minY).toInt()}"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // images
                    if (props.showPrompts) {
                        ReactHTML.div {
                            className = ClassName("debug-images")
                            ReactHTML.img {
                                className = ClassName("debug-img")
                                src = "/api/documents/${props.documentId}/chunks/${chunk.chunkId}/image.png"
                                alt = "chunk raster"
                            }
                            ReactHTML.img {
                                className = ClassName("debug-img")
                                src = "/api/documents/${props.documentId}/chunks/${chunk.chunkId}/annotated.png"
                                alt = "segmentation"
                            }
                        }
                        DebugCollapsiblePrompt {
                            title = "prompt sent to the model"
                            content = chunk.userText
                        }
                    }

                    // fragments compact list
                    if (chunk.fragments.isNotEmpty()) {
                        ReactHTML.details {
                            className = ClassName("debug-raw")
                            ReactHTML.summary { +"${chunk.fragments.size} fragments (image px)" }
                            ReactHTML.pre {
                                +chunk.fragments.joinToString("\n") {
                                    "#${it.id} (${it.minX},${it.minY})–(${it.maxX},${it.maxY}) area=${it.area}"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------
// Agent section
// ---------------------------------------------------------------
private external interface DebugAgentSectionProps : Props {
    var payload: DebugPayload
    var showPrompts: Boolean
}

private val DebugAgentSection = FC<DebugAgentSectionProps> { props ->
    val payload = props.payload

    ReactHTML.section {
        className = ClassName("debug-section")
        key = Key("agent")
        ReactHTML.h3 { +"Agent — ${payload.agent.turns.size} turn(s)" }
        if (props.showPrompts) {
            DebugCollapsiblePrompt {
                title = "system prompt"
                content = payload.agent.systemPrompt
            }
        }

        for ((i, turn) in payload.agent.turns.withIndex()) {
            DebugTurn {
                key = Key("turn-$i")
                data = turn
            }
        }
        if (payload.agent.turns.isEmpty()) {
            ReactHTML.div { className = ClassName("debug-empty"); +"no turns yet" }
        }
    }
}

// ---------------------------------------------------------------
// One agent turn, parsed and rendered as a card
// ---------------------------------------------------------------
private external interface DebugTurnProps : Props {
    var data: com.lauzhack.shared.debug.DebugTurn
}

private val DebugTurn = FC<DebugTurnProps> { props ->
    val turn = props.data

    ReactHTML.div {
        className = ClassName("debug-turn-card ${turn.label}")

        // header: label + role
        ReactHTML.div {
            className = ClassName("debug-turn-head")
            val icon = when (turn.label) {
                "page" -> "ph-file-text"
                "delta" -> "ph-arrows-left-right"
                else -> "ph-chat-circle-text"
            }
            ReactHTML.span { className = ClassName("ph"); +icon }
            ReactHTML.span {
                className = ClassName("debug-turn-label ${turn.role}")
                +"[${turn.label}]"
            }
            ReactHTML.span {
                className = ClassName("debug-turn-meta")
                +"${turn.role} · ${turn.content.length} chars"
            }
        }

        // body: parsed rendering depending on turn type
        when (turn.label) {
            "assistant" -> DebugAssistantActions {
                content = turn.content
            }
            "page", "delta" -> DebugPageOrDelta {
                content = turn.content
                label = turn.label
            }
            else -> ReactHTML.pre { +turn.content }
        }

        // raw JSON collapsible
        ReactHTML.details {
            className = ClassName("debug-raw")
            ReactHTML.summary { +"raw" }
            ReactHTML.pre { +turn.content }
        }
    }
}

// ---------------------------------------------------------------
// Assistant actions → labeled cards
// ---------------------------------------------------------------
private external interface DebugAssistantActionsProps : Props {
    var content: String
}

private val DebugAssistantActions = FC<DebugAssistantActionsProps> { props ->
    val parsed: dynamic = try {
        JSON.parse(props.content)
    } catch (_: Exception) {
        null
    }
    val actions = parsed?.actions as? Array<dynamic>

    if (actions == null || actions.isEmpty()) {
        ReactHTML.div {
            className = ClassName("debug-silence")
            +"silence — no actions"
        }
        return@FC
    }

    ReactHTML.div {
        className = ClassName("debug-actions")
        for (action in actions) {
            val kind = action.kind?.toString() ?: ""
            ReactHTML.div {
                className = ClassName("debug-action-card action-$kind")
                when (kind) {
                    "hint" -> {
                        ReactHTML.div {
                            className = ClassName("debug-action-title")
                            ReactHTML.span { className = ClassName("ph"); +"ph-lightbulb" }
                            +"HINT · ${action.id ?: ""}"
                        }
                        if (action.title != null) ReactHTML.p { +(action.title.toString()) }
                        if (action.message != null) ReactHTML.p { +(action.message.toString()) }
                        val steps = action.steps as? Array<dynamic>
                        if (steps != null && steps.isNotEmpty()) {
                            ReactHTML.ul {
                                for (s in steps) ReactHTML.li { +(s.toString()) }
                            }
                        }
                    }
                    "plot" -> {
                        ReactHTML.div {
                            className = ClassName("debug-action-title")
                            ReactHTML.span { className = ClassName("ph"); +"ph-chart-line" }
                            +"PLOT · ${action.id ?: ""}"
                        }
                        ReactHTML.p {
                            className = ClassName("mono")
                            +"${action.functionName ?: "?"}(${action.variable ?: "x"}) = ${action.expression ?: "?"}"
                        }
                        val domain = action.domain as? Array<dynamic>
                        if (domain != null && domain.isNotEmpty()) {
                            ReactHTML.p {
                                className = ClassName("debug-action-meta")
                                +"domain: ${domain.joinToString(" ∪ ") { d -> "[${d?.from ?: "?"}, ${d?.to ?: "?"}]" }}"
                            }
                        }
                    }
                    "error" -> {
                        ReactHTML.div {
                            className = ClassName("debug-action-title")
                            ReactHTML.span { className = ClassName("ph"); +"ph-warning" }
                            +"ERROR · ${action.id ?: ""}"
                        }
                        if (action.chunkId != null) ReactHTML.p { +"chunk: ${action.chunkId}" }
                        if (action.message != null) ReactHTML.p { +(action.message.toString()) }
                        if (action.suggestion != null) {
                            ReactHTML.p {
                                className = ClassName("debug-action-meta")
                                +(action.suggestion.toString())
                            }
                        }
                    }
                    else -> {
                        ReactHTML.div {
                            className = ClassName("debug-action-title")
                            +"$kind (unknown)"
                        }
                    }
                }
                val remove = action.remove
                if (remove == true) {
                    ReactHTML.span {
                        className = ClassName("debug-action-removed")
                        +"withdrawn"
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------
// Page snapshot or delta → parsed chunk rows
// ---------------------------------------------------------------
private external interface DebugPageOrDeltaProps : Props {
    var content: String
    var label: String
}

private val DebugPageOrDelta = FC<DebugPageOrDeltaProps> { props ->
    val parsed: dynamic = try {
        JSON.parse(props.content)
    } catch (_: Exception) {
        null
    }
    if (parsed == null) return@FC

    val chunks: Array<dynamic>? = when (props.label) {
        "page" -> parsed.chunks as? Array<dynamic>
        else -> {
            // delta: added + modified
            val added = parsed.added as? Array<dynamic> ?: emptyArray()
            val modified = parsed.modified as? Array<dynamic> ?: emptyArray()
            (added + modified).ifEmpty { null }
        }
    }
    val removed = parsed.removed as? Array<dynamic>

    // exercise (page only)
    if (props.label == "page" && parsed.exercise != null) {
        val exerciseText = parsed.exercise.toString()
        if (exerciseText.isNotBlank()) {
            ReactHTML.div {
                className = ClassName("debug-exercise-tag")
                +"exercise: ${exerciseText.take(80)}…"
            }
        }
    }

    if (removed != null && removed.isNotEmpty()) {
        ReactHTML.div {
            className = ClassName("debug-removed-tag")
            +"− removed: ${removed.joinToString(", ") { it.toString() }}"
        }
    }

    if (chunks != null && chunks.isNotEmpty()) {
        ReactHTML.div {
            className = ClassName("debug-chunk-rows")
            for (chunk in chunks) {
                ReactHTML.div {
                    className = ClassName("debug-chunk-row")
                    ReactHTML.span {
                        className = ClassName(kindBadgeClass(chunk.kind?.toString()))
                        +(chunk.kind?.toString() ?: "?")
                    }
                    ReactHTML.span {
                        className = ClassName("mono")
                        +"${chunk.id ?: "?"}"
                    }
                    ReactHTML.span {
                        className = ClassName("debug-chunk-content")
                        +(chunk.content?.toString()?.take(60) ?: "")
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------
// Hints section
// ---------------------------------------------------------------
private external interface DebugHintsSectionProps : Props {
    var payload: DebugPayload
}

private val DebugHintsSection = FC<DebugHintsSectionProps> { props ->
    ReactHTML.section {
        className = ClassName("debug-section")
        key = Key("hints")
        ReactHTML.h3 { +"Hints — ${props.payload.hints.size}" }
        for (hint in props.payload.hints) {
            ReactHTML.div {
                className = ClassName("debug-hint-row")
                key = Key(hint.id)
                ReactHTML.span { className = ClassName(kindBadgeClass(hint.card?.let { "TEXT" } ?: "pending")); +hint.status.name }
                ReactHTML.span {
                    className = ClassName("mono")
                    +"${hint.id} → chunk ${hint.triggerChunkId ?: "?"}"
                }
                hint.card?.let { card ->
                    ReactHTML.p { +"${card.title}: ${card.message.take(100)}" }
                }
            }
        }
        if (props.payload.hints.isEmpty()) {
            ReactHTML.div { className = ClassName("debug-empty"); +"none" }
        }
    }
}

// ---------------------------------------------------------------
// Collapsible raw prompt
// ---------------------------------------------------------------
private external interface DebugCollapsiblePromptProps : Props {
    var title: String
    var content: String
}

private val DebugCollapsiblePrompt = FC<DebugCollapsiblePromptProps> { props ->
    ReactHTML.details {
        className = ClassName("debug-prompt")
        ReactHTML.summary { +props.title }
        ReactHTML.pre { +props.content }
    }
}

/** JS JSON binding. */
private external object JSON {
    fun <T> parse(text: String): T
}
