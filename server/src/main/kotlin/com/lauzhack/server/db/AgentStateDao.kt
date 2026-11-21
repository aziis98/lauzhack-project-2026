package com.lauzhack.server.db

import com.lauzhack.shared.agent.ConversationMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * The page agent's conversation, persisted per document: the model's memory
 * (hints given, mistakes flagged, functions plotted) survives server restarts,
 * and every document keeps its own conversation.
 */
object AgentStateDao {

    /** Saves the document's conversation, replacing any previous one. */
    fun save(documentId: String, messages: List<ConversationMessage>) {
        transaction {
            AgentStateTable.deleteWhere { AgentStateTable.documentId eq documentId }
            AgentStateTable.insert {
                it[AgentStateTable.documentId] = documentId
                it[AgentStateTable.messages] = jsonConfig.encodeToString(messages)
                it[AgentStateTable.updatedAt] = System.currentTimeMillis()
            }
        }
    }

    /** The document's stored conversation, or null when it never had one. */
    fun load(documentId: String): List<ConversationMessage>? {
        val row = transaction {
            AgentStateTable.selectAll().where { AgentStateTable.documentId eq documentId }
                .limit(1).firstOrNull()
        } ?: return null
        return runCatching {
            jsonConfig.decodeFromString<List<ConversationMessage>>(row[AgentStateTable.messages])
        }.getOrNull()
    }
}
