package com.lauzhack.shared.ocr

import kotlinx.serialization.Serializable

/**
 * One connected handwriting blob ("symbol fragment") detected by server-side
 * image segmentation, in image pixel space of the chunk rasterization.
 *
 * Fragments within the dilation radius are merged (e.g. the strokes of a
 * dotted ì, i or an equals sign); small specks are filtered out. The tight
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
