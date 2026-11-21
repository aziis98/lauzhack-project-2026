package com.lauzhack.web.ui

import com.lauzhack.shared.api.DrawingApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML
import react.useEffect
import react.useEffectOnce
import react.useRef
import react.useState
import web.cssom.ClassName

private val debugApi = DrawingApi()
private val debugScope = CoroutineScope(Dispatchers.Default)
private var debugJob: Job? = null

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

/**
 * Floating pipeline inspector window (?debug). Hidden at first: only a 🐛 tab
 * floats top-right; clicking it opens a draggable, closable window showing
 * the agent conversation (system prompt + every turn), the OCR prompts and
 * answers per chunk, and the hints. Feeds the canvas overlay via DebugState.
 */
val DebugDrawer = FC<DebugDrawerProps> { props ->
    var open by useState(DebugState.drawerOpen)
    var showPrompts by useState(false)
    var expanded by useState<String?>(null)
    var version by useState(0)

    // re-render whenever the poller publishes new data (same channel as the canvas layer)
    useEffectOnce {
        DebugState.listen { version += 1 }
    }

    // window position (page px); null until the user drags it
    var pos by useState<Pair<Double, Double>?>(null)
    val origin = useRef(Pair(0.0, 0.0))

    // single poller: refresh the shared DebugState, then re-render
    useEffect(props.documentId, open) {
        if (!open) return@useEffect
        debugJob?.cancel()
        debugJob = debugScope.launch {
            while (isActive) {
                val payload = runCatching { debugApi.debug(props.documentId) }.getOrNull()
                if (payload != null && DebugState.payload != payload) {
                    DebugState.payload = payload
                    DebugState.notifyChanged()
                }
                delay(2500)
            }
        }
        null
    }

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

    val payload = DebugState.payload

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

        // ---- header: only the dedicated handle drags, so buttons always work ----
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
            }

            if (payload != null) {

            // ---- agent transcript ----
            ReactHTML.section {
                className = ClassName("debug-section")
                key = Key("agent")
                ReactHTML.h3 { +"Agent conversation (${payload.agent.turns.size} turns)" }
                if (showPrompts) {
                    ReactHTML.details {
                        className = ClassName("debug-prompt")
                        ReactHTML.summary { +"system prompt" }
                        ReactHTML.pre { +payload.agent.systemPrompt }
                    }
                }
                for ((i, turn) in payload.agent.turns.withIndex()) {
                    ReactHTML.details {
                        className = ClassName("debug-turn ${turn.role} ${turn.label}")
                        key = Key("turn-$i")
                        ReactHTML.summary { +"[${turn.label}] ${turn.role} · ${turn.content.length} chars" }
                        ReactHTML.pre { +turn.content }
                    }
                }
                if (payload.agent.turns.isEmpty()) {
                    ReactHTML.div { className = ClassName("debug-empty"); +"no turns yet" }
                }
            }

            // ---- OCR per chunk ----
            ReactHTML.section {
                className = ClassName("debug-section")
                key = Key("ocr")
                ReactHTML.h3 { +"OCR (${payload.ocr.chunks.size} chunks)" }
                if (showPrompts) {
                    ReactHTML.details {
                        className = ClassName("debug-prompt")
                        ReactHTML.summary { +"OCR system prompt" }
                        ReactHTML.pre { +payload.ocr.systemPrompt }
                    }
                }
                for (chunk in payload.ocr.chunks) {
                    val isExpanded = expanded == chunk.chunkId
                    ReactHTML.div {
                        className = ClassName("debug-chunk")
                        key = Key(chunk.chunkId)
                        ReactHTML.button {
                            className = ClassName("debug-chunk-head")
                            onClick = { _ -> expanded = if (isExpanded) null else chunk.chunkId }
                            val kind = chunk.response?.kind?.name ?: "pending"
                            +"${chunk.chunkId} · $kind · ${chunk.fragments.size} fragments"
                        }
                        if (isExpanded) {
                            if (showPrompts) {
                                ReactHTML.div {
                                    className = ClassName("debug-prompt-inline")
                                    ReactHTML.h4 { +"prompt sent to the model" }
                                    ReactHTML.pre { +chunk.userText }
                                }
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
                            ReactHTML.h4 { +"fragments (image px)" }
                            ReactHTML.pre { +chunk.fragments.joinToString("\n") { "(${it.minX},${it.minY},${it.maxX},${it.maxY}) area=${it.area}" } }
                            ReactHTML.h4 { +"response" }
                            val responseText = chunk.response?.content
                            if (responseText != null) {
                                ReactHTML.pre { +responseText }
                                if (chunk.modelBoxes.isNotEmpty()) {
                                    ReactHTML.h4 { +"model bboxes (page units)" }
                                    ReactHTML.pre {
                                        +chunk.modelBoxes.joinToString("\n") {
                                            "(${it.minX.toInt()},${it.minY.toInt()})–(${it.maxX.toInt()},${it.maxY.toInt()}) ${it.label}"
                                        }
                                    }
                                }
                            } else {
                                ReactHTML.div { className = ClassName("debug-empty"); +"pending…" }
                            }
                        }
                    }
                }
            }

            // ---- hints ----
            ReactHTML.section {
                className = ClassName("debug-section")
                key = Key("hints")
                ReactHTML.h3 { +"Hints (${payload.hints.size})" }
                for (hint in payload.hints) {
                    ReactHTML.details {
                        key = Key(hint.id)
                        ReactHTML.summary {
                            +"${hint.id} · ${hint.triggerChunkId ?: "-"} · ${hint.status.name}"
                        }
                        ReactHTML.pre { +hint.toString() }
                    }
                }
                if (payload.hints.isEmpty()) {
                    ReactHTML.div { className = ClassName("debug-empty"); +"none" }
                }
            }
            } // payload != null
        }
    }
}
