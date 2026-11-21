package com.lauzhack.web.ui

import com.lauzhack.web.tldraw.Editor
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML
import react.useState
import web.cssom.ClassName

external interface ToolbarProps : Props {
    var editor: Editor?
}

private data class Tool(val label: String, val id: String, val icon: String)

/** Minimal tool pill; drives tldraw's editor directly. */
private val tools = listOf(
    Tool("Select", "select", "ph-cursor"),
    Tool("Draw", "draw", "ph-pencil"),
    Tool("Eraser", "eraser", "ph-eraser"),
    Tool("Shape", "geo", "ph-square"),
    Tool("Arrow", "arrow", "ph-arrow-bend-up-right"),
    Tool("Text", "text", "ph-text-aa"),
    Tool("Note", "note", "ph-note"),
)

val Toolbar = FC<ToolbarProps> { props ->
    var active by useState("select")

    ReactHTML.div {
        className = ClassName("tool-dock")
        for (tool in tools) {
            ReactHTML.button {
                className = ClassName(if (tool.id == active) "tool active" else "tool")
                key = Key(tool.id)
                disabled = (props.editor == null)
                title = tool.label
                onClick = { _ ->
                    val ed = props.editor
                    if (ed != null) {
                        ed.setCurrentTool(tool.id)
                        active = tool.id
                    }
                }
                ReactHTML.span {
                    className = ClassName("ph")
                    +tool.icon
                }
            }
        }
    }
}
