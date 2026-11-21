package com.lauzhack.shared.dto

import kotlinx.serialization.Serializable

@Serializable
data class CreateDocumentRequest(val name: String, val exercise: String = "")

/**
 * `POST /api/documents/{id}/hints/define`: the student clicked a word or formula
 * of the exercise statement.
 *
 * @property term the clicked word, or the LaTeX of the clicked formula
 * @property context the sentence around it (math as `$…$`), so the agent can tell
 *   which concept the word belongs to (e.g. "critici" in "punti critici")
 * @property anchor page-space bounds of the clicked word, to place the card
 */
@Serializable
data class DefineTermRequest(
    val term: String,
    val context: String = "",
    val anchor: com.lauzhack.shared.chunking.BoundingBox,
)

/** `PATCH /api/documents/{id}/hint-level`: the hint detail slider moved. */
@Serializable
data class SetHintLevelRequest(val level: com.lauzhack.shared.hint.HintLevel)
