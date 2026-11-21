package com.lauzhack.web.ui

/** Minimal binding to the browser console (for surfacing swallowed errors). */
@JsName("console")
external object Console {
    fun error(vararg args: Any?)
    fun warn(vararg args: Any?)
}
