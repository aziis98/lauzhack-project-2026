package com.lauzhack.server.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.api.ExposedBlob
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

/** Binary files referenced by documents, e.g. figures of imported exercises. */
object AssetsTable : Table("assets") {
    val id = varchar("id", 64)
    val mimeType = varchar("mime_type", 64)
    val data = blob("data")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

class Asset(val id: String, val mimeType: String, val bytes: ByteArray)

fun saveAsset(bytes: ByteArray, mimeType: String = "image/png"): String = transaction {
    val id = UUID.randomUUID().toString()
    AssetsTable.insert { row ->
        row[AssetsTable.id] = id
        row[AssetsTable.mimeType] = mimeType
        row[AssetsTable.data] = ExposedBlob(bytes)
        row[AssetsTable.createdAt] = System.currentTimeMillis()
    }
    id
}

fun getAsset(id: String): Asset? = transaction {
    AssetsTable.selectAll().where { AssetsTable.id eq id }.firstOrNull()?.let {
        Asset(it[AssetsTable.id], it[AssetsTable.mimeType], it[AssetsTable.data].bytes)
    }
}

/** Public URL of an asset, as embedded in exercise Markdown. */
fun assetUrl(id: String) = "/api/assets/$id"
