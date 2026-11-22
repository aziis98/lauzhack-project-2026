package com.lauzhack.shared.ocr

import kotlinx.serialization.Serializable

/**
 * One connected handwriting blob ("symbol fragment") detected by server-side
 * image segmentation, in image pixel space of the chunk rasterization.
 *
 * Fragments within the dilation radius are merged (e.g. the strokes of a
 * dotted i or an equals sign); small specks are filtered out. The tight
 * box is computed on the original ink, so it is not inflated by dilation.
 */
@Serializable
data class SymbolFragment(
    val id: Int,
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
    /** Pixels of original ink inside the box. */
    val area: Int,
) {
    val width: Int get() = maxX - minX
    val height: Int get() = maxY - minY
}

/**
 * One `\bbox[minx,miny,maxx,maxy]{symbol}` marker parsed out of an augmented
 * transcription, in image pixel space of the chunk rasterization.
 */
@Serializable
data class BboxSymbol(
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
    val symbol: String,
)

/** Parses all `\bbox[...]{...}` markers out of an augmented transcription. */
fun String.parseBboxes(): List<BboxSymbol> {
    val marker = "\\bbox["
    val out = ArrayList<BboxSymbol>()
    var i = 0
    while (i < length) {
        val at = indexOf(marker, i)
        if (at == -1) break
        val closeBracket = indexOf(']', at + marker.length)
        if (closeBracket == -1) break
        val coords = substring(at + marker.length, closeBracket).split(',').mapNotNull { it.trim().toIntOrNull() }
        val open = closeBracket + 1
        if (coords.size != 4 || open >= length || this[open] != '{') {
            i = at + 1
            continue
        }
        var k = open + 1
        var depth = 1
        while (k < length && depth > 0) {
            when (this[k]) {
                '{' -> depth++
                '}' -> depth--
            }
            if (depth == 0) break
            k++
        }
        out += BboxSymbol(coords[0], coords[1], coords[2], coords[3], substring(open + 1, k))
        i = (k + 1).coerceAtMost(length)
    }
    return out
}

/**
 * Removes `\bbox[minx,miny,maxx,maxy]{...}` markers from an augmented
 * transcription, keeping the inner content — the plain LaTeX/text the hint
 * agent (and humans) want to see. Brace-aware: `\bbox[...]{\frac{1}{3}}`
 * keeps `\frac{1}{3}`.
 */
fun String.stripBboxes(): String {
    val marker = "\\bbox["
    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        val at = indexOf(marker, i)
        if (at == -1) {
            out.append(substring(i))
            break
        }
        out.append(substring(i, at))
        val closeBracket = indexOf(']', at)
        if (closeBracket == -1) {
            out.append(substring(at))
            break
        }
        val open = closeBracket + 1
        if (open >= length || this[open] != '{') {
            out.append(substring(at))
            break
        }
        var k = open + 1
        var depth = 1
        while (k < length && depth > 0) {
            when (this[k]) {
                '{' -> depth++
                '}' -> depth--
            }
            if (depth == 0) break
            k++
        }
        out.append(this, open + 1, k)
        i = (k + 1).coerceAtMost(length)
    }
    return out.toString()
}
