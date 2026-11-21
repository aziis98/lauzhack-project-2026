package com.lauzhack.shared.dto

import kotlinx.serialization.Serializable

@Serializable
data class CreateDocumentRequest(val name: String, val exercise: String = "")

/** `PATCH /api/documents/{id}/hint-level`: the hint detail slider moved. */
@Serializable
data class SetHintLevelRequest(val level: com.lauzhack.shared.hint.HintLevel)
