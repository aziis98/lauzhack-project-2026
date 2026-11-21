package com.lauzhack.shared.chunking

import com.lauzhack.shared.domain.Element
import com.lauzhack.shared.domain.ElementData
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/** A handwritten stroke reduced to what chunking needs: id, page-space bounds and a geometry fingerprint. */
data class StrokeShape(
    val id: String,
    val bounds: BoundingBox,
    /** Canonical, platform-independent description of the stroke's geometry (used for hashing). */
    val fingerprint: String,
)

/**
 * Converts a stroke [Element] to page space. Stroke points are local to the
 * element origin (x, y) and rotated around it by [Element.rotation] (radians).
 * Returns null for non-stroke elements or strokes without points.
 */
fun Element.toStrokeShapeOrNull(): StrokeShape? {
    val stroke = data as? ElementData.Stroke ?: return null
    val c = cos(rotation)
    val s = sin(rotation)

    var minX = Double.POSITIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY
    val fp = StringBuilder()
    fp.append(id).append('|').append(q(x)).append(',').append(q(y)).append(',').append(q(rotation))

    for (segment in stroke.segments) {
        fp.append('|').append(segment.type).append(':')
        val pts = segment.points
        var i = 0
        while (i + 1 < pts.size) {
            val lx = pts[i]
            val ly = pts[i + 1]
            val px = x + lx * c - ly * s
            val py = y + lx * s + ly * c
            if (px < minX) minX = px
            if (py < minY) minY = py
            if (px > maxX) maxX = px
            if (py > maxY) maxY = py
            fp.append(q(lx)).append(',').append(q(ly)).append(';')
            i += 2
        }
    }
    if (minX == Double.POSITIVE_INFINITY) return null

    // Pad by half the pen width so touching strokes really touch.
    val pad = penWidth(style?.size) / 2
    return StrokeShape(
        id = id,
        bounds = BoundingBox(minX - pad, minY - pad, maxX + pad, maxY + pad),
        fingerprint = fp.toString(),
    )
}

/**
 * The stroke's segments as page-space polylines, each flattened as
 * [x1, y1, x2, y2, ...]. Empty for non-stroke elements.
 */
fun Element.toPageSpacePolylines(): List<DoubleArray> {
    val stroke = data as? ElementData.Stroke ?: return emptyList()
    val c = cos(rotation)
    val s = sin(rotation)
    return stroke.segments.map { segment ->
        val pts = segment.points
        DoubleArray(pts.size - pts.size % 2) { k ->
            val i = k - k % 2
            val lx = pts[i]
            val ly = pts[i + 1]
            if (k % 2 == 0) x + lx * c - ly * s else y + lx * s + ly * c
        }
    }.filter { it.isNotEmpty() }
}

/** Approximate pen width for the size classes used by the web editor. */
fun penWidth(size: String?): Double = when (size) {
    "s" -> 2.0
    "m" -> 3.5
    "l" -> 5.0
    "xl" -> 10.0
    else -> 3.5
}

/** Quantizes to 1/100 page unit so the fingerprint is identical on JVM and JS. */
private fun q(v: Double): Long = (v * 100).roundToLong()

/** 64-bit FNV-1a, hex-encoded. Deterministic on every platform. */
internal fun fnv1a64(text: String): String {
    var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    val prime = 0x100000001b3L
    for (ch in text) {
        val code = ch.code
        hash = (hash xor (code and 0xff).toLong()) * prime
        hash = (hash xor ((code ushr 8) and 0xff).toLong()) * prime
    }
    return hash.toULong().toString(16).padStart(16, '0')
}
