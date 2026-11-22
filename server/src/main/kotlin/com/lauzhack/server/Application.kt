package com.lauzhack.server

import com.lauzhack.server.ai.AiModels
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import com.lauzhack.server.agent.AgentService
import com.lauzhack.server.agent.KoogPageAgent
import com.lauzhack.server.chunking.ChunkService
import com.lauzhack.server.db.getDocument
import com.lauzhack.server.db.assetUrl
import com.lauzhack.server.db.initDatabase
import com.lauzhack.server.db.saveAsset
import com.lauzhack.server.exercise.ExerciseImportService
import com.lauzhack.server.exercise.KoogRegionConverter
import com.lauzhack.server.routes.exerciseRoutes
import kotlin.io.encoding.Base64
import com.lauzhack.server.hint.HintService
import com.lauzhack.server.ocr.KoogChunkRecognizer
import com.lauzhack.server.ocr.OcrService
import com.lauzhack.server.db.jsonConfig
import com.lauzhack.server.routes.agentRoutes
import com.lauzhack.server.routes.apiRoutes
import io.ktor.http.CacheControl
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import java.io.File

fun Application.module() {
    initDatabase()

    fun setting(key: String) = environment.config.propertyOrNull(key)?.getString()?.takeIf { it.isNotBlank() }
    val ai = AiModels(
        togetherApiKey = setting("ai.togetherApiKey"),
        ocrModelId = setting("ai.ocrModel") ?: error("ai.ocrModel missing"),
        agentModelId = setting("ai.agentModel") ?: error("ai.agentModel missing"),
    )
    if (ai.ocrModel == null) environment.log.warn("TOGETHER_API_KEY not set: OCR, hints and plots disabled")

    val ocrReasoningEnabled = setting("ai.ocrReasoningEnabled")?.toBooleanStrict() ?: false
    val agentEffort = ReasoningEffort.valueOf(setting("ai.agentReasoningEffort")?.uppercase() ?: "HIGH")
    val ocr = OcrService(
        recognizer = ai.ocrModel?.let { KoogChunkRecognizer(ai.executor, it, reasoningEnabled = ocrReasoningEnabled) },
        scope = this,
    )
    ocr.start(ChunkService.events)

    val hints = HintService(
        scope = this,
        pageTranscription = ocr::snapshot,
    )
    val agent = AgentService(
        agent = ai.agentModel?.let { KoogPageAgent(ai.executor, it, jsonConfig, reasoningEffort = agentEffort) },
        ocr = ocr,
        hints = hints,
        scope = this,
        json = jsonConfig,
        exercise = { id -> getDocument(id)?.exercise.orEmpty() },
        hintLevel = { id -> getDocument(id)?.hintLevel ?: com.lauzhack.shared.hint.HintLevel.MEDIUM },
    )
    agent.start(ocr.events)
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

    val exerciseImport = ai.ocrModel?.let { model ->
        ExerciseImportService(KoogRegionConverter(ai.executor, model)) { png -> assetUrl(saveAsset(png)) }
    }

    routing {
        exerciseRoutes(exerciseImport) { base64 -> saveAsset(Base64.decode(base64)) }
        apiRoutes(ocr, hints, agent)
        agentRoutes(agent)
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

        // SPA fallback: serve real files from dist if they exist, otherwise the
        // app shell (so URLs like /doc:123 work on direct visits)
        get("/{...}") {
            val path = call.request.path()
            if (path.startsWith("/api")) {
                call.respondText("", status = HttpStatusCode.NotFound)
            } else {
                val requested = path.removePrefix("/")
                val file = File(dist, requested)
                val safe = file.isFile && file.canonicalPath.startsWith(dist.canonicalPath + File.separator)
                if (safe) {
                    call.respondFile(file)
                } else {
                    call.respondFile(File(dist, "index.html"))
                }
            }
        }
    }
}
