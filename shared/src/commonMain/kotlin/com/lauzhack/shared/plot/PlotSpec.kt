package com.lauzhack.shared.plot

import kotlinx.serialization.Serializable

/**
 * A function plot derived from an agent [com.lauzhack.shared.agent.AgentAction.PlotAction]:
 * the model only chose [expression] and [domain]; everything else (parsing, sampling,
 * highlights, rendering) is deterministic Kotlin.
 *
 * @property id stable per plot, e.g. `"plot:1a2b3c4d"`
 */
@Serializable
data class PlotSpec(
    val id: String,
    val documentId: String,
    val functionName: String? = null,
    val expression: String,
    val variable: String = "x",
    val domain: List<Interval> = listOf(Interval(-5.0, 5.0)),
    val samples: Int = 600,
    val highlights: List<Highlight> = emptyList(),
)

/** One x-range to plot; several segments cover e.g. functions with holes. */
@Serializable
data class Interval(val from: Double, val to: Double)

/**
 * A point to mark on the plot with its label.
 *
 * @property y null lets the renderer evaluate it from the expression
 */
@Serializable
data class Highlight(
    val x: Double,
    val y: Double? = null,
    val kind: HighlightKind = HighlightKind.CUSTOM,
    val label: String? = null,
)

@Serializable
enum class HighlightKind { ROOT, CRITICAL, CUSTOM }
