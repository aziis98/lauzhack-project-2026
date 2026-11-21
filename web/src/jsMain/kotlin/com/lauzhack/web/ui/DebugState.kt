package com.lauzhack.web.ui

import com.lauzhack.shared.debug.DebugPayload

/**
 * Shared state between the debug drawer (polls the /debug endpoint) and the
 * canvas overlay (draws chunk/fragment/bbox boxes in page coordinates).
 * The drawer polls, writes here and notifies; the overlay listens and re-renders.
 */
internal object DebugState {
    var payload: DebugPayload? = null

    var drawerOpen: Boolean = false

    var showChunkBoxes: Boolean = true
    var showFragmentBoxes: Boolean = false
    var showModelBoxes: Boolean = true

    private val listeners = ArrayList<() -> Unit>()

    fun notifyChanged() {
        listeners.toList().forEach { it() }
    }

    fun listen(listener: () -> Unit) {
        listeners.add(listener)
    }
}
