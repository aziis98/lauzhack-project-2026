package com.lauzhack.server.routes

import com.lauzhack.server.agent.AgentService
import com.lauzhack.server.db.getDocument
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
 * ```
 */
fun Route.agentRoutes(actions: AgentService) {
    route("/api/documents/{id}") {
        get("actions") {
            val id = call.parameters["id"]!!
            if (getDocument(id) == null) call.respond(HttpStatusCode.NotFound)
            else call.respond(actions.snapshot(id))
        }

        get("conversation") {
            val id = call.parameters["id"]!!
            if (getDocument(id) == null) call.respond(HttpStatusCode.NotFound)
            else call.respond(actions.conversation(id))
        }
    }
}
