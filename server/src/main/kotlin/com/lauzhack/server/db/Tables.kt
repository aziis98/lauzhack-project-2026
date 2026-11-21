package com.lauzhack.server.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

object DocumentsTable : Table("documents") {
    val id = varchar("id", 128)
    val name = varchar("name", 256)
    val exercise = text("exercise").default("")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

/**
 * Generic drawing elements. The type-specific payload (`data`) and `style` are
 * stored as JSON, so new element kinds never require a schema migration.
 */
object ElementsTable : Table("elements") {
    val id = varchar("id", 128)
    val documentId = varchar("document_id", 128)
        .references(DocumentsTable.id, onDelete = ReferenceOption.CASCADE)
    val parentId = varchar("parent_id", 128).nullable()
    val type = varchar("type", 64)
    val x = double("x")
    val y = double("y")
    val rotation = double("rotation").default(0.0)
    val zIndex = varchar("z_index", 128)
    val style = text("style").nullable()
    val data = text("data")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}
