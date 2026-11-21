package com.lauzhack.server.db

import com.lauzhack.shared.domain.Document
import com.lauzhack.shared.domain.Element
import com.lauzhack.shared.domain.ElementData
import com.lauzhack.shared.domain.Style
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** Must mirror the client-side JSON config (see DrawingApi.defaultJson). */
val jsonConfig = Json {
    classDiscriminator = "kind"
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun initDatabase() {
    Database.connect("jdbc:sqlite:data.db", driver = "org.sqlite.JDBC")
    transaction {
        SchemaUtils.create(DocumentsTable, ElementsTable)
    }
    // Databases created before the exercise column existed: add it (fails harmlessly if present).
    runCatching {
        transaction { exec("ALTER TABLE documents ADD COLUMN exercise TEXT NOT NULL DEFAULT ''") }
    }
    seedExampleExercises()
}

fun ResultRow.toDocument(): Document = Document(
    id = this[DocumentsTable.id],
    name = this[DocumentsTable.name],
    exercise = this[DocumentsTable.exercise],
    updatedAt = this[DocumentsTable.updatedAt],
)

fun ResultRow.toElement(): Element = Element(
    id = this[ElementsTable.id],
    type = this[ElementsTable.type],
    x = this[ElementsTable.x],
    y = this[ElementsTable.y],
    rotation = this[ElementsTable.rotation],
    zIndex = this[ElementsTable.zIndex],
    parentId = this[ElementsTable.parentId],
    style = this[ElementsTable.style]?.let { jsonConfig.decodeFromString<Style>(it) },
    data = jsonConfig.decodeFromString<ElementData>(this[ElementsTable.data]),
)

fun elementDataJson(data: ElementData): String = jsonConfig.encodeToString(data)

fun styleJson(style: Style?): String? = style?.let { jsonConfig.encodeToString(it) }
