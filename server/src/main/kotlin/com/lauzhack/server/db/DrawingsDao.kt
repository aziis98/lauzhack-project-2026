package com.lauzhack.server.db

import com.lauzhack.shared.domain.Document
import com.lauzhack.shared.domain.Element
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

private fun now() = System.currentTimeMillis()

fun listDocuments(): List<Document> = transaction {
    DocumentsTable
        .selectAll()
        .orderBy(DocumentsTable.updatedAt to SortOrder.DESC)
        .map { it.toDocument() }
}

fun createDocument(name: String): Document = transaction {
    val ts = now()
    val doc = Document("doc:" + UUID.randomUUID(), name, ts)
    DocumentsTable.insert { row ->
        row[DocumentsTable.id] = doc.id
        row[DocumentsTable.name] = doc.name
        row[DocumentsTable.createdAt] = ts
        row[DocumentsTable.updatedAt] = ts
    }
    doc
}

fun getDocument(id: String): Document? = transaction {
    DocumentsTable
        .selectAll()
        .where { DocumentsTable.id eq id }
        .firstOrNull()
        ?.toDocument()
}

/**
 * Resolves a document by full id (`doc:<uuid>`) or by its short form:
 * the first 16 hex chars of the uuid, used in the `/doc/{hex}` URL path.
 */
fun resolveDocument(idOrShort: String): Document? = transaction {
    when {
        idOrShort.startsWith("doc:") -> getDocument(idOrShort)

        idOrShort.matches(Regex("[0-9a-f]{16}")) -> {
            // dash the short hex the way a uuid is formatted: 8-4-4, then prefix-match
            val dashed = idOrShort.substring(0, 8) + "-" + idOrShort.substring(8, 12) + "-" + idOrShort.substring(12, 16)
            DocumentsTable
                .selectAll()
                .where { DocumentsTable.id like ("doc:$dashed%") }
                .firstOrNull()
                ?.toDocument()
        }

        else -> null
    }
}

fun deleteDocument(id: String): Boolean = transaction {
    // Delete children explicitly: SQLite FK enforcement is off by default.
    ElementsTable.deleteWhere { ElementsTable.documentId eq id }
    DocumentsTable.deleteWhere { DocumentsTable.id eq id } > 0
}

fun listElements(documentId: String): List<Element> = transaction {
    ElementsTable
        .selectAll()
        .where { ElementsTable.documentId eq documentId }
        .orderBy(ElementsTable.zIndex to SortOrder.ASC)
        .map { it.toElement() }
}

/** Batch sync used by the web frontend's autosave: upsert incoming, delete missing. */
fun replaceElements(documentId: String, elements: List<Element>) = transaction {
    val ts = now()
    val existingIds = ElementsTable
        .select(ElementsTable.id)
        .where { ElementsTable.documentId eq documentId }
        .map { it[ElementsTable.id] }
        .toHashSet()
    val incomingIds = elements.mapTo(hashSetOf()) { it.id }

    val removed = existingIds - incomingIds
    if (removed.isNotEmpty()) {
        ElementsTable.deleteWhere {
            (ElementsTable.documentId eq documentId) and (ElementsTable.id inList removed)
        }
    }

    elements.forEach { el ->
        val dataJson = elementDataJson(el.data)
        val styleJson = styleJson(el.style)
        if (el.id in existingIds) {
            ElementsTable.update({ ElementsTable.id eq el.id }) { row ->
                row[ElementsTable.parentId] = el.parentId
                row[ElementsTable.type] = el.type
                row[ElementsTable.x] = el.x
                row[ElementsTable.y] = el.y
                row[ElementsTable.rotation] = el.rotation
                row[ElementsTable.zIndex] = el.zIndex
                row[ElementsTable.style] = styleJson
                row[ElementsTable.data] = dataJson
                row[ElementsTable.updatedAt] = ts
            }
        } else {
            ElementsTable.insert { row ->
                row[ElementsTable.id] = el.id
                row[ElementsTable.documentId] = documentId
                row[ElementsTable.parentId] = el.parentId
                row[ElementsTable.type] = el.type
                row[ElementsTable.x] = el.x
                row[ElementsTable.y] = el.y
                row[ElementsTable.rotation] = el.rotation
                row[ElementsTable.zIndex] = el.zIndex
                row[ElementsTable.style] = styleJson
                row[ElementsTable.data] = dataJson
                row[ElementsTable.createdAt] = ts
                row[ElementsTable.updatedAt] = ts
            }
        }
    }

    DocumentsTable.update({ DocumentsTable.id eq documentId }) { row ->
        row[DocumentsTable.updatedAt] = ts
    }
}

fun createElement(documentId: String, element: Element): Boolean = transaction {
    if (DocumentsTable.selectAll().where { DocumentsTable.id eq documentId }.firstOrNull() == null) {
        return@transaction false
    }
    val ts = now()
    ElementsTable.insert { row ->
        row[ElementsTable.id] = element.id
        row[ElementsTable.documentId] = documentId
        row[ElementsTable.parentId] = element.parentId
        row[ElementsTable.type] = element.type
        row[ElementsTable.x] = element.x
        row[ElementsTable.y] = element.y
        row[ElementsTable.rotation] = element.rotation
        row[ElementsTable.zIndex] = element.zIndex
        row[ElementsTable.style] = styleJson(element.style)
        row[ElementsTable.data] = elementDataJson(element.data)
        row[ElementsTable.createdAt] = ts
        row[ElementsTable.updatedAt] = ts
    }
    true
}

/** Full replace of a single element (body must contain the complete element). */
fun updateElement(documentId: String, elementId: String, element: Element): Element? = transaction {
    val updated = ElementsTable.update({
        (ElementsTable.documentId eq documentId) and (ElementsTable.id eq elementId)
    }) { row ->
        row[ElementsTable.parentId] = element.parentId
        row[ElementsTable.type] = element.type
        row[ElementsTable.x] = element.x
        row[ElementsTable.y] = element.y
        row[ElementsTable.rotation] = element.rotation
        row[ElementsTable.zIndex] = element.zIndex
        row[ElementsTable.style] = styleJson(element.style)
        row[ElementsTable.data] = elementDataJson(element.data)
        row[ElementsTable.updatedAt] = now()
    }
    if (updated == 0) null else element.copy(id = elementId)
}

fun deleteElement(documentId: String, elementId: String): Boolean = transaction {
    ElementsTable.deleteWhere {
        (ElementsTable.documentId eq documentId) and (ElementsTable.id eq elementId)
    } > 0
}
