package com.lauzhack.shared.exercise

import kotlinx.serialization.Serializable

/**
 * One rectangle the user selected on a PDF page, to be turned into exercise
 * Markdown (with `$…$` / `$$…$$` LaTeX). The PDF itself never leaves the browser.
 *
 * @property imagePngBase64 the selected region rendered as PNG
 * @property pdfText text pdf.js found inside the region (exact words, broken math); may be empty
 * @property previousMarkdown set on a retry: the transcription that had KaTeX errors
 * @property katexErrors set on a retry: KaTeX parse errors of [previousMarkdown]
 */
@Serializable
data class ConvertRegionRequest(
    val imagePngBase64: String,
    val pdfText: String = "",
    val previousMarkdown: String? = null,
    val katexErrors: List<String> = emptyList(),
)

/**
 * @property markdown Markdown + LaTeX; figures found in the region are embedded
 *   as images served by the backend (`![Figure 1](/api/assets/…)`)
 */
@Serializable
data class ConvertRegionResponse(
    val markdown: String,
    val figureCount: Int = 0,
)

/** A figure the user cropped by hand ("Figure" selection mode). */
@Serializable
data class UploadAssetRequest(val imagePngBase64: String)

@Serializable
data class AssetRef(val id: String, val url: String)
