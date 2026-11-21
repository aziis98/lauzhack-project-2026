@file:JsModule("three/addons/controls/OrbitControls.js")

package com.lauzhack.web.three

/** three's addons path maps to examples/jsm (see three's package exports). */
@JsName("OrbitControls")
external open class OrbitControls(camera: dynamic, domElement: dynamic) {
    val target: Vector3
    var enableDamping: Boolean
    var dampingFactor: Double
    var minDistance: Double
    var maxDistance: Double
    fun update(): Boolean
    fun dispose()
}
