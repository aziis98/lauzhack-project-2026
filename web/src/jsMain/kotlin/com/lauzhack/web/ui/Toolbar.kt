package com.lauzhack.web.ui

import com.lauzhack.shared.api.DrawingApi
import com.lauzhack.web.tldraw.DefaultColorStyle
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import com.lauzhack.web.tldraw.Editor
import react.ChildrenBuilder
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML
import react.useState
import web.cssom.ClassName

external interface ToolbarProps : Props {
    var editor: Editor?
}

private val toolbarApi = DrawingApi()

private data class Tool(val label: String, val id: String, val icon: String)

private val tools = listOf(
    Tool("Select", "select", "ph-cursor"),
    Tool("Pen", "draw", "ph-pencil"),
    Tool("Eraser", "eraser", "ph-eraser"),
    Tool("Shape", "geo", "ph-square"),
    Tool("Arrow", "arrow", "ph-arrow-bend-up-right"),
    Tool("Text", "text", "ph-text-aa"),
    Tool("Note", "note", "ph-note"),
)

/** Quick ink colors (tldraw color names) with their swatch color. */
private val inks = listOf(
    "black" to "#1d1d1d",
    "blue" to "#4465e9",
    "red" to "#e03131",
    "green" to "#099268",
)

/**
 * Tool pill, usable without a keyboard (tablet): tools, quick ink colors,
 * undo/redo and pen mode (only the stylus draws; fingers pan and zoom).
 */
val Toolbar = FC<ToolbarProps> { props ->
    var active by useState("select")
    var ink by useState("black")
    var penMode by useState(false)
    val editor = props.editor

    fun ChildrenBuilder.iconButton(label: String, icon: String, isActive: Boolean = false, onPress: (Editor) -> Unit) {
        ReactHTML.button {
            className = ClassName(if (isActive) "tool active" else "tool")
            disabled = editor == null
            title = label
            asDynamic()["aria-label"] = label
            onClick = { _ -> editor?.let(onPress) }
            ReactHTML.span { className = ClassName("ph"); +icon }
        }
    }

    ReactHTML.div {
        className = ClassName("tool-dock")

        for (tool in tools) {
            ReactHTML.span {
                key = Key(tool.id)
                className = ClassName("tool-slot")
                iconButton(tool.label, tool.icon, tool.id == active) { ed ->
                    ed.setCurrentTool(tool.id)
                    active = tool.id
                }
            }
        }

        ReactHTML.span { className = ClassName("tool-sep") }

        for ((name, swatch) in inks) {
            ReactHTML.button {
                key = Key(name)
                className = ClassName(if (name == ink) "tool swatch active" else "tool swatch")
                disabled = editor == null
                title = "Ink: $name"
                asDynamic()["aria-label"] = "Ink: $name"
                onClick = { _ ->
                    editor?.let { ed ->
                        ed.setStyleForNextShapes(DefaultColorStyle, name)
                        ed.setStyleForSelectedShapes(DefaultColorStyle, name)
                        ink = name
                        if (active != "draw") { ed.setCurrentTool("draw"); active = "draw" }
                    }
                }
                ReactHTML.span {
                    className = ClassName("swatch-dot")
                    val css: dynamic = js("({})")
                    css.background = swatch
                    asDynamic().style = css
                }
            }
        }

        ReactHTML.span { className = ClassName("tool-sep") }

        iconButton("Undo", "ph-arrow-counter-clockwise") { it.undo() }
        iconButton("Redo", "ph-arrow-clockwise") { it.redo() }

        ReactHTML.span { className = ClassName("tool-sep") }

        // Ask the AI for a hint without drawing a circled "?"
        ReactHTML.button {
            className = ClassName("tool help")
            disabled = editor == null
            title = "Ask for a hint"
            asDynamic()["aria-label"] = "Ask for a hint"
            onClick = { _ ->
                canvasDocumentId?.let { doc -> MainScope().launch { runCatching { toolbarApi.requestHint(doc) } } }
            }
            ReactHTML.span { className = ClassName("ph"); +"ph-question" }
        }

        ReactHTML.span { className = ClassName("tool-sep") }

        iconButton(
            if (penMode) "Pen mode on: only the stylus draws" else "Pen mode off: fingers draw too",
            "ph-pen-nib",
            isActive = penMode,
        ) { ed ->
            val next = !penMode
            val state: dynamic = js("({})")
            state.isPenMode = next
            ed.updateInstanceState(state)
            penMode = next
        }
    }
}
