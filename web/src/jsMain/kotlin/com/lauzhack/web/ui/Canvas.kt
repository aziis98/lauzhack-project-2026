package com.lauzhack.web.ui

import com.lauzhack.shared.api.DrawingApi
import com.lauzhack.web.tldraw.Tldraw
import com.lauzhack.web.tldraw.toElementOrNull
import com.lauzhack.web.tldraw.toShapePartial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import react.FC
import react.Props

private val api = DrawingApi()

external interface CanvasProps : Props {
    var documentId: String
}

val Canvas = FC<CanvasProps> { props ->
    Tldraw {
        onMount = { editor ->
            CoroutineScope(Dispatchers.Default).launch {
                // LOAD: fetch the document's elements and push them into the editor
                val elements = runCatching { api.getElements(props.documentId) }.getOrDefault(emptyList())

                val existing = editor.getCurrentPageShapes()
                if (existing.isNotEmpty()) {
                    editor.deleteShapes(existing.map { it.id }.toTypedArray())
                }

                val pageId = editor.getCurrentPageId()
                val partials = elements.mapNotNull { it.toShapePartial(pageId) }
                if (partials.isNotEmpty()) {
                    runCatching { editor.createShapes(partials.toTypedArray()) }
                }

                // SAVE: batch-replace the document's elements every 2.5 seconds
                while (isActive) {
                    delay(2500)
                    val current = editor.getCurrentPageShapes().mapNotNull { it.toElementOrNull() }
                    runCatching { api.replaceElements(props.documentId, current) }
                }
            }
        }
    }
}
