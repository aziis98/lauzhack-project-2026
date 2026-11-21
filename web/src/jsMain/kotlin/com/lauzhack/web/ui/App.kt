package com.lauzhack.web.ui

import com.lauzhack.shared.api.DrawingApi
import com.lauzhack.shared.domain.Document
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

/** Supports deep links like /?doc=doc:123 that open a drawing directly. */
private fun initialDocId(): String? =
    js("new URLSearchParams(window.location.search).get('doc')")

private fun formatTime(ms: Long): String {
    val date: dynamic = js("new Date()")
    date.setTime(ms)
    return date.toLocaleString().toString()
}

val App = FC<Props> {
    var documents by useState<List<Document>?>(null)
    var selected by useState(initialDocId())
    var newName by useState("")

    fun refresh() {
        scope.launch { documents = api.listDocuments() }
    }

    useEffectOnce {
        refresh()
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
                                    onClick = { _ -> selected = doc.id }
                                    ReactHTML.span { +doc.name }
                                    ReactHTML.span {
                                        className = ClassName("muted")
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
                +"←"
                onClick = { _ -> selected = null }
            }

            ReactHTML.div {
                className = ClassName("canvas-fill")
                Canvas {
                    documentId = openId
                }
            }
        }
    }
}
