package com.lauzhack.server.routes

import com.lauzhack.server.agent.AgentService
import com.lauzhack.server.db.resolveDocument
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * Page-agent endpoints, kept separate from [apiRoutes] so the two features can be
 * developed in parallel without touching each other's files.
 *
 * ```
 * GET /api/documents/{id}/actions        the agent's latest answer
 * GET /api/documents/{id}/conversation  the full conversation (snapshot, deltas, answers)
 * GET /api/documents/{id}/plots          the active plot actions, with their source anchors
 * GET /api/documents/{id}/errors         the active error marks (red highlights)
 * ```
 */
fun Route.agentRoutes(actions: AgentService) {
    route("/api/documents/{id}") {
        get("actions") {
            val id = resolveId(call.parameters["id"]!!) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(actions.snapshot(id))
        }

        get("conversation") {
            val id = resolveId(call.parameters["id"]!!) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(actions.conversation(id))
        }

        get("plots") {
            val id = resolveId(call.parameters["id"]!!) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(actions.plots(id))
        }

        get("errors") {
            val id = resolveId(call.parameters["id"]!!) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(actions.errors(id))
        }
    }
}

/** Resolves a full id or a 16-hex short id to the document's full id, or null when unknown. */
private fun resolveId(id: String): String? = resolveDocument(id)?.id
