package com.lauzhack.server.hint

import com.lauzhack.server.ocr.TranscriptionEvent
import com.lauzhack.shared.chunking.BoundingBox
import com.lauzhack.shared.chunking.Chunk
import com.lauzhack.shared.domain.Point
import com.lauzhack.shared.hint.HintCard
import com.lauzhack.shared.hint.HintStatus
import com.lauzhack.shared.ocr.ChunkKind
import com.lauzhack.shared.ocr.ChunkTranscription
import com.lauzhack.shared.ocr.TranscribedChunk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun chunk(id: String, hash: String = id, y: Double = 0.0) =
    Chunk(id, listOf("s-$id"), BoundingBox(0.0, y, 100.0, y + 40.0), hash)

private fun tc(c: Chunk, kind: ChunkKind?, content: String = "") =
    TranscribedChunk(c, kind?.let { ChunkTranscription(it, content) })

/** The hint-card lifecycle the page agent drives (reserve / complete / fail / move / dismiss). */
class HintServiceTest {
    private val scope = CoroutineScope(SupervisorJob())

    /** The page the service sees when it asks for the transcription. */
    @Volatile
    private var page: List<TranscribedChunk> = emptyList()

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun service() = HintService(scope, { page }, clock = { System.nanoTime() })

    private val work = tc(chunk("chunk-1"), ChunkKind.MATH, "A(x)=2x\\sqrt{1-x^2}")

    @Test
    fun buttonHintIsAnchoredToTheLowestWork() = runBlocking {
        val hints = service()
        page = listOf(work)
        val hint = hints.reserveButtonHint("doc")
        assertEquals(null, hint.triggerChunkId)
        assertEquals(work.chunk.bounds, hint.anchor)
        assertEquals(HintStatus.THINKING, hint.status)
    }

    @Test
    fun definitionHintIsAnchoredToTheGivenBounds() = runBlocking {
        val hints = service()
        val anchor = BoundingBox(10.0, 20.0, 30.0, 40.0)
        val hint = hints.reserveDefinition("doc", anchor)
        assertEquals(anchor, hint.anchor)
        assertEquals(null, hint.triggerChunkId)
    }

    @Test
    fun circledQuestionMarkIsReservedOncePerContent() = runBlocking {
        val hints = service()
        val anchor = BoundingBox(0.0, 0.0, 100.0, 40.0)
        val first = assertNotNull(hints.request("doc", "chunk-2", "hash-a", anchor))
        // same content hash → not reserved again
        assertNull(hints.request("doc", "chunk-2", "hash-a", anchor))
        // different content hash → reserved again, replacing the old card
        val second = assertNotNull(hints.request("doc", "chunk-2", "hash-b", anchor))
        assertNotEquals(first, second)
        assertEquals(1, hints.hints("doc").size)
    }

    @Test
    fun completeFillsTheReservedHint() = runBlocking {
        val hints = service()
        val id = hints.request("doc", "chunk-2", "hash-a", BoundingBox(0.0, 0.0, 100.0, 40.0))!!
        hints.complete("doc", id, HintCard("Title", "Message"))
        val hint = hints.hints("doc").single()
        assertEquals(HintStatus.READY, hint.status)
        assertEquals("Title", hint.card?.title)
    }

    @Test
    fun failMarksTheReservedHint() = runBlocking {
        val hints = service()
        val id = hints.request("doc", "chunk-2", "hash-a", BoundingBox(0.0, 0.0, 100.0, 40.0))!!
        hints.fail("doc", id, "boom")
        assertEquals(HintStatus.FAILED, hints.hints("doc").single().status)
    }

    @Test
    fun moreMovesTheCardToHistoryAndBackToThinking() = runBlocking {
        val hints = service()
        val id = hints.request("doc", "chunk-2", "hash-a", BoundingBox(0.0, 0.0, 100.0, 40.0))!!
        hints.complete("doc", id, HintCard("First", "msg"))
        val (hint, fresh) = hints.reserveMore("doc", id)!!
        assertTrue(fresh)
        assertEquals(HintStatus.THINKING, hint.status)
        assertEquals(listOf("First"), hint.history.map { it.title })
    }

    @Test
    fun moveStoresThePosition() = runBlocking {
        val hints = service()
        val id = hints.request("doc", "chunk-2", "hash-a", BoundingBox(0.0, 0.0, 100.0, 40.0))!!
        hints.move("doc", id, Point(10.0, 20.0))
        assertEquals(Point(10.0, 20.0), hints.hints("doc").single().position)
    }

    @Test
    fun dismissRemovesTheHint() = runBlocking {
        val hints = service()
        val id = hints.request("doc", "chunk-2", "hash-a", BoundingBox(0.0, 0.0, 100.0, 40.0))!!
        assertTrue(hints.dismiss("doc", id))
        assertTrue(hints.hints("doc").isEmpty())
    }

    @Test
    fun erasingTheTriggerRemovesItsHint() = runBlocking {
        val hints = service()
        hints.request("doc", "chunk-2", "hash-a", BoundingBox(0.0, 0.0, 100.0, 40.0))
        hints.handle(TranscriptionEvent.Removed("doc", "chunk-2"))
        assertTrue(hints.hints("doc").isEmpty())
    }
}
