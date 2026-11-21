package com.lauzhack.web.ui

import com.lauzhack.web.tldraw.Editor
import react.FC
import react.Props
import react.dom.html.ReactHTML
import web.cssom.ClassName

external interface TopBarProps : Props {
    var title: String
    var subtitle: String?
    var editor: Editor?
    var showTools: Boolean
    var onBack: (() -> Unit)?
}

val TopBar = FC<TopBarProps> { props ->
    ReactHTML.header {
        className = ClassName("topbar")

        if (props.onBack != null) {
            ReactHTML.button {
                className = ClassName("ghost")
                +"←"
                onClick = { _ -> props.onBack?.invoke() }
            }
        }

        ReactHTML.div {
            className = ClassName("title")
            +props.title
        }

        if (props.showTools) {
            Toolbar { editor = props.editor }
        }

        ReactHTML.div { className = ClassName("grow") }

        ReactHTML.span {
            className = ClassName("status")
            +(props.subtitle ?: "")
        }
    }
}
