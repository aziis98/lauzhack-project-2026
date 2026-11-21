@file:JsModule("three")

package com.lauzhack.web.three

import org.w3c.dom.HTMLCanvasElement

/**
 * Minimal three.js externals, in the project's "declare only the surface we
 * actually use" style. All options/params go through `dynamic`.
 */

@JsName("Scene")
external open class Scene {
    fun add(obj: dynamic): Scene
    var background: dynamic
}

@JsName("PerspectiveCamera")
external open class PerspectiveCamera(fov: Double, aspect: Double, near: Double, far: Double) {
    val position: Vector3
    fun lookAt(x: Double, y: Double, z: Double)
}

@JsName("Vector3")
external open class Vector3 {
    fun set(x: Double, y: Double, z: Double): Vector3
}

@JsName("WebGLRenderer")
external open class WebGLRenderer(params: dynamic) {
    fun setSize(width: Double, height: Double)
    fun setPixelRatio(ratio: Double)
    fun render(scene: Scene, camera: PerspectiveCamera)
    fun dispose()
    val domElement: HTMLCanvasElement
}

@JsName("BufferGeometry")
external open class BufferGeometry {
    fun setAttribute(name: String, attribute: dynamic): BufferGeometry
    fun setIndex(indices: dynamic): BufferGeometry
    fun computeVertexNormals()
}

@JsName("BufferAttribute")
external open class BufferAttribute(array: dynamic, itemSize: Int)

@JsName("Mesh")
external open class Mesh(geometry: dynamic, material: dynamic) {
    val position: Vector3
}

@JsName("Color")
external open class Color(hex: Int)

@JsName("MeshLambertMaterial")
external open class MeshLambertMaterial(params: dynamic)

@JsName("MeshBasicMaterial")
external open class MeshBasicMaterial(params: dynamic)

@JsName("AmbientLight")
external open class AmbientLight(color: Int, intensity: Double) {
    val position: Vector3
}

@JsName("DirectionalLight")
external open class DirectionalLight(color: Int, intensity: Double) {
    val position: Vector3
}

@JsName("SphereGeometry")
external open class SphereGeometry(radius: Double, widthSegments: Int, heightSegments: Int)

@JsName("GridHelper")
external open class GridHelper(size: Double, divisions: Int, colorCenterLine: Int, colorGrid: Int) {
    val position: Vector3
}

/** The colored x/y/z tripod, so the surface's orientation reads while orbiting. */
@JsName("AxesHelper")
external open class AxesHelper(size: Double)
