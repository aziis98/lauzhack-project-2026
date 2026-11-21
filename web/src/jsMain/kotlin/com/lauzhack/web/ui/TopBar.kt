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

/** Three-column grid: title left, tools centered, status right. */
val TopBar = FC<TopBarProps> { props ->
    ReactHTML.header {
        className = ClassName("topbar")

        ReactHTML.div {
            className = ClassName("tb-left")
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
        }

        if (props.showTools) {
            ReactHTML.div {
                className = ClassName("tb-center")
                Toolbar { editor = props.editor }
            }
        }

        ReactHTML.span {
            className = ClassName("tb-right")
            +(props.subtitle ?: "")
        }
    }
}
