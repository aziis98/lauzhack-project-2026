package com.lauzhack.web.ui

/**
 * Minimal browser history/location bindings for path-based routing:
 * `/` is the document list, `/{documentId}` opens a drawing.
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

/** The document id encoded in the current URL path, or null on `/`. */
private fun docIdFromPath(path: String): String? {
    val candidate = path.removePrefix("/").substringBefore('/')
    return if (candidate.startsWith("doc:")) candidate else null
}

/** Document id for the initial page load: path first, then the legacy ?doc= deep link. */
fun initialDocId(): String? {
    docIdFromPath(Location.pathname)?.let { return it }
    val fromQuery: String? = js("new URLSearchParams(window.location.search).get('doc')")
    if (fromQuery != null && fromQuery.startsWith("doc:")) {
        // normalize legacy query deep links to the path form
        BrowserHistory.replaceState(null, "", "/$fromQuery")
        return fromQuery
    }
    return null
}

/** Sync the app to the browser's back/forward navigation. */
fun listenForPopState(onDocId: (String?) -> Unit) {
    Window.addEventListener("popstate") { _ ->
        onDocId(docIdFromPath(Location.pathname))
    }
}

/** Navigate to a document's path (pushes history so browser Back returns home). */
fun navigateToDocument(docId: String) {
    BrowserHistory.pushState(null, "", "/$docId")
}

/** Navigate back to the document list. */
fun navigateHome() {
    BrowserHistory.pushState(null, "", "/")
}
