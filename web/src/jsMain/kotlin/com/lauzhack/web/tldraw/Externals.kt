@file:JsModule("tldraw")

package com.lauzhack.web.tldraw

import react.ComponentType
import react.Props

/**
 * Minimal tldraw externals: we only declare the surface we actually use.
 * All record/props internals are read via `dynamic` at the edges.
 */

/** The <Tldraw> React component (named export of the tldraw package). */
@JsName("Tldraw")
external val Tldraw: ComponentType<TldrawProps>

external interface TldrawProps : Props {
    var onMount: ((editor: Editor) -> Unit)?
    var components: dynamic?
    var options: dynamic?
}

external interface Editor {
    val store: TlStore
    fun getCurrentPageId(): String
    fun getCurrentPageShapes(): Array<TlRecord>
    fun createShapes(shapes: Array<dynamic>)
    fun deleteShapes(ids: Array<String>)
    fun getShape(id: String): TlRecord?
    fun setCurrentTool(id: String)
    fun zoomIn()
    fun zoomOut()
    fun resetZoom()
    fun getCurrentPageBounds(): Box?
    fun setCameraOptions(opts: dynamic)
}

external interface TlStore {
    fun listen(onHistory: (entry: dynamic) -> Unit, filters: dynamic): () -> Unit
}

/** An axis-aligned box (page space). */
external interface Box {
    val x: Double
    val y: Double
    val w: Double
    val h: Double
}

/** A tldraw store record (shapes, pages, bindings, ...). */
external interface TlRecord {
    val id: String
    val typeName: String
    val type: String?
    val parentId: String?
    val index: String?
    val x: Double?
    val y: Double?
    val rotation: Double?
    val props: dynamic
}
