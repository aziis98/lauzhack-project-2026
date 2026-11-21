package com.lauzhack.web.ui

import com.lauzhack.web.tldraw.Editor
import react.FC
import react.Props
import react.dom.html.ReactHTML
import web.cssom.ClassName

external interface ZoomControlsProps : Props {
    var editor: Editor?
}

/** Zoom buttons, bottom-left: zoom in, zoom out, reset to the initial zoom. */
val ZoomControls = FC<ZoomControlsProps> { props ->
    ReactHTML.div {
        className = ClassName("zoom-dock")

        ReactHTML.button {
            className = ClassName("zoom-btn")
            +"−"
            onClick = { _ -> props.editor?.zoomOut() }
        }

        ReactHTML.button {
            className = ClassName("zoom-btn")
            +"+"
            onClick = { _ -> props.editor?.zoomIn() }
        }

        ReactHTML.button {
            className = ClassName("zoom-btn")
            +"⟲"
            title = "Reset zoom"
            onClick = { _ -> props.editor?.resetZoom() }
        }
    }
}
