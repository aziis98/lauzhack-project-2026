package com.lauzhack.shared.api

import com.lauzhack.shared.domain.Document
import com.lauzhack.shared.domain.Element
import com.lauzhack.shared.dto.CreateDocumentRequest
import com.lauzhack.shared.hint.Hint
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
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

    suspend fun createDocument(name: String): Document =
        client.post("$api/documents") {
            contentType(ContentType.Application.Json)
            setBody(CreateDocumentRequest(name))
        }.body()

    suspend fun getDocument(documentId: String): Document =
        client.get("$api/documents/$documentId").body()

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
