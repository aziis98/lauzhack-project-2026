package com.lauzhack.shared.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Tool-agnostic drawing domain model.
 *
 * The structure is loosely based on tldraw's record model (flat element list,
 * opaque string ids, fractional z-indexing) so the web frontend can convert
 * losslessly, but the model itself knows nothing about tldraw. Android and iOS
 * clients (future) consume the same model over the same REST API.
 */

@Serializable
data class Document(
    val id: String,
    val name: String,
    val updatedAt: Long = 0L,
    /** Exercise statement in Markdown with `$…$` / `$$…$$` LaTeX math (KaTeX); empty for free drawings. */
    val exercise: String = "",
)

@Serializable
data class Style(
    val color: String? = null, // named palette, e.g. "black", "red", "light-blue"
    val fill: String? = null,  // "none" | "semi" | "solid"
    val dash: String? = null,  // "draw" | "solid" | "dotted" | "dashed"
    val size: String? = null,   // stroke width class: "s" | "m" | "l" | "xl"
)

@Serializable
data class Point(val x: Double, val y: Double)

@Serializable
data class Segment(
    val points: List<Double>, // flattened [x1, y1, x2, y2, ...]
    val type: String = "free", // "free" | "straight" | "edge"
)

/**
 * Type-specific element payload. Polymorphic via the "kind" discriminator.
 * New widget kinds for future app components = new subclasses, no DB migration.
 */
@Serializable
sealed interface ElementData {
    @Serializable
    @SerialName("stroke")
    data class Stroke(
        val segments: List<Segment> = emptyList(),
        val complete: Boolean = true,
    ) : ElementData

    @Serializable
    @SerialName("geometry")
    data class Geometry(
        val w: Double = 0.0,
        val h: Double = 0.0,
    ) : ElementData

    @Serializable
    @SerialName("text")
    data class Text(
        val text: String = "",
        val w: Double = 0.0,
        val h: Double = 0.0,
    ) : ElementData

    @Serializable
    @SerialName("arrow")
    data class Arrow(
        val bend: Double = 0.0,
        val start: Point = Point(0.0, 0.0),
        val end: Point = Point(0.0, 0.0),
    ) : ElementData
}

/**
 * A single drawing element on a canvas.
 * Ids follow the "shape:<uuid>" convention (tldraw-compatible by coincidence, not by necessity).
 */
@Serializable
data class Element(
    val id: String,
    val type: String, // stroke | rectangle | ellipse | triangle | diamond | cloud | text | note | arrow | <future widget types>
    val x: Double = 0.0,
    val y: Double = 0.0,
    val rotation: Double = 0.0,
    val zIndex: String = "a1", // fractional index string for z-ordering
    val parentId: String? = null, // reserved: page/frame/group container
    val style: Style? = null,
    val data: ElementData,
)
