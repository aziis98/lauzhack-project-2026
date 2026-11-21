package com.lauzhack.server.routes

import com.lauzhack.server.chunking.ChunkService
import com.lauzhack.server.hint.HintService
import com.lauzhack.server.agent.AgentService
import com.lauzhack.server.ocr.ChunkRasterizer
import com.lauzhack.server.ocr.OcrService
import com.lauzhack.server.ocr.SymbolAnnotator
import com.lauzhack.server.ocr.buildOcrUserText
import com.lauzhack.server.ocr.OCR_SYSTEM_PROMPT
import com.lauzhack.server.agent.PAGE_AGENT_SYSTEM_PROMPT
import com.lauzhack.shared.chunking.Chunk
import com.lauzhack.shared.debug.DebugAgent
import com.lauzhack.shared.debug.DebugBox
import com.lauzhack.shared.debug.DebugOcr
import com.lauzhack.shared.debug.DebugOcrChunk
import com.lauzhack.shared.debug.DebugPayload
import com.lauzhack.shared.debug.DebugTurn
import com.lauzhack.shared.debug.labelOf
import com.lauzhack.shared.ocr.parseBboxes
import io.ktor.http.ContentType
import io.ktor.server.response.respondBytes
import com.lauzhack.shared.ocr.TranscribedChunk
import com.lauzhack.server.db.createDocument
import com.lauzhack.server.db.createElement
import com.lauzhack.server.db.deleteDocument
import com.lauzhack.server.db.deleteElement
import com.lauzhack.server.db.getDocument
import com.lauzhack.server.db.resolveDocument
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

