package com.lauzhack.server

import com.lauzhack.server.ai.AiModels
import com.lauzhack.server.chunking.ChunkService
import com.lauzhack.server.db.initDatabase
import com.lauzhack.server.hint.HintService
import com.lauzhack.server.hint.KoogHintAgent
import com.lauzhack.server.ocr.KoogChunkRecognizer
import com.lauzhack.server.ocr.OcrService
import com.lauzhack.server.db.jsonConfig
import com.lauzhack.server.routes.apiRoutes
import io.ktor.http.CacheControl
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import java.io.File

fun Application.module() {
    initDatabase()

    fun setting(key: String) = environment.config.propertyOrNull(key)?.getString()?.takeIf { it.isNotBlank() }
    val ai = AiModels(
        togetherApiKey = setting("ai.togetherApiKey"),
        ocrModelId = setting("ai.ocrModel") ?: error("ai.ocrModel missing"),
        hintModelId = setting("ai.hintModel") ?: error("ai.hintModel missing"),
        anthropicApiKey = setting("ai.anthropicApiKey"),
        reasoningModelId = setting("ai.reasoningModel") ?: error("ai.reasoningModel missing"),
    )
    if (ai.ocrModel == null) environment.log.warn("TOGETHER_API_KEY not set: OCR and hints disabled")
    if (ai.reasoningModel == null) environment.log.warn("ANTHROPIC_API_KEY not set: reasoning model disabled")
    val ocr = OcrService(
        recognizer = ai.ocrModel?.let { KoogChunkRecognizer(ai.executor, it) },
        scope = this,
    )
    ocr.start(ChunkService.events)
    val hints = HintService(
        generator = ai.hintModel?.let { KoogHintAgent(ai.executor, it) },
        scope = this,
        pageTranscription = ocr::snapshot,
    )
    hints.start(ocr.events)

    install(ContentNegotiation) {
        json(jsonConfig)
    }

    // Permissive in dev so the webpack dev server origin works; tighten for prod.
    // The dev server proxies /api from :8081, so the backend sees a cross-origin
    // Origin header; JSON bodies need non-simple content types allowed.
    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowNonSimpleContentTypes = true
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respondText(
                text = cause.message ?: "Internal server error",
                status = HttpStatusCode.InternalServerError,
            )
            throw cause
        }
    }

    routing {
        apiRoutes(ocr, hints)
        serveFrontendIfBuilt()
    }
}

/** Serves the bundled web frontend when it has been built (production mode). */
private fun io.ktor.server.routing.Routing.serveFrontendIfBuilt() {
    val dist = listOf(
        File("web/dist"),
        File("../web/build/dist/js/productionExecutable"),
    ).firstOrNull { it.isDirectory }
    if (dist != null) {
        staticFiles("/", dist, index = "index.html") {
            // always revalidate so updated bundles are picked up immediately
            cacheControl { listOf(CacheControl.NoCache(null)) }
        }
    }
}
