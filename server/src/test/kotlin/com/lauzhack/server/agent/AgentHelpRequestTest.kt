package com.lauzhack.server.agent

import com.lauzhack.server.db.AgentStateTable
import com.lauzhack.server.db.jsonConfig
import com.lauzhack.server.hint.HintService
import com.lauzhack.server.ocr.OcrService
import com.lauzhack.shared.agent.AgentAction
import com.lauzhack.shared.agent.AgentOutput
import com.lauzhack.shared.agent.HelpRequest
import com.lauzhack.shared.agent.PageDelta
import com.lauzhack.shared.agent.PageSnapshot
import com.lauzhack.shared.hint.Hint
import com.lauzhack.shared.hint.HintStatus
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Help button and "Another hint" go through the page agent's conversation. */
class AgentHelpRequestTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val messages = Collections.synchronizedList(mutableListOf<String>())
    private var answer: (String) -> AgentOutput = { AgentOutput() }

    private val ocr = OcrService(recognizer = null, scope = scope)
    private val hints = HintService(scope = scope, pageTranscription = ocr::snapshot)
    private val fake = PageAgent { _, userMessage -> messages += userMessage; answer(userMessage) }
    private val agent = AgentService(fake, ocr, hints, scope, jsonConfig, settleTimeoutMillis = 100, settlePollMillis = 10)

    @BeforeTest
    fun db() {
        val file = File.createTempFile("agent-test", ".db").apply { deleteOnExit() }
        Database.connect("jdbc:sqlite:${file.absolutePath}", driver = "org.sqlite.JDBC")
        transaction { SchemaUtils.create(AgentStateTable) }
    }

    @AfterTest
    fun tearDown() = scope.cancel()

    /** Answers every request in the message with a hint titled "<prefix> <requestId>". */
    private fun answerRequests(prefix: String): (String) -> AgentOutput = { msg ->
        val requests = jsonConfig.parseToJsonElement(msg).jsonObject["requests"]
            ?.jsonArray?.map { jsonConfig.decodeFromJsonElement(HelpRequest.serializer(), it) }
            ?: emptyList()
        AgentOutput(requests.map { AgentAction.HintAction(requestId = it.requestId, title = "$prefix ${it.requestId}", minimal = "min", medium = "med", guided = "gui") })
    }

    private suspend fun awaitStatus(id: String, status: HintStatus): Hint {
        repeat(200) {
            val hint = hints.hints("doc").find { it.id == id }
            if (hint?.status == status) return hint
            delay(10)
        }
        error("hint $id never reached $status: ${hints.hints("doc")}")
    }

    @Test
    fun helpButtonIsSentAsARequestAndAnsweredInItsCard() = runBlocking {
        answer = answerRequests("Hint")
        val hint = assertNotNull(agent.requestHelp("doc"))
        assertEquals(HintStatus.THINKING, hint.status)

        val ready = awaitStatus(hint.id, HintStatus.READY)
        assertEquals("Hint ${hint.id}", ready.card?.title)
        val sent = jsonConfig.decodeFromString<PageSnapshot>(messages.single())
        assertEquals("button", sent.requests.single().kind)
        assertEquals(hint.id, sent.requests.single().requestId)
    }

    @Test
    fun anotherHintSendsWhatTheCardAlreadySaid() = runBlocking {
        answer = answerRequests("First")
        val hint = agent.requestHelp("doc")!!
        awaitStatus(hint.id, HintStatus.READY)

        answer = answerRequests("Second")
        val thinking = assertNotNull(agent.requestMoreHelp("doc", hint.id))
        assertEquals(HintStatus.THINKING, thinking.status)
        val ready = awaitStatus(hint.id, HintStatus.READY)

        assertEquals("Second ${hint.id}", ready.card?.title)
        assertEquals(listOf("First ${hint.id}"), ready.history.map { it.title })
        val more = jsonConfig.decodeFromString<PageDelta>(messages.last()).requests.single()
        assertEquals("more", more.kind)
        assertEquals(listOf("First ${hint.id}: med"), more.previousHints)
    }

    @Test
    fun unansweredRequestFailsInsteadOfHanging() = runBlocking {
        answer = { AgentOutput() } // the model stays silent
        val hint = agent.requestHelp("doc")!!
        assertTrue(awaitStatus(hint.id, HintStatus.FAILED).error!!.isNotBlank())
    }

    @Test
    fun modelErrorFailsTheWaitingCard() = runBlocking {
        answer = { error("model down") }
        val hint = agent.requestHelp("doc")!!
        awaitStatus(hint.id, HintStatus.FAILED)
    }

    @Test
    fun unknownHintCannotAskForMore() = runBlocking {
        assertNull(agent.requestMoreHelp("doc", "hint-404"))
    }

    @Test
    fun withoutAnyModelHelpIsUnavailable() = runBlocking {
        val noAgent = AgentService(null, ocr, hints, scope, jsonConfig)
        assertNull(noAgent.requestHelp("doc"))
    }
}
