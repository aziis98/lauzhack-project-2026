package com.lauzhack.web.tldraw

import com.lauzhack.shared.domain.Element
import com.lauzhack.shared.domain.ElementData
import com.lauzhack.shared.domain.Point
import com.lauzhack.shared.domain.Segment
import com.lauzhack.shared.domain.Style

/**
 * The ONLY tldraw-aware code in the project: converts tldraw store records to
 * the shared domain model and back. The server never sees anything tldraw-ish.
 */

private val geoTypes = setOf("rectangle", "ellipse", "triangle", "diamond", "cloud")

// ---------------------------------------------------------------
// tldraw record -> domain Element
// ---------------------------------------------------------------

fun TlRecord.toElementOrNull(): Element? {
    if (typeName != "shape") return null
    val shapeType = type ?: return null

    val data: ElementData = when (shapeType) {
        "draw" -> {
            val segments = (props.segments as? Array<*>)?.mapNotNull { seg ->
                val s = seg.asDynamic()
                val path = s.path as? String ?: return@mapNotNull null
                val dim = if ((s.dim as? Double) == 2.0) 2 else 3
                Segment(
                    points = decodeDrawPath(path, dim),
                    type = s.type as? String ?: "free",
                )
            } ?: emptyList()
            ElementData.Stroke(segments = segments, complete = props.isComplete == true)
        }
        "geo" -> ElementData.Geometry(w = dnum(props, "w", 100.0), h = dnum(props, "h", 100.0))
        "text" -> ElementData.Text(text = extractText(props), w = dnum(props, "w", 0.0))
        "note" -> ElementData.Text(text = extractText(props))
        "arrow" -> {
            val start: dynamic = props.start
            val end: dynamic = props.end
            if (start == null || end == null) return null
            // Arrows bound to other shapes are persisted as free arrows at
            // their current endpoints (v5 start/end are plain points).
            ElementData.Arrow(
                bend = dnum(props, "bend", 0.0),
                start = Point(start.x as Double, start.y as Double),
                end = Point(end.x as Double, end.y as Double),
            )
        }
        else -> return null // unknown/unsupported shape types are simply not persisted
    }

    return Element(
        id = id,
        type = when (shapeType) {
            "draw" -> "stroke"
            "geo" -> dstr(props, "geo") ?: "rectangle"
            else -> shapeType
        },
        x = x ?: 0.0,
        y = y ?: 0.0,
        rotation = rotation ?: 0.0,
        zIndex = index ?: "a1",
        parentId = parentId?.takeIf { !it.startsWith("page:") },
        style = styleFrom(props),
        data = data,
    )
}

// ---------------------------------------------------------------
// domain Element -> tldraw shape partial (for editor.createShapes)
// ---------------------------------------------------------------

fun Element.toShapePartial(pageId: String): dynamic? {
    val shape: dynamic = js("{}")
    shape.id = if (id.startsWith("shape:")) id else "shape:$id"
    shape.parentId = parentId ?: pageId
    shape.x = x
    shape.y = y
    shape.rotation = rotation
    shape.index = zIndex

    val p: dynamic = js("{}")

    style?.let { st ->
        st.color?.let { p.color = it }
        st.fill?.let { p.fill = it }
        st.dash?.let { p.dash = it }
        st.size?.let { p.size = it }
    }

    when (type) {
        "stroke" -> {
            val d = data as ElementData.Stroke
            if (d.segments.all { it.points.size < 2 }) return null
            shape.type = "draw"
            p.segments = d.segments.map { seg ->
                val s: dynamic = js("{}")
                s.type = seg.type
                s.path = encodeDrawPath(seg.points)
                s.dim = 2
                s
            }.toTypedArray()
            p.isComplete = d.complete
        }
        in geoTypes -> {
            shape.type = "geo"
            val d = data as ElementData.Geometry
            p.geo = type
            p.w = d.w
            p.h = d.h
        }
        "text" -> {
            shape.type = "text"
            val d = data as ElementData.Text
            if (d.text.isEmpty()) return null
            p.richText = richTextFrom(d.text)
            p.w = d.w
        }
        "note" -> {
            shape.type = "note"
            val d = data as ElementData.Text
            if (d.text.isEmpty()) return null
            p.richText = richTextFrom(d.text)
        }
        "arrow" -> {
            shape.type = "arrow"
            val d = data as ElementData.Arrow
            p.bend = d.bend
            p.start = vecModel(d.start)
            p.end = vecModel(d.end)
        }
        else -> return null // unknown widget types from other clients: skipped by this frontend
    }

    shape.props = p
    return shape
}

// ---------------------------------------------------------------
// helpers
// ---------------------------------------------------------------

private fun vecModel(point: Point): dynamic {
    val v: dynamic = js("{}")
    v.x = point.x
    v.y = point.y
    return v
}

private fun styleFrom(props: dynamic): Style? {
    val style = Style(
        color = dstr(props, "color"),
        fill = dstr(props, "fill"),
        dash = dstr(props, "dash"),
        size = dstr(props, "size"),
    )
    return if (style == Style()) null else style
}

/** Extracts plain text from tldraw's ProseMirror rich text structure. */
private fun extractText(props: dynamic): String {
    val richText: dynamic = props.richText
    if (richText == null) return (props.text as? String) ?: "" // older schema fallback
    val blocks = richText.content as? Array<*> ?: return ""
    val sb = StringBuilder()
    for (block in blocks) {
        val leaves = block.asDynamic().content as? Array<*> ?: continue
        for (leaf in leaves) {
            val text = leaf.asDynamic().text
            if (text != null) sb.append(text.toString())
        }
    }
    return sb.toString()
}

/** Builds a minimal ProseMirror doc for tldraw's richText prop. */
private fun richTextFrom(text: String): dynamic {
    val doc: dynamic = js("{}")
    doc.type = "doc"
    if (text.isEmpty()) {
        doc.content = arrayOf<dynamic>()
        return doc
    }
    val leaf: dynamic = js("{}")
    leaf.type = "text"
    leaf.text = text
    val paragraph: dynamic = js("{}")
    paragraph.type = "paragraph"
    paragraph.content = arrayOf(leaf)
    doc.content = arrayOf(paragraph)
    return doc
}

private fun dnum(props: dynamic, key: String, default: Double): Double {
    val value = props[key]
    return when (value) {
        is Number -> value.toDouble()
        else -> default
    }
}

private fun dstr(props: dynamic, key: String): String? = props[key] as? String
