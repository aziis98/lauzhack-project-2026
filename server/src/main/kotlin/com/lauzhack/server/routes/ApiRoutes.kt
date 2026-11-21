package com.lauzhack.server.routes

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

fun Route.apiRoutes() {
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
                if (deleteDocument(id)) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound)
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
                        call.respond(HttpStatusCode.OK, mapOf("count" to elements.size))
                    }
                }
                post {
                    val id = call.parameters["id"]!!
                    val element = call.receive<Element>()
                    if (createElement(id, element)) {
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
                        if (updated == null) call.respond(HttpStatusCode.NotFound) else call.respond(updated)
                    }
                    delete {
                        val id = call.parameters["id"]!!
                        val eid = call.parameters["eid"]!!
                        if (deleteElement(id, eid)) {
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
