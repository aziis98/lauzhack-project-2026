package com.lauzhack.web.ui

import com.lauzhack.shared.api.DrawingApi
import com.lauzhack.web.tldraw.Editor
import com.lauzhack.web.tldraw.Tldraw
import com.lauzhack.web.tldraw.toElementOrNull
import com.lauzhack.web.tldraw.toShapePartial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import react.FC
import react.Props

private val api = DrawingApi()

private val json = Json

external interface CanvasProps : Props {
    var documentId: String
    var onReady: ((Editor) -> Unit)?
    var onSaved: (() -> Unit)?
}

/** Hides tldraw's default chrome; we provide our own minimal toolbar. */
private fun hiddenComponents(): dynamic {
    val c: dynamic = js("{}")
    c.Minimap = null
    c.NavigationPanel = null
    c.MainMenu = null
    c.HelpMenu = null
    c.Toolbar = null
    return c
}

val Canvas = FC<CanvasProps> { props ->
    Tldraw {
        components = hiddenComponents()
        onMount = { editor ->
            props.onReady?.invoke(editor)

            CoroutineScope(Dispatchers.Default).launch {
                // LOAD: fetch the document's elements and push them into the editor.
                // If anything fails we bail out WITHOUT starting the autosave loop,
                // so a broken load can never overwrite stored content with an empty canvas.
                val elements = runCatching { api.getElements(props.documentId) }
                    .getOrElse { error ->
                        Console.error("LOAD FAILED: " + (error.message ?: error.toString()))
                        return@launch
                    }

                runCatching {
                    val existing = editor.getCurrentPageShapes()
                    if (existing.isNotEmpty()) {
                        editor.deleteShapes(existing.map { it.id }.toTypedArray())
                    }
                }

                val pageId = editor.getCurrentPageId()
                val partials = elements.mapNotNull { it.toShapePartial(pageId) }
                // create shapes one by one so a single invalid shape
                // doesn't prevent the others from rendering
                for (partial in partials) {
                    val created = runCatching { editor.createShapes(arrayOf(partial)) }
                    if (created.isFailure) {
                        Console.warn("skipped shape: " + (created.exceptionOrNull()?.message ?: "unknown error"))
                    }
                }
                if (partials.isNotEmpty()) {
                    runCatching { editor.zoomToFit() }
                }

                // SAVE: batch-replace when content changed (poll every 2.5s)
                var lastPayload: String? = null
                while (isActive) {
                    delay(2500)
                    val result = runCatching {
                        val current = editor.getCurrentPageShapes().mapNotNull { it.toElementOrNull() }
                        val payload = json.encodeToString(current)
                        if (payload != lastPayload) {
                            if (api.replaceElements(props.documentId, current)) {
                                lastPayload = payload
                                props.onSaved?.invoke()
                            }
                        }
                    }
                    // editor was disposed (component unmounted) — stop the loop
                    if (result.isFailure) {
                        Console.warn("autosave loop stopped:", result.exceptionOrNull()?.message)
                        break
                    }
                }
            }
        }
    }
}
