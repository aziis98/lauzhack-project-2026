package com.lauzhack.web

import com.lauzhack.web.ui.App
import react.create
import react.dom.client.createRoot
import web.dom.ElementId
import web.dom.document

fun main() {
    // side-effect: bundles tldraw's stylesheet
    default

    val container = document.getElementById(ElementId("root"))
        ?: error("index.html must contain a #root element")
    createRoot(container).render(App.create())
}
