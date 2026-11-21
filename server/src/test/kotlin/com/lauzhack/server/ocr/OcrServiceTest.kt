package com.lauzhack.server.ocr

import com.lauzhack.server.chunking.ChunkEvent
import com.lauzhack.shared.chunking.BoundingBox
import com.lauzhack.shared.chunking.ChunkTracker
import com.lauzhack.shared.domain.Element
import com.lauzhack.shared.domain.ElementData
import com.lauzhack.shared.domain.Segment
import com.lauzhack.shared.ocr.ChunkKind
import com.lauzhack.shared.ocr.ChunkTranscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun stroke(id: String, x: Double, y: Double) = Element(
    id = id, type = "stroke", x = x, y = y,
    data = ElementData.Stroke(listOf(Segment(listOf(0.0, 0.0, 30.0, 20.0)))),
)

class ChunkRasterizerTest {
    @Test
    fun rendersInkInsideAPaddedImage() {
        val png = ChunkRasterizer(preferredScale = 2.0, paddingPx = 10)
            .render(BoundingBox(0.0, 0.0, 30.0, 20.0), listOf(stroke("a", 0.0, 0.0)))
        val img = ImageIO.read(ByteArrayInputStream(png))
        assertEquals(30 * 2 + 20, img.width)
        assertEquals(20 * 2 + 20, img.height)
        val dark = (0 until img.width).sumOf { x -> (0 until img.height).count { y -> (img.getRGB(x, y) and 0xff) < 128 } }
        assertTrue(dark > 50, "expected ink pixels, got $dark")
        assertTrue((img.getRGB(0, 0) and 0xff) > 250, "corner should be white")
    }

    @Test
    fun hugeChunksAreScaledDownToMaxSide() {
        val png = ChunkRasterizer(maxSidePx = 500, paddingPx = 0)
            .render(BoundingBox(0.0, 0.0, 5000.0, 100.0), listOf(stroke("a", 0.0, 0.0)))
        assertEquals(500, ImageIO.read(ByteArrayInputStream(png)).width)
    }
}

/** Polls [condition] until true; fails after [timeoutMs]. Cold JVMs make Java2D/ImageIO slow on first use. */
private suspend fun awaitUntil(timeoutMs: Long = 5000, condition: suspend () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        check(System.currentTimeMillis() < end) { "condition not met within ${timeoutMs}ms" }
        delay(20)
    }
}

class OcrServiceTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val calls = AtomicInteger()
    private val recognizer = ChunkRecognizer { _, _ -> calls.incrementAndGet(); ChunkTranscription(ChunkKind.MATH, "x=1") }
    private val tracker = ChunkTracker()

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun event(vararg page: Element): ChunkEvent {
        val update = tracker.update(page.toList())
        return ChunkEvent("doc", update, page.associateBy { it.id })
    }

    private fun service(rec: ChunkRecognizer? = recognizer) = OcrService(rec, scope, debounceMillis = 50)

    private fun CoroutineScope.record(ocr: OcrService): MutableList<TranscriptionEvent> {
        val events = Collections.synchronizedList(mutableListOf<TranscriptionEvent>())
        launch(start = CoroutineStart.UNDISPATCHED) { ocr.events.collect { events += it } }
        return events
    }

    @Test
    fun newChunkIsPublishedPendingThenTranscribed() = runBlocking {
        val ocr = service()
        val events = scope.record(ocr)
        ocr.handle(event(stroke("a", 0.0, 0.0)))
        awaitUntil { events.count { it is TranscriptionEvent.Updated } == 2 }

        val updates = events.filterIsInstance<TranscriptionEvent.Updated>()
        assertEquals(2, updates.size)
        assertNull(updates[0].chunk.transcription)
        assertEquals("x=1", updates[1].chunk.transcription?.content)
        assertEquals(1, calls.get())
        assertEquals("x=1", ocr.snapshot("doc").single().transcription?.content)
    }

    @Test
    fun rapidEditsAreDebouncedToOneCallForTheLatestVersion() = runBlocking {
        val ocr = service()
        val events = scope.record(ocr)
        ocr.handle(event(stroke("a", 0.0, 0.0)))
        ocr.handle(event(stroke("a", 0.0, 0.0), stroke("b", 40.0, 0.0)))
        awaitUntil { calls.get() >= 1 }; delay(200) // let any extra (wrong) call happen

        assertEquals(1, calls.get())
        val done = events.filterIsInstance<TranscriptionEvent.Updated>().filter { it.chunk.transcription != null }
        assertEquals(listOf(2), done.map { it.chunk.chunk.version })
    }

    @Test
    fun identicalContentIsServedFromCache() = runBlocking {
        val ocr = service()
        ocr.handle(event(stroke("a", 0.0, 0.0)))
        awaitUntil { calls.get() >= 1 }
        ocr.handle(event(stroke("a", 0.0, 0.0), stroke("b", 40.0, 0.0))) // modify…
        ocr.handle(event(stroke("a", 0.0, 0.0)))                         // …and undo before OCR runs
        delay(300); awaitUntil { ocr.snapshot("doc").singleOrNull()?.transcription != null }
        assertEquals(1, calls.get())
        assertEquals("x=1", ocr.snapshot("doc").single().transcription?.content)
    }

    @Test
    fun removedChunksArePublishedAndForgotten() = runBlocking {
        val ocr = service()
        val events = scope.record(ocr)
        ocr.handle(event(stroke("a", 0.0, 0.0)))
        ocr.handle(event())
        delay(200)
        assertTrue(events.any { it is TranscriptionEvent.Removed && it.chunkId == "chunk-1" })
        assertTrue(ocr.snapshot("doc").isEmpty())
        assertEquals(0, calls.get()) // removed before the debounce elapsed
    }

    @Test
    fun withoutRecognizerChunksStayPending() = runBlocking {
        val ocr = service(rec = null)
        ocr.handle(event(stroke("a", 0.0, 0.0)))
        delay(200)
        assertNull(ocr.snapshot("doc").single().transcription)
    }

    @Test
    fun failuresAreLoggedAndLeaveTheChunkPending() = runBlocking {
        val ocr = service(rec = { _, _ -> error("model down") })
        ocr.handle(event(stroke("a", 0.0, 0.0)))
        delay(300) // the recognizer throws right after the debounce
        assertNull(ocr.snapshot("doc").single().transcription)
    }
}
