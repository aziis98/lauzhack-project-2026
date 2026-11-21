package com.lauzhack.shared.chunking

/**
 * Groups strokes into macro-blocks purely by spatial proximity.
 *
 * Two strokes belong to the same block when their bounding boxes are at most
 * [horizontalGap] apart horizontally AND at most [verticalGap] apart
 * vertically; blocks are the connected components of that relation
 * (single-linkage). The result depends only on the set of strokes, never on
 * their order or on when they were drawn, so it is fully deterministic.
 *
 * Gaps are absolute page units (not relative to the median stroke size) on
 * purpose: adding strokes in one corner of the page must never change how a
 * distant, untouched block is clustered.
 */
class StrokeClusterer(
    val horizontalGap: Double = 40.0,
    val verticalGap: Double = 20.0,
) {
    /** A cluster before identity assignment. */
    data class Cluster(
        val strokes: List<StrokeShape>, // sorted by stroke id
        val bounds: BoundingBox,
        val contentHash: String,
    ) {
        val strokeIds: List<String> get() = strokes.map { it.id }
    }

    fun cluster(strokes: List<StrokeShape>): List<Cluster> {
        if (strokes.isEmpty()) return emptyList()
        val sorted = strokes.sortedBy { it.id }
        val uf = UnionFind(sorted.size)

        // Sweep on minX: boxes after j start even further right, so once one
        // is beyond reach of box i, all the following ones are too.
        val order = sorted.indices.sortedBy { sorted[it].bounds.minX }
        for (a in order.indices) {
            val i = order[a]
            val bi = sorted[i].bounds
            for (b in a + 1 until order.size) {
                val j = order[b]
                val bj = sorted[j].bounds
                if (bj.minX - bi.maxX > horizontalGap) break
                if (bi.gapX(bj) <= horizontalGap && bi.gapY(bj) <= verticalGap) uf.union(i, j)
            }
        }

        return sorted.indices
            .groupBy { uf.find(it) }
            .values
            .map { members ->
                val group = members.map { sorted[it] } // stays id-sorted
                Cluster(
                    strokes = group,
                    bounds = group.map { it.bounds }.reduce(BoundingBox::union),
                    contentHash = fnv1a64(group.joinToString("\n") { it.fingerprint }),
                )
            }
            .sortedWith(readingOrder)
    }

    private class UnionFind(n: Int) {
        private val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val n = parent[c]; parent[c] = r; c = n }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) { if (ra < rb) parent[rb] = ra else parent[ra] = rb }
        }
    }

    companion object {
        /** Top-to-bottom, then left-to-right, then by first stroke id as a final tie-break. */
        val readingOrder: Comparator<Cluster> =
            compareBy<Cluster>({ it.bounds.minY }, { it.bounds.minX }, { it.strokes.first().id })
    }
}
