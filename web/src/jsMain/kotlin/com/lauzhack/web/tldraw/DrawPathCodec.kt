package com.lauzhack.web.tldraw

/**
 * Codec for tldraw v5 draw-shape segment paths (verified against tldraw's
 * b64Vecs source):
 *
 * - dim = 2: [Float32 x0][Float32 y0] then per point [Float16 dx][Float16 dy]
 * - dim = 3: [Float32 x0][Float32 y0][Float32 z0] then per point [Float16 dx][Float16 dy][Float16 dz]
 * - little-endian, base64 encoded, deltas relative to the previous point.
 *
 * We decode to plain flattened [x, y, x, y, ...] points so the generic domain
 * model (and future Android/iOS clients) never sees this encoding.
 *
 * Note: indexed access must go through `dynamic` (or DataView methods) —
 * Kotlin `operator fun get/set` on external classes compiles to .get()/.set()
 * method calls, which collide with TypedArray's native set(array, offset).
 */

private external fun atob(data: String): String

private external fun btoa(data: String): String

private external class Uint8Array(length: Int) {
    val length: Int
    val buffer: dynamic
}

private external class DataView(buffer: dynamic) {
    fun getFloat32(byteOffset: Int, littleEndian: Boolean): Double
    fun setFloat32(byteOffset: Int, value: Double, littleEndian: Boolean)
    fun getFloat16(byteOffset: Int, littleEndian: Boolean): Double
    fun setFloat16(byteOffset: Int, value: Double, littleEndian: Boolean)
}

/** Decodes a draw-segment path into flattened [x, y, x, y, ...] points. */
internal fun decodeDrawPath(path: String, dim: Int): List<Double> {
    if (path.isEmpty()) return emptyList()
    val binary = atob(path)
    val len: Int = binary.asDynamic().length
    val bytes: dynamic = Uint8Array(len)
    for (i in 0 until len) {
        bytes[i] = binary.asDynamic().charCodeAt(i)
    }
    val view = DataView(bytes.buffer)

    val dim3 = dim != 2
    val firstBytes = if (dim3) 12 else 8
    val stepBytes = if (dim3) 6 else 4
    if (len < firstBytes) return emptyList()

    val points = ArrayList<Double>()
    var x = view.getFloat32(0, true)
    var y = view.getFloat32(4, true)
    points += x
    points += y

    var offset = firstBytes
    while (offset + stepBytes <= len) {
        x += view.getFloat16(offset, true)
        y += view.getFloat16(offset + 2, true)
        points += x
        points += y
        offset += stepBytes
    }
    return points
}

/** Encodes flattened [x, y, x, y, ...] points into a draw-segment path (dim = 2). */
internal fun encodeDrawPath(points: List<Double>): String {
    val n = points.size / 2
    if (n == 0) return ""
    val byteLength = 8 + (n - 1) * 4
    val bytes: dynamic = Uint8Array(byteLength)
    val view = DataView(bytes.buffer)
    view.setFloat32(0, points[0], true)
    view.setFloat32(4, points[1], true)
    for (i in 1 until n) {
        val offset = 8 + (i - 1) * 4
        view.setFloat16(offset, points[2 * i] - points[2 * (i - 1)], true)
        view.setFloat16(offset + 2, points[2 * i + 1] - points[2 * (i - 1) + 1], true)
    }
    val sb = StringBuilder()
    for (i in 0 until byteLength) {
        sb.append((bytes[i] as Int).toChar())
    }
    return btoa(sb.toString())
}
