package com.lauzhack.web.ui

/**
 * Path-based routing with short URLs:
 * `/` is the document list, `/doc/{16-hex}` opens a drawing.
 * The short hex is the first 16 hex chars of the document's uuid; the
 * server resolves it back to the full id.
 */

@JsName("location")
external object Location {
    val pathname: String
    val search: String
}

@JsName("history")
external object BrowserHistory {
    fun pushState(data: dynamic, title: String, url: String)
    fun replaceState(data: dynamic, title: String, url: String)
}

@JsName("window")
external object Window {
    fun addEventListener(type: String, listener: (event: dynamic) -> Unit)
}

/** Matches a 16-char lowercase hex short id. */
private val shortIdHex = Regex("^[0-9a-f]{16}$")

/** Matches a plausible id segment in a /doc/... path (hex short ids and full non-uuid ids). */
private val pathIdSegment = Regex("^[a-zA-Z0-9-]{4,64}$")

/** The URL path segment for a document: 16-hex short form for uuids, otherwise the full id. */
private fun pathSegment(docId: String): String {
    val hex = docId.removePrefix("doc:").replace("-", "")
    return if (hex.length >= 16 && hex.substring(0, 16).matches(shortIdHex)) {
        hex.substring(0, 16)
    } else {
        docId.removePrefix("doc:")
    }
}

/** `/doc/{path segment}` */
fun docPath(docId: String): String = "/doc/" + pathSegment(docId)

/** The id encoded in a `/doc/...` path, or null. */
fun shortIdFromPath(path: String): String? {
    if (!path.startsWith("/doc/")) return null
    val short = path.removePrefix("/doc/").substringBefore('/')
    return if (short.matches(pathIdSegment)) short else null
}

/** True when the app was opened with ?debug (pipeline inspector). Set once at boot. */
internal var debugMode: Boolean = false

fun debugFromQuery(): Boolean =
    js("new URLSearchParams(window.location.search).has('debug')")

/** Keeps the ?debug flag across pushState navigations. */
private fun withDebug(path: String): String = if (debugMode) "$path?debug" else path

/** Sync the app to the browser's back/forward navigation (passes the raw path). */
fun listenForPopState(onPath: (String) -> Unit) {
    Window.addEventListener("popstate") { _ ->
        onPath(Location.pathname)
    }
}

/** Navigate to a document's path (pushes history so browser Back returns home). */
fun navigateToDocument(docId: String) {
    BrowserHistory.pushState(null, "", withDebug(docPath(docId)))
}

/** Navigate back to the document list. */
fun navigateHome() {
    BrowserHistory.pushState(null, "", withDebug("/"))
}
