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
        ReactHTML.div {
            className = ClassName("app")

            ReactHTML.h1 { +"Lauzhack Draw" }

            ReactHTML.div {
                className = ClassName("toolbar")
                ReactHTML.input {
                    placeholder = "New drawing name"
                    value = newName
                    onChange = { event -> newName = event.target.value }
                }
                ReactHTML.button {
                    +"Create"
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

            val docs = documents
            if (docs == null) {
                ReactHTML.p { +"Loading…" }
            } else if (docs.isEmpty()) {
                ReactHTML.p { +"No drawings yet — create one above." }
            } else {
                ReactHTML.ul {
                    for (doc in docs) {
                        ReactHTML.li {
                            key = Key(doc.id)
                            ReactHTML.button {
                                +doc.name
                                onClick = { _ -> selected = doc.id }
                            }
                        }
                    }
                }
            }

            ReactHTML.p {
                className = ClassName("hint")
                +"Drawings are stored in SQLite via the Kotlin backend. The canvas autosaves every 2.5s."
            }
        }
    } else {
        ReactHTML.div {
            className = ClassName("canvas-wrap")
            ReactHTML.button {
                className = ClassName("back")
                +"← All drawings"
                onClick = { _ -> selected = null }
            }
            Canvas { documentId = openId }
        }
    }
}
