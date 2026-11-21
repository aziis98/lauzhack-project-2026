package com.lauzhack.web.ui

import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML
import web.cssom.ClassName

/**
 * Pipeline boxes drawn on the page in page coordinates (pointer-events: none),
 * mounted inside tldraw's OnTheCanvas slot.
 */
val DebugLayer = FC<Props> {
    useDebugUpdates()
    if (DebugState.documentId != canvasDocumentId) return@FC
    val payload = DebugState.payload ?: return@FC

    ReactHTML.div {
        className = ClassName("dbg-layer")

        if (DebugState.showChunkBoxes) {
            for ((index, chunk) in payload.chunks.withIndex()) {
                val b = chunk.bounds
                ReactHTML.div {
                    className = ClassName("dbg-chunk-box c${index % 6}")
                    key = Key(chunk.id)
                    val css: dynamic = js("({})")
                    css.left = "${b.minX}px"
                    css.top = "${b.minY}px"
                    css.width = "${b.maxX - b.minX}px"
                    css.height = "${b.maxY - b.minY}px"
                    asDynamic().style = css
                    ReactHTML.span {
                        className = ClassName("dbg-chunk-label c${index % 6}")
                        +"${chunk.id} · ${chunk.strokeIds.size} strokes"
                    }
                }
            }
        }

        for (ocrChunk in payload.ocr.chunks) {
            if (DebugState.showFragmentBoxes) {
                for (box in ocrChunk.fragmentBoxes) {
                    ReactHTML.div {
                        className = ClassName("dbg-frag-box")
                        key = Key("${ocrChunk.chunkId}-frag-${box.label}-${box.minX.toInt()}")
                        val css: dynamic = js("({})")
                        css.left = "${box.minX}px"
                        css.top = "${box.minY}px"
                        css.width = "${box.maxX - box.minX}px"
                        css.height = "${box.maxY - box.minY}px"
                        asDynamic().style = css
                    }
                }
            }
            if (DebugState.showModelBoxes) {
                for (box in ocrChunk.modelBoxes) {
                    ReactHTML.div {
                        className = ClassName("dbg-model-box")
                        key = Key("${ocrChunk.chunkId}-model-${box.label}-${box.minX.toInt()}")
                        val css: dynamic = js("({})")
                        css.left = "${box.minX}px"
                        css.top = "${box.minY}px"
                        css.width = "${box.maxX - box.minX}px"
                        css.height = "${box.maxY - box.minY}px"
                        asDynamic().style = css
                        ReactHTML.span {
                            className = ClassName("dbg-model-label")
                            +box.label
                        }
                    }
                }
            }
        }
    }
}
