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
    /** The problem the student is solving, if the document has one. */
    val exercise: String = "",
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
        /** Second independent variable: present → the client renders a 3D surface. */
        val variableY: String? = null,
        /** JavaScript the client can evaluate directly, e.g. `2*x*Math.sqrt(1-x*x)`. */
        val expression: String,
        val domain: List<PlotInterval> = listOf(PlotInterval(-5.0, 5.0)),
        /** Range of the second variable (defaults to a square with the x domain). */
        val domainY: PlotInterval? = null,
        /** The special points the STUDENT computed about this function on the page. */
        val points: List<PlotPoint> = emptyList(),
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

/**
 * A point the student computed about a function (zero, critical point, extremum,
 * evaluated value) — extracted from their work by the model, rendered on the plot.
 *
 * @property y for one-variable plots: the value f(x); for two-variable plots:
 *   the second input, with [z] as the value
 * @property z null when the student only wrote the inputs: the client evaluates it
 */
@Serializable
data class PlotPoint(
    val x: Double,
    val y: Double? = null,
    val z: Double? = null,
    val label: String? = null,
)

/** A plot action plus the bounds of the chunk that defined the function, if known. */
@Serializable
data class PlotWithAnchor(
    val plot: AgentAction.PlotAction,
    val anchor: com.lauzhack.shared.chunking.BoundingBox? = null,
)

/** One stored turn of the per-document conversation. */
@kotlinx.serialization.Serializable
data class ConversationMessage(val role: String, val content: String)
