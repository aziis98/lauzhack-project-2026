package com.lauzhack.shared.hint

import com.lauzhack.shared.chunking.BoundingBox
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
    val message: String,
    val steps: List<String> = emptyList(),
    val relatedChunkIds: List<String> = emptyList(),
)

@Serializable
enum class HintStatus { THINKING, READY, FAILED }

/**
 * A help request and its answer.
 *
 * @property triggerChunkId the chunk holding the circled "?"
 * @property anchor page-space bounds of the trigger, used to place the card
 * @property card null until [status] is [HintStatus.READY]
 */
@Serializable
data class Hint(
    val id: String,
    val triggerChunkId: String,
    val anchor: BoundingBox,
    val status: HintStatus,
    val card: HintCard? = null,
    val error: String? = null,
    val createdAt: Long = 0L,
)
