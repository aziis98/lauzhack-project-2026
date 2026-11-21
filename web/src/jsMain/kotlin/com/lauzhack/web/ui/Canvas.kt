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
}

/** Hides tldraw's default chrome; we provide our own minimal toolbar. */
private fun hiddenComponents(): dynamic {
    val c: dynamic = js("{}")
    c.Minimap = null
    c.NavigationPanel = null
    c.MainMenu = null
    c.HelpMenu = null
    c.Toolbar = null
    c.PageMenu = null
    c.StylePanel = null      // right-side color/style panel
    c.QuickActions = null    // left-side bar when a shape is selected
    c.HelperButtons = null   // "back to content" button
    c.ActionsMenu = null
    c.OnTheCanvas = HintLayer // hint cards, in page coordinates
    return c
}

/** Each drawing is a single page: disable tldraw's multi-page UI entirely. */
private fun singlePageOptions(): dynamic {
    val o: dynamic = js("{}")
    o.maxPages = 1
    o.camera = documentCameraOptions(PAGE_HEIGHT)
    return o
}

/**
 * Camera behaves like a vertical scrolling document:
 * - wheel pans (scrolls) instead of zooming
 * - the camera is constrained to a tall page; 'contain' pins the page while
 *   zoomed out below the fit zoom and keeps it inside the viewport when zoomed in
 *
 * `height` is the current page height — the page auto-grows as content is
 * added (see growBoundsToContent).
 */
private fun documentCameraOptions(height: Double): dynamic {
    val camera: dynamic = js("{}")
    camera.wheelBehavior = "pan"

    val constraints: dynamic = js("{}")
    val bounds: dynamic = js("{}")
    bounds.x = 0.0
    bounds.y = 0.0
    bounds.w = PAGE_WIDTH
    bounds.h = height
    constraints.bounds = bounds

    val origin: dynamic = js("{}")
    origin.x = 0.5
    origin.y = 0.0 // open at the top of the document, like a real document
    constraints.origin = origin

    constraints.behavior = "contain"
    constraints.initialZoom = "fit-x-100"

    val padding: dynamic = js("{}")
    padding.x = 32.0
    padding.y = 32.0
    constraints.padding = padding

    camera.constraints = constraints
    return camera
}

/** Dimensions of the document page (page-space bounds). */
private const val PAGE_WIDTH = 960.0
private const val PAGE_HEIGHT = 4800.0

/** Extra room below the lowest content when the page auto-grows. */
private const val PAGE_GROW_MARGIN = 800.0

val Canvas = FC<CanvasProps> { props ->
    hintDocumentId = props.documentId
    Tldraw {
        components = hiddenComponents()
        options = singlePageOptions()
        onMount = { editor ->
            props.onReady?.invoke(editor)

            // ---- auto-growing page bounds: grow to fit content, never shrink ----
            var pageHeight = PAGE_HEIGHT

            fun growBoundsToContent() {
                val bounds = runCatching { editor.getCurrentPageBounds() }.getOrNull() ?: return
                val needed = bounds.y + bounds.h + PAGE_GROW_MARGIN
                if (needed > pageHeight) {
                    pageHeight = needed
                    Console.warn("document page grew to height $needed")
                    runCatching { editor.setCameraOptions(documentCameraOptions(pageHeight)) }
                }
            }

            // grow as the user draws/edits (store listener filtered to user document
            // changes — includes stroke end, but also live updates while drawing)
            val filters: dynamic = js("{}")
            filters.source = "user"
            filters.scope = "document"
            editor.store.listen({ _ -> growBoundsToContent() }, filters)

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
                // camera placement is owned by the camera constraints
                // (initialZoom 'fit-x-100' + origin), not zoomToFit
                // grow the page if stored content extends below the initial height
                growBoundsToContent()

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
