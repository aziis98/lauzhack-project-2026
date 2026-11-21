package com.lauzhack.server.routes

import com.lauzhack.server.agent.AgentService
import com.lauzhack.server.db.getDocument
import com.lauzhack.server.plot.PlotRenderer
import com.lauzhack.server.plot.PlotRegistry
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * Page-agent endpoints, kept separate from [apiRoutes] so the two features can be
 * developed in parallel without touching each other's files.
 *
 * ```
 * GET /api/documents/{id}/actions                the agent's latest answer (debug/inspection)
 * GET /api/documents/{id}/plots                  current PlotSpecs as JSON
 * GET /api/documents/{id}/plots/{pid}/image.png a plot, rendered deterministically
 * ```
 */
fun Route.agentRoutes(actions: AgentService, plots: PlotRegistry) {
    route("/api/documents/{id}") {
        get("actions") {
            val id = call.parameters["id"]!!
            if (getDocument(id) == null) call.respond(HttpStatusCode.NotFound)
            else call.respond(actions.snapshot(id))
        }

        route("plots") {
            get {
                val id = call.parameters["id"]!!
                if (getDocument(id) == null) call.respond(HttpStatusCode.NotFound)
                else call.respond(plots.snapshot(id))
            }

            get("{pid}/image.png") {
                val id = call.parameters["id"]!!
                val pid = call.parameters["pid"]!!
                val spec = plots.plot(id, pid)
                if (spec == null) call.respond(HttpStatusCode.NotFound)
                else call.respondBytes(PlotRenderer.render(spec), ContentType.Image.PNG)
            }
        }
    }
}
