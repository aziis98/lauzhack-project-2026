package com.lauzhack.shared.chunking

import kotlinx.serialization.Serializable

/**
 * Axis-aligned bounding box in **page coordinates** (the canvas' own units,
 * independent of zoom/pan). y grows downwards.
 */
@Serializable
data class BoundingBox(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
) {
    val width: Double get() = maxX - minX
    val height: Double get() = maxY - minY

    fun union(other: BoundingBox) = BoundingBox(
        minOf(minX, other.minX),
        minOf(minY, other.minY),
        maxOf(maxX, other.maxX),
        maxOf(maxY, other.maxY),
    )

    /** Horizontal distance between the two boxes (0 when they overlap on x). */
    fun gapX(other: BoundingBox): Double = maxOf(0.0, maxOf(minX, other.minX) - minOf(maxX, other.maxX))

    /** Vertical distance between the two boxes (0 when they overlap on y). */
    fun gapY(other: BoundingBox): Double = maxOf(0.0, maxOf(minY, other.minY) - minOf(maxY, other.maxY))
}

/**
 * A macro-block of handwriting: a set of strokes that are spatially close to
 * each other. This is the unit that gets OCR'd and that the reasoning model
 * reasons about.
 *
 * @property id stable identity across edits (assigned by [ChunkTracker])
 * @property version incremented every time the chunk's content changes
 * @property contentHash deterministic hash of the strokes' geometry; a different
 *   hash means the chunk must be re-processed (re-OCR)
 */
@Serializable
data class Chunk(
    val id: String,
    val strokeIds: List<String>,
    val bounds: BoundingBox,
    val contentHash: String,
    val version: Int = 1,
)

/**
 * What changed on the page between two consecutive [ChunkTracker.update] calls.
 * This is the event stream the reasoning agent consumes.
 */
@Serializable
data class ChunkUpdate(
    val added: List<Chunk> = emptyList(),
    val modified: List<Chunk> = emptyList(),
    val removed: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = added.isEmpty() && modified.isEmpty() && removed.isEmpty()
}
