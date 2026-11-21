package com.lauzhack.web.ui

/**
 * Path-based routing with short, readable URLs:
 * `/` is the document list, `/doc/{16-hex}` opens a drawing.
 * The short hex is the first 16 hex chars of the document's uuid, so no
 * extra storage is needed; the server resolves it back to the full id.
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
private val shortIdRegex = Regex("^[0-9a-f]{16}$")

/** `/doc/{first 16 hex chars of the document's uuid}` */
fun docPath(docId: String): String {
    val hex = docId.removePrefix("doc:").replace("-", "").take(16)
    return "/doc/$hex"
}

/** The short id encoded in a `/doc/...` path, or null. */
fun shortIdFromPath(path: String): String? {
    if (!path.startsWith("/doc/")) return null
    val short = path.removePrefix("/doc/").substringBefore('/')
    return if (short.matches(shortIdRegex)) short else null
}

/** Legacy `/{docId}` path form, or null. */
fun fullIdFromPath(path: String): String? {
    val candidate = path.removePrefix("/").substringBefore('/')
    return if (candidate.startsWith("doc:")) candidate else null
}

/** Document id from the legacy `?doc=` query param, or null. */
fun docIdFromQuery(): String? {
    val id: String? = js("new URLSearchParams(window.location.search).get('doc')")
    return id?.takeIf { it.startsWith("doc:") }
}

/** Sync the app to the browser's back/forward navigation (passes the raw path). */
fun listenForPopState(onPath: (String) -> Unit) {
    Window.addEventListener("popstate") { _ ->
        onPath(Location.pathname)
    }
}

/** Navigate to a document's path (pushes history so browser Back returns home). */
fun navigateToDocument(docId: String) {
    BrowserHistory.pushState(null, "", docPath(docId))
}

/** Navigate back to the document list. */
fun navigateHome() {
    BrowserHistory.pushState(null, "", "/")
}
