package ai.hermes.bots.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime

/** Pure parsers for profiles.list rows — JVM-testable. */
object RosterParsing {

    fun lastActiveMs(value: JsonElement?): Long? = when (value) {
        is JsonPrimitive -> when {
            value.isString -> parseIso(value.content)
            // Server sends epoch SECONDS as a float (e.g. 1788676747.686); treat big integers as ms.
            else -> value.content.toDoubleOrNull()?.let { if (it < 1e11) (it * 1000).toLong() else it.toLong() }
        }
        else -> null
    }

    private fun parseIso(text: String): Long? =
        runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
            .getOrElse { runCatching { Instant.parse(text).toEpochMilli() }.getOrNull() }

    private fun uiMetaBotSection(row: JsonObject): JsonObject? =
        (row["ui_meta"] as? JsonObject)?.get("hermes-bots") as? JsonObject

    /**
     * True when the profile carries ui_meta["hermes-bots"] — the flag that makes gateways
     * inject `message_agent` into this bot's chats (tools/bot_mode_probe.py
     * is_bot_mode_managed). Note the server only includes NON-empty ui_meta in profiles.list
     * rows (methods_profiles.py _profile_ui_meta_fields), so a profile flagged with an empty
     * payload reads as not-capable here even though the injection gate would pass.
     */
    fun relayCapable(row: JsonObject): Boolean = uiMetaBotSection(row) != null

    /** (sectionId, hidden) from ui_meta["hermes-bots"] (PROTOCOL.md §5.3 identity convention). */
    fun sectionAndHidden(row: JsonObject): Pair<String?, Boolean> {
        val section = uiMetaBotSection(row)
        val sectionId = (section?.get("sectionId") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val hidden = (section?.get("hidden") as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
        return sectionId to hidden
    }

    fun revisions(row: JsonObject): Map<String, Int> =
        (row["ui_meta_revisions"] as? JsonObject)
            ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.toIntOrNull()?.let { k to it } }
            ?.toMap()
            .orEmpty()

    fun botRow(connectionId: String, element: JsonElement): BotRow? {
        val row = element as? JsonObject ?: return null
        val name = (row["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: return null
        val (sectionId, hidden) = sectionAndHidden(row)
        val canonical = row["canonical_session"] as? JsonObject
        val lastSession = row["last_session"] as? JsonObject
        val workerSession = row["worker_session"] as? JsonObject
        // Blank server strings mean "unset" — normalize to null so UI fallbacks (name, model…) engage.
        fun str(key: String): String? =
            (row[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        return BotRow(
            connectionId = connectionId,
            name = name,
            displayName = str("display_name"),
            description = str("description"),
            model = str("model"),
            provider = str("provider"),
            skillCount = (row["skill_count"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
            isDefault = (row["is_default"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
            hasAvatar = (row["has_avatar"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
            sectionId = sectionId,
            hidden = hidden,
            lastPreview = lastSession?.get("preview")?.let {
                (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { p -> p.isNotBlank() }
            },
            lastActiveMs = lastActiveMs(lastSession?.get("last_active")),
            workerActiveMs = lastActiveMs(workerSession?.get("last_active")),
            canonicalSessionId = (canonical?.get("resolved_id") as? JsonPrimitive)?.takeIf { it.isString }?.content,
            canonicalRootTitle = (canonical?.get("root_title") as? JsonPrimitive)?.takeIf { it.isString }?.content,
            uiMetaRevisions = revisions(row),
        )
    }
}
