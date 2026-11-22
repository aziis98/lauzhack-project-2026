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
import web.html.InputType
import web.html.file

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
    var activeSubject by useState("all")
    // the PDF being imported as an exercise (a browser File), null when not importing
    var importing by useState<dynamic>(null)

    fun refresh() {
        scope.launch { documents = api.listDocuments() }
    }

    fun createAndOpen(name: String) {
        scope.launch {
            val doc = api.createDocument(name)
            refresh()
            selected = doc.id
            navigateToDocument(doc.id)
        }
    }

    // ---- routing: interpret a URL path into app state ----
    fun handleUrlPath(path: String, replaceUrl: Boolean) {
        val short = shortIdFromPath(path)
        if (short != null) {
            scope.launch {
                val doc = runCatching { api.getDocument(short) }.getOrNull()
                selected = doc?.id
            }
        } else {
            selected = null
            if (replaceUrl && path != "/") {
                BrowserHistory.replaceState(null, "", "/")
            }
        }
    }

    useEffectOnce {
        debugMode = debugFromQuery()
        refresh()
        handleUrlPath(Location.pathname, replaceUrl = true)
        // keep the app in sync with browser back/forward navigation
        listenForPopState { newPath -> handleUrlPath(newPath, replaceUrl = false) }
    }

    val openId = selected

    if (openId == null) {
        // ---------------- home: dashboard with subject sidebar ----------------
        val pdfFile = importing
        if (pdfFile != null) {
            PdfImport {
                file = pdfFile
                onClose = { importing = null }
                onCreated = { id ->
                    importing = null
                    refresh()
                    selected = id
                    navigateToDocument(id)
                }
            }
        }
        ReactHTML.div {
            className = ClassName("dash")

            // subject sidebar (mock data for now)
            ReactHTML.aside {
                className = ClassName("sidebar-card")
                Sidebar {
                    active = activeSubject
                    onSelect = { subject -> activeSubject = subject }
                }
            }

            ReactHTML.div {
                className = ClassName("dash-main")

                TopBar {
                    title = "Lauzhack Draw"
                    subtitle = "Kotlin · SQLite · tldraw"
                }

                ReactHTML.div {
                    className = ClassName("ws-header")

                    ReactHTML.h2 {
                        +(if (activeSubject == "all") "All worksheets" else activeSubject)
                    }

                    // PDF import: label wraps a hidden file input; opens the PdfImport screen
                    ReactHTML.label {
                        className = ClassName("btn-primary")
                        ReactHTML.span { className = ClassName("ph ph-upload-simple") }
                        +"Upload PDF"
                        ReactHTML.input {
                            className = ClassName("hidden-file-input")
                            type = InputType.file
                            accept = "application/pdf"
                            onChange = { event ->
                                val files: dynamic = event.target.asDynamic().files
                                val first: dynamic = if (files != null) files[0] else null
                                if (first != null) {
                                    importing = first
                                    event.target.asDynamic().value = ""
                                }
                            }
                        }
                    }
                }

                ReactHTML.div {
                    className = ClassName("ws-grid")

                    // new worksheet tile
                    ReactHTML.div {
                        className = ClassName("ws-new")
                        key = Key("new-tile")
                        ReactHTML.span { className = ClassName("ph ph-plus") }
                        ReactHTML.input {
                            className = ClassName("ws-new-input")
                            placeholder = "New worksheet…"
                            value = newName
                            onChange = { event -> newName = event.target.value }
                            onKeyDown = { event ->
                                if (event.key == "Enter") {
                                    createAndOpen(newName.ifBlank { "Untitled" })
                                    newName = ""
                                }
                            }
                        }
                    }

                    val docs = documents
                    if (docs == null) {
                        ReactHTML.div {
                            className = ClassName("ws-empty")
                            key = Key("loading")
                            +"Loading…"
                        }
                    } else {
                        val shown = if (activeSubject == "all") docs else emptyList()
                        if (shown.isEmpty() && activeSubject == "all" && docs.isEmpty()) {
                            ReactHTML.div {
                                className = ClassName("ws-empty")
                                key = Key("empty")
                                ReactHTML.span { className = ClassName("ph ph-notebook") }
                                +"No worksheets yet — create your first one with the tile above."
                            }
                        } else if (activeSubject != "all") {
                            // subjects are mock for now; nothing is filed under them yet
                            ReactHTML.div {
                                className = ClassName("ws-empty")
                                key = Key("subject-empty")
                                ReactHTML.span { className = ClassName("ph ph-folder-simple") }
                                +"No worksheets in $activeSubject yet — subjects get real wiring soon."
                            }
                        } else {
                            for (doc in shown) {
                                ReactHTML.button {
                                    className = ClassName("ws-card")
                                    key = Key(doc.id)
                                    onClick = { _ ->
                                        selected = doc.id
                                        navigateToDocument(doc.id)
                                    }
                                    ReactHTML.div {
                                        className = ClassName("ws-thumb")
                                        ReactHTML.span { className = ClassName("ph ph-notebook") }
                                    }
                                    ReactHTML.span {
                                        className = ClassName("ws-title")
                                        +doc.name
                                    }
                                    ReactHTML.span {
                                        className = ClassName("ws-time mono")
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
                ReactHTML.span { className = ClassName("ph ph-arrow-left") }
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

            // The inspector polls while mounted, even when its window is closed.
            if (debugMode) {
                DebugDrawer {
                    key = Key(openId)
                    documentId = openId
                }
            }
        }
    }
}
