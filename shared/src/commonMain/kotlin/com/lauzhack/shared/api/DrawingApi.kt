package com.lauzhack.shared.api

import com.lauzhack.shared.agent.ErrorMark
import com.lauzhack.shared.agent.PlotWithAnchor
import com.lauzhack.shared.debug.DebugPayload
import com.lauzhack.shared.domain.Document
import com.lauzhack.shared.domain.Element
import com.lauzhack.shared.dto.CreateDocumentRequest
import com.lauzhack.shared.exercise.AssetRef
import com.lauzhack.shared.exercise.ConvertRegionRequest
import com.lauzhack.shared.exercise.ConvertRegionResponse
import com.lauzhack.shared.exercise.UploadAssetRequest
import com.lauzhack.shared.hint.Hint
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Multiplatform REST client for the drawing backend.
 * Shared by the web frontend today and future Android/iOS apps.
 */
class DrawingApi(
    baseUrl: String = "",
    private val client: HttpClient = defaultClient(),
) {
    private val api = "$baseUrl/api"

    suspend fun listDocuments(): List<Document> =
        client.get("$api/documents").body()

    /** Resolves a full document id OR a 16-hex short id; null if not found. */
    suspend fun getDocument(idOrShortId: String): Document? =
        try {
            client.get("$api/documents/$idOrShortId").body<Document>()
        } catch (cause: Exception) {
            null
        }

    /** Wire-level debug view of the pipeline for one document (?debug mode). */
    suspend fun debug(documentId: String): DebugPayload? =
        try {
            client.get("$api/documents/$documentId/debug").body<DebugPayload>()
        } catch (cause: Exception) {
            null
        }

    suspend fun createDocument(name: String, exercise: String = ""): Document =
        client.post("$api/documents") {
            contentType(ContentType.Application.Json)
            setBody(CreateDocumentRequest(name, exercise))
        }.body()

    suspend fun deleteDocument(documentId: String): Boolean =
        client.delete("$api/documents/$documentId").status.value in 200..299

    /** Saves the hint detail level of a document (the slider on the canvas). */
    suspend fun setHintLevel(documentId: String, level: com.lauzhack.shared.hint.HintLevel): Boolean =
        client.patch("$api/documents/$documentId/hint-level") {
            contentType(ContentType.Application.Json)
            setBody(com.lauzhack.shared.dto.SetHintLevelRequest(level))
        }.status.value in 200..299

    /** Converts a region selected on a PDF page into exercise Markdown + LaTeX. */
    suspend fun convertExerciseRegion(request: ConvertRegionRequest): ConvertRegionResponse {
        val response = client.post("$api/exercises/convert") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        check(response.status.value in 200..299) { "conversion failed (${response.status.value})" }
        return response.body()
    }

    /** Stores a PNG (e.g. a figure cropped from a PDF) and returns its URL. */
    suspend fun uploadAsset(imagePngBase64: String): AssetRef =
        client.post("$api/assets") {
            contentType(ContentType.Application.Json)
            setBody(UploadAssetRequest(imagePngBase64))
        }.body()

    suspend fun getElements(documentId: String): List<Element> =
        client.get("$api/documents/$documentId/elements").body()

    suspend fun replaceElements(documentId: String, elements: List<Element>): Boolean =
        client.put("$api/documents/$documentId/elements") {
            contentType(ContentType.Application.Json)
            setBody(elements)
        }.status.value in 200..299

    suspend fun createElement(documentId: String, element: Element): HttpResponse =
        client.post("$api/documents/$documentId/elements") {
            contentType(ContentType.Application.Json)
            setBody(element)
        }

    suspend fun deleteElement(documentId: String, elementId: String): Boolean =
        client.delete("$api/documents/$documentId/elements/$elementId").status.value in 200..299

    /** Hints answering the circled "?" drawn on the page. */
    suspend fun getHints(documentId: String): List<Hint> =
        client.get("$api/documents/$documentId/hints").body()

    /** The plots the page agent currently proposes for the document. */
    suspend fun getPlots(documentId: String): List<PlotWithAnchor> =
        client.get("$api/documents/$documentId/plots").body()

    /** The error marks (red highlights) the page agent currently flags for the document. */
    suspend fun getErrors(documentId: String): List<ErrorMark> =
        client.get("$api/documents/$documentId/errors").body()

    /** Asks for a hint without drawing a circled "?" (toolbar button). */
    suspend fun requestHint(documentId: String): Boolean =
        client.post("$api/documents/$documentId/hints").status.value in 200..299

    /** The student clicked a word (or formula) of the exercise statement: explain it. */
    suspend fun defineTerm(documentId: String, request: com.lauzhack.shared.dto.DefineTermRequest): Boolean =
        client.post("$api/documents/$documentId/hints/define") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.status.value in 200..299

    /** "Another hint" for a card the student did not understand. */
    suspend fun moreHint(documentId: String, hintId: String): Boolean =
        client.post("$api/documents/$documentId/hints/$hintId/more").status.value in 200..299

    /** Saves where the card was dragged (page coordinates). */
    suspend fun moveHint(documentId: String, hintId: String, x: Double, y: Double): Boolean =
        client.patch("$api/documents/$documentId/hints/$hintId/position") {
            contentType(ContentType.Application.Json)
            setBody(com.lauzhack.shared.domain.Point(x, y))
        }.status.value in 200..299

    suspend fun dismissHint(documentId: String, hintId: String): Boolean =
        client.delete("$api/documents/$documentId/hints/$hintId").status.value in 200..299

    companion object {
        /** Must mirror the server's JSON configuration exactly. */
        fun defaultJson(): Json = Json {
            classDiscriminator = "kind"
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun defaultClient(): HttpClient = HttpClient {
            install(ContentNegotiation) {
                json(defaultJson())
            }
        }
    }
}
