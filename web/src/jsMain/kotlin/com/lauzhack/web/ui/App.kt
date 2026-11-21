package com.lauzhack.web.ui

import com.lauzhack.shared.api.DrawingApi
import com.lauzhack.shared.domain.Document
import com.lauzhack.web.tldraw.Editor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

private val api = DrawingApi()

private val scope = CoroutineScope(Dispatchers.Default)

private fun formatTime(ms: Long): String {
    val date: dynamic = js("new Date()")
    date.setTime(ms)
    return date.toLocaleString().toString()
}

val App = FC<Props> {
    // the currently open document's FULL id (doc:<uuid>); null = home
    var selected by useState<String?>(null)
    var documents by useState<List<Document>?>(null)
    var newName by useState("")
    var activeEditor by useState<Editor?>(null)

    fun refresh() {
        scope.launch { documents = api.listDocuments() }
    }

    // ---- routing: interpret a URL path into app state ----
    fun handleUrlPath(path: String, replaceUrl: Boolean) {
        val short = shortIdFromPath(path)
        val legacy = fullIdFromPath(path)
        when {
            short != null || legacy != null -> {
                val idOrShort = short ?: legacy!!
                scope.launch {
                    val doc = runCatching { api.getDocument(idOrShort) }.getOrNull()
                    selected = doc?.id
                    if (doc != null && replaceUrl) {
                        // normalize legacy forms to the /doc/{hex} path
                        BrowserHistory.replaceState(null, "", docPath(doc.id))
                    }
                }
            }

            else -> {
                selected = null
                if (replaceUrl && path != "/" && path.isNotEmpty()) {
                    BrowserHistory.replaceState(null, "", "/")
                }
            }
        }
    }

    useEffectOnce {
        refresh()
        // initial URL: path first, then the legacy ?doc= deep link
        val path = Location.pathname
        val queryDoc = docIdFromQuery()
        if (path != "/") {
            handleUrlPath(path, replaceUrl = true)
        } else if (queryDoc != null) {
            handleUrlPath("/$queryDoc", replaceUrl = true)
        }
        // keep the app in sync with browser back/forward navigation
        listenForPopState { newPath -> handleUrlPath(newPath, replaceUrl = false) }
    }

    val openId = selected

    if (openId == null) {
        // ---------------- home: vertical scrolling document of cards ----------------
        ReactHTML.div {
            className = ClassName("page")

            TopBar {
                title = "Lauzhack Draw"
                subtitle = "Kotlin · SQLite · tldraw"
            }

            ReactHTML.section {
                className = ClassName("card card-pad")
                ReactHTML.div {
                    className = ClassName("create-row")
                    ReactHTML.input {
                        placeholder = "New drawing name…"
                        value = newName
                        onChange = { event -> newName = event.target.value }
                    }
                    ReactHTML.button {
                        +"New drawing"
                        onClick = { _ ->
                            val name = newName.ifBlank { "Untitled" }
                            scope.launch {
                                api.createDocument(name)
                                refresh()
                            }
                            newName = ""
                        }
                    }
                }
            }

            val docs = documents
            ReactHTML.section {
                className = ClassName("card")
                if (docs == null) {
                    ReactHTML.p { className = ClassName("card-pad muted"); +"Loading…" }
                } else if (docs.isEmpty()) {
                    ReactHTML.p {
                        className = ClassName("card-pad muted")
                        +"No drawings yet — create your first one above."
                    }
                } else {
                    ReactHTML.ul {
                        className = ClassName("docs")
                        for (doc in docs) {
                            ReactHTML.li {
                                key = Key(doc.id)
                                ReactHTML.button {
                                    className = ClassName("doc-row")
                                    onClick = { _ ->
                                        selected = doc.id
                                        navigateToDocument(doc.id)
                                    }
                                    ReactHTML.span { +doc.name }
                                    ReactHTML.span {
                                        className = ClassName("muted mono")
                                        +formatTime(doc.updatedAt)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        // ---------------- drawing: chrome-free full-page canvas ----------------
        ReactHTML.div {
            className = ClassName("fullscreen-page")

            // the only chrome: a minimal floating back button
            ReactHTML.button {
                className = ClassName("back-fab")
                title = "All drawings"
                onClick = { _ ->
                    selected = null
                    activeEditor = null
                    navigateHome()
                }
                ReactHTML.span { className = ClassName("ph"); +"ph-arrow-left" }
            }

            // minimal floating tool pill
            Toolbar { editor = activeEditor }

            // zoom buttons, bottom-left
            ZoomControls { editor = activeEditor }

            ReactHTML.div {
                className = ClassName("canvas-fill")
                Canvas {
                    // remount the editor when switching documents (the load/autosave
                    // coroutine and editor instance are bound to one document)
                    key = Key(openId)
                    documentId = openId
                    onReady = { activeEditor = it }
                }
            }
        }
    }
}
