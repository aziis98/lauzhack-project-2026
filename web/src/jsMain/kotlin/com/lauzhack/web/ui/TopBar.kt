package com.lauzhack.web.ui

import react.FC
import react.Props
import react.dom.html.ReactHTML
import web.cssom.ClassName

external interface TopBarProps : Props {
    var title: String
    var subtitle: String?
    var onBack: (() -> Unit)?
}

/** App header used on the home page: title left, status right. */
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

        ReactHTML.span {
            className = ClassName("tb-right")
            +(props.subtitle ?: "")
        }
    }
}
