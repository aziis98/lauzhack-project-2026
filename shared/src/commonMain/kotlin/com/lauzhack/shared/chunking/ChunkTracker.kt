package com.lauzhack.shared.chunking

import com.lauzhack.shared.domain.Element

/**
 * Keeps track of the chunks on one page across successive edits and reports,
 * deterministically, which ones were added, modified or removed.
 *
 * Identity: after re-clustering, each new cluster inherits the id of the
 * previous chunk it shares the most strokes with (each old id can be
 * inherited at most once). So:
 *  - writing more next to an existing block → same id, *modified*;
 *  - two blocks merged by a bridging stroke → the larger one keeps its id
 *    (modified), the other one is *removed*;
 *  - a block split by erasing → the larger part keeps the id, the rest is *added*;
 *  - moving/redrawing a stroke → same id, different hash → *modified*.
 *
 * A chunk is reported as modified only when its [Chunk.contentHash] changed,
 * so untouched chunks are never re-processed.
 *
 * Not thread-safe: callers serialize access (one tracker per document).
 */
class ChunkTracker(
    private val clusterer: StrokeClusterer = StrokeClusterer(),
) {
    private var chunks: Map<String, Chunk> = emptyMap()
    private var nextId = 1

    /** Current layout of the page: every chunk with its coordinates, in reading order. */
    fun snapshot(): List<Chunk> = chunks.values.sortedWith(chunkReadingOrder)

    /** Re-clusters the page from its full element list and returns the diff. */
    fun update(elements: List<Element>): ChunkUpdate =
        updateFromStrokes(elements.mapNotNull { it.toStrokeShapeOrNull() })

    fun updateFromStrokes(strokes: List<StrokeShape>): ChunkUpdate {
        val clusters = clusterer.cluster(strokes)

        // stroke id -> previous chunk id
        val previousOwner = HashMap<String, String>()
        for (chunk in chunks.values) for (sid in chunk.strokeIds) previousOwner[sid] = chunk.id

        // Candidate (cluster, old chunk, shared strokes), best matches first.
        data class Candidate(val cluster: Int, val oldId: String, val overlap: Int)
        val candidates = clusters.flatMapIndexed { idx, cluster ->
            cluster.strokeIds.mapNotNull { previousOwner[it] }
                .groupingBy { it }.eachCount()
                .map { (oldId, count) -> Candidate(idx, oldId, count) }
        }.sortedWith(compareByDescending<Candidate> { it.overlap }.thenBy { it.oldId }.thenBy { it.cluster })

        val assigned = arrayOfNulls<String>(clusters.size)
        val claimed = HashSet<String>()
        for (c in candidates) {
            if (assigned[c.cluster] == null && c.oldId !in claimed) {
                assigned[c.cluster] = c.oldId
                claimed += c.oldId
            }
        }

        val added = mutableListOf<Chunk>()
        val modified = mutableListOf<Chunk>()
        val next = LinkedHashMap<String, Chunk>()
        clusters.forEachIndexed { idx, cluster ->
            val oldId = assigned[idx]
            val old = oldId?.let { chunks[it] }
            val chunk = when {
                old == null -> Chunk(
                    id = "chunk-${nextId++}",
                    strokeIds = cluster.strokeIds,
                    bounds = cluster.bounds,
                    contentHash = cluster.contentHash,
                ).also { added += it }
                old.contentHash == cluster.contentHash -> old
                else -> old.copy(
                    strokeIds = cluster.strokeIds,
                    bounds = cluster.bounds,
                    contentHash = cluster.contentHash,
                    version = old.version + 1,
                ).also { modified += it }
            }
            next[chunk.id] = chunk
        }
        val removed = chunks.keys.filter { it !in next }.sorted()

        chunks = next
        return ChunkUpdate(
            added = added.sortedWith(chunkReadingOrder),
            modified = modified.sortedWith(chunkReadingOrder),
            removed = removed,
        )
    }

    companion object {
        val chunkReadingOrder: Comparator<Chunk> =
            compareBy<Chunk>({ it.bounds.minY }, { it.bounds.minX }, { it.id })
    }
}
