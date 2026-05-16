package com.mythara.agent

import android.util.Log
import com.mythara.memory.Tier
import com.mythara.secret.observe.embed.EmbeddingsModelStore
import com.mythara.secret.observe.embed.LocalEmbedder
import com.mythara.secret.observe.vault.LearningVault
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single chokepoint for persisting one message of a conversation
 * (incoming or outgoing) into the learning vault, facetted so the
 * analytics layer can group by contact.
 *
 * Used by:
 *   - [AutoReplyDispatcher] when an incoming notification arrives
 *     (direction = "incoming")
 *   - [ToolRegistry] when send_whatsapp_direct / send_sms_direct
 *     fires successfully in an auto-reply turn
 *     (direction = "outgoing")
 *
 * Pulled out of those classes to avoid duplicating the vault-write
 * logic and to dodge a Hilt cycle (dispatcher → registry would
 * pull in agent loop). Both callers depend on this singleton
 * directly.
 *
 * Failure modes (vault.add throws, embedder unavailable) are swallowed
 * and logged — never block the caller's send path on an analytics
 * write.
 */
@Singleton
class ConversationMessageWriter @Inject constructor(
    private val vault: LearningVault,
    private val embedder: LocalEmbedder,
    /** Capability Expansion v3 — dual-write so the new
     *  ProfileDetail "recent interactions" panel + the
     *  GlassesMemoryScreen don't have to scan the vault by facet
     *  on every render. */
    private val interactionRepo: com.mythara.analytics.interactions.ContactInteractionRepository,
) {
    suspend fun record(
        contactName: String,
        body: String,
        pkg: String,
        direction: String,
    ) {
        val text = body.trim()
        if (contactName.isBlank() || text.isBlank()) return
        val displayContact = contactName.trim()
        val content = if (direction == "outgoing") "User to $displayContact: $text"
                      else "$displayContact to user: $text"
        val embedding = runCatching {
            if (embedder.isReady()) embedder.embed(content) else null
        }.getOrNull()
        val facets = buildList {
            add("kind:message-history")
            add("source:notification")
            add("contact:$displayContact")
            if (pkg.isNotBlank()) add("app:$pkg")
            add("direction:$direction")
        }
        val now = System.currentTimeMillis()
        runCatching {
            vault.add(
                content = content,
                tier = Tier.Working,
                src = "msg:$pkg",
                facets = facets,
                embedding = embedding,
                embModel = if (embedding != null) EmbeddingsModelStore.MODEL_ID else null,
                conf = 0.75,
                now = now,
            )
        }.onFailure { Log.w(TAG, "vault.add for $direction message failed: ${it.message}") }

        // v3 dual-write to the structured interactions log.
        runCatching {
            interactionRepo.dao.insert(
                com.mythara.analytics.interactions.ContactInteractionRow(
                    nameKey = displayContact.lowercase(),
                    tsMs = now,
                    kind = if (direction == "outgoing") "message_sent" else "message_received",
                    source = "notification",
                    note = pkg.ifBlank { null },
                ),
            )
        }.onFailure { Log.w(TAG, "interactions.insert for $direction failed: ${it.message}") }
    }

    companion object {
        private const val TAG = "Mythara/ConvWrite"
    }
}
