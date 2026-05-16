package com.mythara.agent.tools

import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.services.NotificationActionStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `list_dismissed_notifications` — what did Mythara quietly dismiss?
 *
 * Companion to the smart-auto-action notification mode. When the
 * user asks "did I miss anything earlier today?" or "what
 * notifications did you dismiss?", this tool surfaces the recent
 * auto-dismissal log so Mythara can summarise them.
 *
 * Stored in [NotificationActionStore]'s rolling log, capped at 100
 * entries. The user can ask after the fact and Mythara can either
 * read them out or filter ("anything from Mom?").
 */
@Singleton
class ListDismissedNotificationsTool @Inject constructor(
    private val store: NotificationActionStore,
) : Tool {

    @Serializable
    data class Entry(
        val pkg: String,
        val title: String? = null,
        val text: String? = null,
        val tsMillis: Long,
    )

    @Serializable
    data class Response(val count: Int, val entries: List<Entry>)

    override val name: String = "list_dismissed_notifications"

    override val description: String =
        "Recent notifications Mythara auto-dismissed on the user's behalf. " +
            "Use when the user asks 'what did I miss', 'did you dismiss anything', " +
            "'show me anything from <app>'. Returns up to the last 100 dismissals " +
            "with package, title, text, timestamp. Empty list when auto-action mode is off " +
            "or no dismissals have happened yet."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "limit",
                    buildJsonObject {
                        put("type", "integer")
                        put("description", "Max entries to return (newest first). Default 20, max 100.")
                    },
                )
                put(
                    "package_filter",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Optional case-insensitive substring filter on package (e.g. 'whatsapp', 'slack').")
                    },
                )
            },
        )
        put("required", JsonArray(emptyList()))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val limit = ((args["limit"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 20).coerceIn(1, 100)
        val pkgFilter = (args["package_filter"] as? JsonPrimitive)?.content?.lowercase()?.trim().orEmpty()

        val all = store.recentDismissals(limit = 100)
        val filtered = if (pkgFilter.isEmpty()) all
            else all.filter { it.pkg.lowercase().contains(pkgFilter) }

        val out = filtered.take(limit).map { e ->
            Entry(pkg = e.pkg, title = e.title, text = e.text, tsMillis = e.tsMillis)
        }
        return ToolResult(
            ok = true,
            output = JSON.encodeToString(
                Response.serializer(),
                Response(count = out.size, entries = out),
            ),
        )
    }

    companion object {
        private val JSON = Json { encodeDefaults = false; prettyPrint = false }
    }
}
