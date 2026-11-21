package com.lauzhack.shared.chunking

import com.lauzhack.shared.domain.Element
import com.lauzhack.shared.domain.ElementData
import com.lauzhack.shared.domain.Segment
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A short horizontal stroke 30 units wide and 20 tall, placed at (x, y). */
private fun stroke(id: String, x: Double, y: Double, w: Double = 30.0, h: Double = 20.0) = Element(
    id = id,
    type = "stroke",
    x = x,
    y = y,
    data = ElementData.Stroke(listOf(Segment(listOf(0.0, 0.0, w, h)))),
)

class StrokeGeometryTest {
    @Test
    fun boundsAreInPageSpaceAndRotated() {
        val e = stroke("s", 100.0, 100.0, w = 10.0, h = 0.0).copy(rotation = PI / 2)
        val b = e.toStrokeShapeOrNull()!!.bounds
        val pad = penWidth(null) / 2
        // Rotated by 90°: a horizontal 10-unit line becomes vertical going down.
        assertEquals(100.0 - pad, b.minX, 1e-9)
        assertEquals(100.0 + pad, b.maxX, 1e-9)
        assertEquals(110.0 + pad, b.maxY, 1e-9)
    }

    @Test
    fun hashIsStable() {
        assertEquals(fnv1a64("abc"), fnv1a64("abc"))
        assertTrue(fnv1a64("abc") != fnv1a64("abd"))
        assertEquals("cbf29ce484222325", fnv1a64(""))
    }
}

class StrokeClustererTest {
    private val clusterer = StrokeClusterer(horizontalGap = 40.0, verticalGap = 20.0)

    private fun clusterIds(vararg elements: Element) =
        clusterer.cluster(elements.mapNotNull { it.toStrokeShapeOrNull() }).map { it.strokeIds }

    @Test
    fun nearbyStrokesFormOneBlockAndFarOnesAnother() {
        val result = clusterIds(
            stroke("a", 0.0, 0.0), stroke("b", 50.0, 0.0), stroke("c", 100.0, 5.0), // one line
            stroke("d", 0.0, 300.0), // far below
        )
        assertEquals(listOf(listOf("a", "b", "c"), listOf("d")), result)
    }

    @Test
    fun resultDoesNotDependOnInputOrder() {
        val els = listOf(stroke("a", 0.0, 0.0), stroke("b", 50.0, 0.0), stroke("c", 500.0, 0.0), stroke("d", 0.0, 400.0))
        val expected = clusterIds(*els.toTypedArray())
        repeat(5) { assertEquals(expected, clusterIds(*els.shuffled().toTypedArray())) }
    }

    @Test
    fun chainedStrokesAreTransitivelyMerged() {
        // a–b and b–c are close, a–c are not: single linkage still gives one block.
        assertEquals(1, clusterIds(stroke("a", 0.0, 0.0), stroke("b", 60.0, 0.0), stroke("c", 120.0, 0.0)).size)
    }
}

class ChunkTrackerTest {
    private val tracker = ChunkTracker(StrokeClusterer(horizontalGap = 40.0, verticalGap = 20.0))

    @Test
    fun firstUpdateAddsEverything() {
        val u = tracker.update(listOf(stroke("a", 0.0, 0.0), stroke("b", 0.0, 300.0)))
        assertEquals(listOf("chunk-1", "chunk-2"), u.added.map { it.id })
        assertTrue(u.modified.isEmpty() && u.removed.isEmpty())
    }

    @Test
    fun unchangedPageProducesEmptyUpdate() {
        val page = listOf(stroke("a", 0.0, 0.0), stroke("b", 0.0, 300.0))
        tracker.update(page)
        val u = tracker.update(page.reversed())
        assertTrue(u.isEmpty)
    }

    @Test
    fun writingNextToABlockModifiesOnlyThatBlock() {
        val page = listOf(stroke("a", 0.0, 0.0), stroke("b", 0.0, 300.0))
        tracker.update(page)
        val u = tracker.update(page + stroke("c", 50.0, 0.0))
        assertEquals(listOf("chunk-1"), u.modified.map { it.id })
        assertEquals(2, u.modified.single().version)
        assertEquals(listOf("a", "c"), u.modified.single().strokeIds)
        assertTrue(u.added.isEmpty() && u.removed.isEmpty())
    }

    @Test
    fun movingAStrokeModifiesItsBlock() {
        tracker.update(listOf(stroke("a", 0.0, 0.0), stroke("b", 30.0, 0.0)))
        val u = tracker.update(listOf(stroke("a", 0.0, 0.0), stroke("b", 35.0, 0.0)))
        assertEquals(listOf("chunk-1"), u.modified.map { it.id })
    }

    @Test
    fun newIsolatedWritingAddsAChunk() {
        tracker.update(listOf(stroke("a", 0.0, 0.0)))
        val u = tracker.update(listOf(stroke("a", 0.0, 0.0), stroke("z", 0.0, 500.0)))
        assertEquals(listOf("chunk-2"), u.added.map { it.id })
        assertTrue(u.modified.isEmpty())
    }

    @Test
    fun bridgingStrokeMergesBlocksAndLargerOneKeepsId() {
        val left = listOf(stroke("a", 0.0, 0.0), stroke("b", 40.0, 0.0))   // chunk-1 (2 strokes)
        val right = listOf(stroke("c", 200.0, 0.0))                        // chunk-2 (1 stroke)
        tracker.update(left + right)
        val u = tracker.update(left + right + stroke("bridge", 110.0, 0.0, w = 60.0))
        assertEquals(listOf("chunk-1"), u.modified.map { it.id })
        assertEquals(listOf("chunk-2"), u.removed)
        assertTrue(u.added.isEmpty())
    }

    @Test
    fun erasingASplitsBlockAndTheRestIsAdded() {
        val page = listOf(
            stroke("a", 0.0, 0.0), stroke("b", 40.0, 0.0),
            stroke("bridge", 110.0, 0.0, w = 60.0),
            stroke("c", 200.0, 0.0),
        )
        tracker.update(page)
        val u = tracker.update(page.filter { it.id != "bridge" })
        assertEquals(listOf("chunk-1"), u.modified.map { it.id })
        assertEquals(listOf("a", "b"), u.modified.single().strokeIds)
        assertEquals(listOf(listOf("c")), u.added.map { it.strokeIds })
    }

    @Test
    fun erasingAWholeBlockRemovesIt() {
        tracker.update(listOf(stroke("a", 0.0, 0.0), stroke("b", 0.0, 300.0)))
        val u = tracker.update(listOf(stroke("a", 0.0, 0.0)))
        assertEquals(listOf("chunk-2"), u.removed)
        assertEquals(listOf("chunk-1"), tracker.snapshot().map { it.id })
    }

    @Test
    fun nonStrokeElementsAreIgnored() {
        val text = Element(id = "t", type = "text", data = ElementData.Text("hello"))
        val u = tracker.update(listOf(text))
        assertTrue(u.isEmpty)
    }
}
