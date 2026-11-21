package com.lauzhack.shared.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One transcribed block of the page. */
@Serializable
data class PageChunk(
    val id: String,
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
    /** ChunkKind name: MATH, TEXT, HINT_REQUEST, DRAWING, UNREADABLE. */
    val kind: String,
    val content: String,
)

/** The bootstrap message: the whole page, when the conversation starts (or restarts). */
@Serializable
data class PageSnapshot(
    val type: String = "page",
    val chunks: List<PageChunk>,
)

/** One delta turn: what changed on the page since the previous message. */
@Serializable
data class PageDelta(
    val type: String = "changes",
    val added: List<PageChunk> = emptyList(),
    val modified: List<PageChunk> = emptyList(),
    val removed: List<String> = emptyList(),
)

/**
 * The agent's answer, always a JSON list of actions:
 * an **empty list means no action is needed**.
 */
@Serializable
data class AgentOutput(val actions: List<AgentAction> = emptyList())

/** One thing the agent decided to do about the page. Discriminated by `kind`. */
@Serializable
sealed interface AgentAction {

    /** A circled "?" ([PageChunk.kind] `HINT_REQUEST`) asks for a Socratic hint card. */
    @Serializable
    @SerialName("hint")
    data class HintAction(
        val id: String = "",
        val triggerChunkId: String,
        val title: String,
        val message: String,
        val steps: List<String> = emptyList(),
        val relatedChunkIds: List<String> = emptyList(),
    ) : AgentAction

    /** A function definition on the page should be plotted (client-side, JS expression). */
    @Serializable
    @SerialName("plot")
    data class PlotAction(
        val id: String = "",
        val functionName: String? = null,
        val variable: String = "x",
        /** JavaScript the client can evaluate directly, e.g. `2*x*Math.sqrt(1-x*x)`. */
        val expression: String,
        val domain: List<PlotInterval> = listOf(PlotInterval(-5.0, 5.0)),
        /** Withdraw a previously emitted plot with the same id. */
        val remove: Boolean = false,
    ) : AgentAction

    /** A clear mathematical mistake in the student's work. */
    @Serializable
    @SerialName("error")
    data class ErrorAction(
        val id: String = "",
        val chunkId: String,
        val message: String,
        val suggestion: String? = null,
        val remove: Boolean = false,
    ) : AgentAction
}

@Serializable
data class PlotInterval(val from: Double, val to: Double)

/** One stored turn of the per-document conversation. */
@kotlinx.serialization.Serializable
data class ConversationMessage(val role: String, val content: String)
