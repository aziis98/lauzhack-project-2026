package com.lauzhack.shared.hint

import com.lauzhack.shared.chunking.BoundingBox
import com.lauzhack.shared.domain.Point
import kotlinx.serialization.Serializable

/**
 * A hint shown to the student as a floating card next to the circled "?"
 * that requested it (README: `HintCard`).
 *
 * @property message the hint itself; math written in plain Unicode (x², √, ≤), no LaTeX
 * @property steps optional short follow-up questions / sub-steps
 * @property relatedChunkIds chunks of the student's work the hint refers to
 */
@Serializable
data class HintCard(
    val title: String,
    /** The same hint at three distances from the solution; the app shows the
     *  level the student chose with the slider. */
    val minimal: String,
    val medium: String,
    val guided: String,
    val relatedChunkIds: List<String> = emptyList(),
)

@Serializable
enum class HintStatus { THINKING, READY, FAILED }

/**
 * How much a hint gives away, chosen by the student with a slider (per document).
 * Every hint stays a hint: none of them states the final result.
 */
@Serializable
enum class HintLevel {
    /** A nudge: the idea or one short question, nothing more. */
    MINIMAL,

    /** The rule or method and where to apply it (the default). */
    MEDIUM,

    /** Leads the student close to the solution, step by step. */
    GUIDED,
}

/**
 * A help request and its answer.
 *
 * @property triggerChunkId the chunk holding the circled "?"; null when asked with the toolbar button
 * @property anchor page-space bounds of the trigger, used to place the card
 * @property card null until [status] is [HintStatus.READY]
 * @property history earlier cards shown in this box ("another hint"), oldest first
 * @property position page-space top-left of the card once the student dragged it;
 *   UI state only, never sent to the AI
 */
@Serializable
data class Hint(
    val id: String,
    val triggerChunkId: String?,
    val anchor: BoundingBox,
    val status: HintStatus,
    val card: HintCard? = null,
    val error: String? = null,
    val createdAt: Long = 0L,
    val history: List<HintCard> = emptyList(),
    val position: Point? = null,
)
