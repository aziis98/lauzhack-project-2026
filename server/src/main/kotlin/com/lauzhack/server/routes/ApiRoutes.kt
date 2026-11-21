package com.lauzhack.server.routes

import com.lauzhack.server.chunking.ChunkService
import com.lauzhack.server.ocr.ChunkRasterizer
import com.lauzhack.server.ocr.OcrService
import io.ktor.http.ContentType
import io.ktor.server.response.respondBytes
import com.lauzhack.shared.ocr.TranscribedChunk
import com.lauzhack.server.db.createDocument
import com.lauzhack.server.db.createElement
import com.lauzhack.server.db.deleteDocument
import com.lauzhack.server.db.deleteElement
import com.lauzhack.server.db.getDocument
import com.lauzhack.server.db.listDocuments
import com.lauzhack.server.db.listElements
import com.lauzhack.server.db.replaceElements
import com.lauzhack.server.db.updateElement
import com.lauzhack.shared.domain.Element
import com.lauzhack.shared.dto.CreateDocumentRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

fun Route.apiRoutes(ocr: OcrService) {
    route("/api/documents") {
        get {
            call.respond(listDocuments())
        }
        post {
            val request = call.receive<CreateDocumentRequest>()
            val name = request.name.trim()
            if (name.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "name must not be blank"))
            } else {
                call.respond(HttpStatusCode.Created, createDocument(name))
            }
        }

        route("{id}") {
            get {
                val id = call.parameters["id"]!!
                val doc = getDocument(id)
                if (doc == null) call.respond(HttpStatusCode.NotFound) else call.respond(doc)
            }
            delete {
                val id = call.parameters["id"]!!
                if (deleteDocument(id)) {
                    ChunkService.forget(id)
                    ocr.forget(id)
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }

            // Handwriting macro-blocks with page coordinates (debug/inspection; see shared/chunking).
            get("chunks") {
                val id = call.parameters["id"]!!
                if (getDocument(id) == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respond(ChunkService.snapshot(id))
                }
            }

            // The exact image the OCR model receives for one chunk; debug/inspection.
            get("chunks/{cid}/image.png") {
                val id = call.parameters["id"]!!
                val chunk = ChunkService.snapshot(id).find { it.id == call.parameters["cid"] }
                if (chunk == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    val ids = chunk.strokeIds.toSet()
                    val png = ChunkRasterizer().render(chunk.bounds, listElements(id).filter { it.id in ids })
                    call.respondBytes(png, ContentType.Image.PNG)
                }
            }

            // Chunks with their OCR result (null = pending); debug/inspection.
            get("transcription") {
                val id = call.parameters["id"]!!
                if (getDocument(id) == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respond(ChunkService.snapshot(id).map { TranscribedChunk(it, ocr.cached(it.contentHash)) })
                }
            }

            route("elements") {
                get {
                    val id = call.parameters["id"]!!
                    if (getDocument(id) == null) {
                        call.respond(HttpStatusCode.NotFound)
                    } else {
                        call.respond(listElements(id))
                    }
                }
                put {
                    val id = call.parameters["id"]!!
                    val elements = call.receive<List<Element>>()
                    if (getDocument(id) == null) {
                        call.respond(HttpStatusCode.NotFound)
                    } else {
                        replaceElements(id, elements)
                        ChunkService.refresh(id)
                        call.respond(HttpStatusCode.OK, mapOf("count" to elements.size))
                    }
                }
                post {
                    val id = call.parameters["id"]!!
                    val element = call.receive<Element>()
                    if (createElement(id, element)) {
                        ChunkService.refresh(id)
                        call.respond(HttpStatusCode.Created, element)
                    } else {
                        call.respond(HttpStatusCode.NotFound)
                    }
                }

                route("{eid}") {
                    patch {
                        val id = call.parameters["id"]!!
                        val eid = call.parameters["eid"]!!
                        val element = call.receive<Element>()
                        val updated = updateElement(id, eid, element)
                        if (updated != null) ChunkService.refresh(id)
                        if (updated == null) call.respond(HttpStatusCode.NotFound) else call.respond(updated)
                    }
                    delete {
                        val id = call.parameters["id"]!!
                        val eid = call.parameters["eid"]!!
                        if (deleteElement(id, eid)) {
                            ChunkService.refresh(id)
                            call.respond(HttpStatusCode.NoContent)
                        } else {
                            call.respond(HttpStatusCode.NotFound)
                        }
                    }
                }
            }
        }
    }
}