fun Route.apiRoutes(ocr: OcrService, hints: HintService, agent: AgentService) {
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
                call.respond(HttpStatusCode.Created, createDocument(name, request.exercise))
            }
        }

        route("{id}") {
            get {
                val id = call.parameters["id"]!!
                // accepts the full id or the 16-hex short form used in URLs
                val doc = resolveDocument(id)
                if (doc == null) call.respond(HttpStatusCode.NotFound) else call.respond(doc)
            }
            // The hint detail slider: { "level": "MINIMAL" | "MEDIUM" | "GUIDED" }
            patch("hint-level") {
                val id = call.parameters["id"]!!
                val request = call.receive<com.lauzhack.shared.dto.SetHintLevelRequest>()
                val doc = com.lauzhack.server.db.setHintLevel(id, request.level)
                if (doc == null) call.respond(HttpStatusCode.NotFound) else call.respond(doc)
            }
            delete {
                val id = call.parameters["id"]!!
                if (deleteDocument(id)) {
                    ChunkService.forget(id)
                    ocr.forget(id)
                    hints.forget(id)
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
                val chunk = chunkOf(id, call.parameters["cid"])
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respondBytes(chunkPng(id, chunk), ContentType.Image.PNG)
            }

            // Symbol fragments detected by pre-OCR segmentation; debug/inspection.
            get("chunks/{cid}/symbols") {
                val id = call.parameters["id"]!!
                val chunk = chunkOf(id, call.parameters["cid"])
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(SymbolAnnotator().annotate(chunkPng(id, chunk)))
            }

            // The chunk image with the segmentation painted over the ink; debug/inspection.
            get("chunks/{cid}/annotated.png") {
                val id = call.parameters["id"]!!
                val chunk = chunkOf(id, call.parameters["cid"])
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                val png = chunkPng(id, chunk)
                val annotator = SymbolAnnotator()
                call.respondBytes(annotator.renderAnnotated(png, annotator.annotate(png)), ContentType.Image.PNG)
            }

            // Wire-level debug view: agent conversation, OCR prompts, chunks, hints (?debug mode).
            get("debug") {
                val id = call.parameters["id"]!!
                if (getDocument(id) == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    call.respond(debugPayload(id, ocr, hints, agent))
                }
            }

            // Chunks with their OCR result (null = pending); debug/inspection.
            // Hints answering circled "?" on the page (polled by the canvas).
            route("hints") {
                get {
                    val id = call.parameters["id"]!!
                    call.respond(hints.hints(id))
                }
                // Help button in the toolbar (no circled "?").
                post {
                    val id = call.parameters["id"]!!
                    if (getDocument(id) == null) {
                        call.respond(HttpStatusCode.NotFound)
                        return@post
                    }
                    val hint = agent.requestHelp(id)
                    if (hint == null) call.respond(HttpStatusCode.ServiceUnavailable) else call.respond(HttpStatusCode.Created, hint)
                }
                // The student clicked a word or formula of the exercise statement.
                post("define") {
                    val id = call.parameters["id"]!!
                    val request = call.receive<com.lauzhack.shared.dto.DefineTermRequest>()
                    if (getDocument(id) == null || request.term.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@post
                    }
                    val hint = agent.requestDefinition(id, request)
                    if (hint == null) call.respond(HttpStatusCode.ServiceUnavailable) else call.respond(HttpStatusCode.Created, hint)
                }
                // "Another hint" for a card the student did not understand.
                post("{hid}/more") {
                    val hint = agent.requestMoreHelp(call.parameters["id"]!!, call.parameters["hid"]!!)
                    if (hint == null) call.respond(HttpStatusCode.NotFound) else call.respond(hint)
                }
                // The student dragged the card: { "x": …, "y": … } in page coordinates.
                patch("{hid}/position") {
                    val position = call.receive<com.lauzhack.shared.domain.Point>()
                    val hint = hints.move(call.parameters["id"]!!, call.parameters["hid"]!!, position)
                    if (hint == null) call.respond(HttpStatusCode.NotFound) else call.respond(hint)
                }
                delete("{hid}") {
                    val id = call.parameters["id"]!!
                    val removed = hints.dismiss(id, call.parameters["hid"]!!)
                    call.respond(if (removed) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
                }
            }

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

/** The chunk with id [cid] of document [documentId], or null. */
private suspend fun chunkOf(documentId: String, cid: String?): Chunk? =
    cid?.let { wanted -> ChunkService.snapshot(documentId).find { it.id == wanted } }

/** Rasterizes one chunk exactly as the OCR pipeline would render it. */
private suspend fun chunkPng(documentId: String, chunk: Chunk): ByteArray {
    val ids = chunk.strokeIds.toSet()
    return ChunkRasterizer().render(chunk.bounds, listElements(documentId).filter { it.id in ids })
}

/**
 * Builds the full wire-level debug view of one document's pipeline: the agent
 * conversation (system prompt + every turn), the OCR prompts and answers per
 * chunk (with the model's `\bbox` symbols mapped back into page coordinates),
 * the chunks themselves and the current hints.
 */
private suspend fun debugPayload(
    documentId: String,
    ocr: OcrService,
    hints: HintService,
    agent: AgentService,
): DebugPayload {
    val chunks = ChunkService.snapshot(documentId)
    val transcribed = ocr.snapshot(documentId).associateBy { it.chunk.id }
    val rasterizer = ChunkRasterizer()
    val annotator = SymbolAnnotator()

    val ocrChunks = chunks.map { chunk ->
        val png = chunkPng(documentId, chunk)
        val fragments = annotator.annotate(png)
        val (scale, padding) = rasterizer.rasterParams(chunk.bounds)

        // image px → page units
        fun toPageX(img: Double) = chunk.bounds.minX + (img - padding) / scale
        fun toPageY(img: Double) = chunk.bounds.minY + (img - padding) / scale

        val transcription = transcribed[chunk.id]?.transcription

        DebugOcrChunk(
            chunkId = chunk.id,
            userText = buildOcrUserText(fragments),
            fragments = fragments,
            response = transcription,
            modelBoxes = transcription?.content.orEmpty().parseBboxes().map { b ->
                DebugBox(
                    toPageX(b.minX.toDouble()), toPageY(b.minY.toDouble()),
                    toPageX(b.maxX.toDouble()), toPageY(b.maxY.toDouble()),
                    b.symbol,
                )
            },
            fragmentBoxes = fragments.map { f ->
                DebugBox(
                    toPageX(f.minX.toDouble()), toPageY(f.minY.toDouble()),
                    toPageX(f.maxX.toDouble()), toPageY(f.maxY.toDouble()),
                    "#${f.id}",
                )
            },
        )
    }

    return DebugPayload(
        agent = DebugAgent(
            systemPrompt = PAGE_AGENT_SYSTEM_PROMPT,
            turns = agent.conversation(documentId).map { m -> DebugTurn(m.role, labelOf(m), m.content) },
        ),
        ocr = DebugOcr(OCR_SYSTEM_PROMPT, ocrChunks),
        chunks = chunks,
        hints = hints.hints(documentId),
    )
}
