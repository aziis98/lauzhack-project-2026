package com.lauzhack.web

import com.lauzhack.web.fonts.jetBrainsMono400
import com.lauzhack.web.fonts.openSans400
import com.lauzhack.web.fonts.openSans600
import com.lauzhack.web.fonts.openSans700
import com.lauzhack.web.fonts.phosphorRegular
import com.lauzhack.web.markdown.katexCss
import com.lauzhack.web.ui.App
import react.create
import react.dom.client.createRoot
import web.dom.ElementId
import web.dom.document

fun main() {
    // side-effect imports: tldraw stylesheet, fonts, and icon font
    default
    openSans400
    openSans600
    openSans700
    jetBrainsMono400
    phosphorRegular
    katexCss

    val container = document.getElementById(ElementId("root"))
        ?: error("index.html must contain a #root element")
    createRoot(container).render(App.create())
}
