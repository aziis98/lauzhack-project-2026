package com.lauzhack.web.ui

import com.lauzhack.shared.debug.DebugPayload
import kotlinx.coroutines.awaitCancellation
import react.useEffectOnce
import react.useState

/** Shared debug data for the inspector and tldraw's canvas slot. */
internal object DebugState {
    var documentId: String? = null
    var payload: DebugPayload? = null
    var error: String? = null

    fun reset(documentId: String?) {
        this.documentId = documentId
        payload = null
        error = null
        notifyChanged()
    }

    var drawerOpen: Boolean = false

    var showChunkBoxes: Boolean = true
    var showFragmentBoxes: Boolean = false
    var showModelBoxes: Boolean = true

    private val listeners = ArrayList<() -> Unit>()

    fun notifyChanged() {
        listeners.toList().forEach { it() }
    }

    fun listen(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }
}

/** Subscribe after commit and remove the listener when the component unmounts. */
internal fun useDebugUpdates() {
    val (_, setVersion) = useState(0)
    useEffectOnce {
        val unsubscribe = DebugState.listen { setVersion { it + 1 } }
        // Catch updates published before this effect subscribed.
        setVersion { it + 1 }
        try {
            awaitCancellation()
        } finally {
            unsubscribe()
        }
    }
}
