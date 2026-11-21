package com.lauzhack.shared.agent

import com.lauzhack.shared.plot.Interval
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The page as the agent sees it: the JSON synthesized from the chunk and canvas
 * information, sent to the model in the user message.
 */
@Serializable
data class PageContext(
    val documentId: String,
    val chunks: List<PageChunk>,
    /** Hints already given on this page, oldest first (so the next can be more concrete). */
    val givenHints: List<GivenHint> = emptyList(),
)

/** One transcribed block of the page, in reading order. */
@Serializable
data class PageChunk(
    val id: String,
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
    /** [com.lauzhack.shared.ocr.ChunkKind] name: MATH, TEXT, HINT_REQUEST, DRAWING, UNREADABLE. */
    val kind: String,
    val content: String,
)

@Serializable
data class GivenHint(val title: String, val message: String)

/**
 * The agent's answer, always a JSON list of actions:
 * an **empty list means no action is needed** — plain work with no help request
 * and nothing to plot.
 */
@Serializable
data class AgentOutput(val actions: List<AgentAction> = emptyList())

/** One thing the agent decided to do about the page. Discriminated by `type`. */
@Serializable
sealed interface AgentAction {

    /** A circled "?" ([PageChunk.kind] `HINT_REQUEST`) asks for a Socratic hint card. */
    @Serializable
    @SerialName("hint")
    data class HintAction(
        /** The chunk holding the circled "?" — the card anchors next to it. */
        val triggerChunkId: String,
        val title: String,
        val message: String,
        val steps: List<String> = emptyList(),
        val relatedChunkIds: List<String> = emptyList(),
    ) : AgentAction

    /** A function definition on the page should be plotted — the model computes the curve itself. */
    @Serializable
    @SerialName("plot")
    data class PlotAction(
        val functionName: String? = null,
        val variable: String = "x",
        /** The definition as written on the page — shown as the plot caption, never evaluated. */
        val expression: String = "",
        val domain: List<Interval> = listOf(Interval(-5.0, 5.0)),
        /** The curve, sampled by the model: `[x, y]` pairs ordered by x. */
        val points: List<List<Double>> = emptyList(),
        /** Interesting points the model found (roots, maxima, minima). */
        val highlights: List<PlotHighlight> = emptyList(),
    ) : AgentAction
}

@Serializable
data class PlotHighlight(
    val x: Double,
    val y: Double? = null,
    /** root | max | min | custom */
    val kind: String = "custom",
    val label: String? = null,
)
