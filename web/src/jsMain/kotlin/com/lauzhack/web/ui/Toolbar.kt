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

private data class Tool(val label: String, val id: String)

/** Minimal tool selection; drives tldraw's editor directly. */
private val tools = listOf(
    Tool("Select", "select"),
    Tool("Draw", "draw"),
    Tool("Eraser", "eraser"),
    Tool("Shape", "geo"),
    Tool("Arrow", "arrow"),
    Tool("Text", "text"),
    Tool("Note", "note"),
)

val Toolbar = FC<ToolbarProps> { props ->
    var active by useState("select")

    ReactHTML.div {
        className = ClassName("toolbar")
        for (tool in tools) {
            ReactHTML.button {
                className = ClassName(if (tool.id == active) "tool active" else "tool")
                key = Key(tool.id)
                disabled = (props.editor == null)
                +tool.label
                onClick = { _ ->
                    val ed = props.editor
                    if (ed != null) {
                        ed.setCurrentTool(tool.id)
                        active = tool.id
                    }
                }
            }
        }
    }
}
