package com.lauzhack.server.routes

import com.lauzhack.server.db.assetUrl
import com.lauzhack.server.db.getAsset
import com.lauzhack.server.exercise.ExerciseImportService
import com.lauzhack.shared.exercise.AssetRef
import com.lauzhack.shared.exercise.ConvertRegionRequest
import com.lauzhack.shared.exercise.UploadAssetRequest
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * Exercise import from PDF and the assets (figures) it produces.
 *
 * @param import null when no model is configured: conversion answers 503
 */
fun Route.exerciseRoutes(import: ExerciseImportService?, saveFigure: (String) -> String) {
    // A region the user selected on a PDF page → Markdown + LaTeX (+ figures).
    post("/api/exercises/convert") {
        val request = call.receive<ConvertRegionRequest>()
        if (import == null) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "TOGETHER_API_KEY not set"))
        } else {
            call.respond(import.convert(request))
        }
    }

    // A figure the user cropped by hand.
    post("/api/assets") {
        val request = call.receive<UploadAssetRequest>()
        val id = saveFigure(request.imagePngBase64)
        call.respond(HttpStatusCode.Created, AssetRef(id, assetUrl(id)))
    }

    get("/api/assets/{aid}") {
        val asset = getAsset(call.parameters["aid"]!!)
        if (asset == null) {
            call.respond(HttpStatusCode.NotFound)
        } else {
            // ids are random and assets immutable: cache forever
            call.response.cacheControl(CacheControl.MaxAge(maxAgeSeconds = 31_536_000))
            call.respondBytes(asset.bytes, ContentType.parse(asset.mimeType))
        }
    }
}
