package com.lauzhack.server.hint

import com.lauzhack.server.ocr.TranscriptionEvent
import com.lauzhack.shared.chunking.BoundingBox
import com.lauzhack.shared.chunking.Chunk
import com.lauzhack.shared.hint.HintCard
import com.lauzhack.shared.hint.HintStatus
import com.lauzhack.shared.ocr.ChunkKind
import com.lauzhack.shared.ocr.ChunkTranscription
import com.lauzhack.shared.ocr.TranscribedChunk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun chunk(id: String, hash: String = id, y: Double = 0.0) =
    Chunk(id, listOf("s-$id"), BoundingBox(0.0, y, 100.0, y + 40.0), hash)

private fun tc(c: Chunk, kind: ChunkKind?, content: String = "") =
    TranscribedChunk(c, kind?.let { ChunkTranscription(it, content) })

class HintServiceTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val contexts = Collections.synchronizedList(mutableListOf<HintContext>())

    /** The page the service sees when it asks for the transcription. */
    @Volatile
    private var page: List<TranscribedChunk> = emptyList()

    private val generator = HintGenerator { ctx ->
        contexts += ctx
        HintCard("Hint ${contexts.size}", "msg")
    }

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun service(gen: HintGenerator? = generator) =
        HintService(gen, scope, { page }, settleTimeoutMillis = 300, settlePollMillis = 20, clock = { System.nanoTime() })

    private fun updated(t: TranscribedChunk) = TranscriptionEvent.Updated("doc", t)

    private val work = tc(chunk("chunk-1"), ChunkKind.MATH, "A(x)=2x\\sqrt{1-x^2}")
    private val circled = tc(chunk("chunk-2", y = 100.0), ChunkKind.HINT_REQUEST)

    @Test
    fun circledQuestionMarkProducesAHintWithPageContext() = runBlocking {
        val hints = service()
        page = listOf(work, circled)
        hints.handle(updated(circled))
        assertEquals(HintStatus.THINKING, hints.hints("doc").single().status)
        delay(200)

        val hint = hints.hints("doc").single()
        assertEquals(HintStatus.READY, hint.status)
        assertEquals("chunk-2", hint.triggerChunkId)
        assertEquals("Hint 1", hint.card?.title)
        val ctx = contexts.single()
        assertEquals("chunk-2", ctx.trigger.chunk.id)
        assertEquals(2, ctx.page.size)
    }

    @Test
    fun otherKindsAndPendingOcrDoNotTrigger() = runBlocking {
        val hints = service()
        hints.handle(updated(work))
        hints.handle(updated(tc(chunk("chunk-3"), ChunkKind.QUESTION_MARK)))
        hints.handle(updated(tc(chunk("chunk-4"), null)))
        delay(100)
        assertTrue(hints.hints("doc").isEmpty())
    }

    @Test
    fun waitsForThePageToBeTranscribed() = runBlocking {
        val hints = service()
        val pending = tc(chunk("chunk-1"), null)
        page = listOf(pending, circled)
        hints.handle(updated(circled))
        delay(80)
        assertTrue(contexts.isEmpty(), "must not ask while OCR is pending")
        page = listOf(work, circled)
        delay(150)
        assertEquals("A(x)=2x\\sqrt{1-x^2}", contexts.single().page.first().transcription?.content)
    }

    @Test
    fun sameCircledQuestionMarkIsAnsweredOnceAndDismissStaysDismissed() = runBlocking {
        val hints = service()
        page = listOf(work, circled)
        hints.handle(updated(circled))
        delay(150)
        hints.handle(updated(circled))
        delay(150)
        assertEquals(1, contexts.size)

        val id = hints.hints("doc").single().id
        assertTrue(hints.dismiss("doc", id))
        hints.handle(updated(circled))
        delay(150)
        assertTrue(hints.hints("doc").isEmpty())
    }

    @Test
    fun secondRequestSeesPreviousHints() = runBlocking {
        val hints = service()
        val second = tc(chunk("chunk-5", y = 200.0), ChunkKind.HINT_REQUEST)
        page = listOf(work, circled, second)
        hints.handle(updated(circled))
        delay(150)
        hints.handle(updated(second))
        delay(150)
        assertEquals(listOf("Hint 1"), contexts[1].previousHints.map { it.title })
    }

    @Test
    fun erasingTheCircledQuestionMarkRemovesItsHint() = runBlocking {
        val hints = service()
        page = listOf(work, circled)
        hints.handle(updated(circled))
        delay(150)
        hints.handle(TranscriptionEvent.Removed("doc", "chunk-2"))
        assertTrue(hints.hints("doc").isEmpty())
    }

    @Test
    fun generatorFailureIsReportedAsFailed() = runBlocking {
        val hints = service(gen = { error("model down") })
        page = listOf(work, circled)
        hints.handle(updated(circled))
        delay(150)
        assertEquals(HintStatus.FAILED, hints.hints("doc").single().status)
    }

    @Test
    fun withoutGeneratorNothingHappens() = runBlocking {
        val hints = service(gen = null)
        hints.handle(updated(circled))
        assertTrue(hints.hints("doc").isEmpty())
    }

    @Test
    fun promptMarksTheTriggerAndListsPositions() {
        val text = KoogHintAgent.describe(HintContext(listOf(work, circled), circled, emptyList()))
        assertTrue("[chunk-1] x=0..100 y=0..40  MATH: A(x)=2x\\sqrt{1-x^2}" in text, text)
        assertTrue("[chunk-2] x=0..100 y=100..140  << CIRCLED ?" in text, text)
    }
}
