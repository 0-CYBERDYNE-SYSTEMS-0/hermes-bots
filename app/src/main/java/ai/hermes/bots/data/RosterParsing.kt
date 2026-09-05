package ai.hermes.bots.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime

/** Pure parsers for profiles.list rows — JVM-testable. */
object RosterParsing {

    fun lastActiveMs(value: JsonElement?): Long? = when (value) {
        is JsonPrimitive ->
            if (value.isString) parseIso(value.content) else value.content.toLongOrNull()
        else -> null
    }

    private fun parseIso(text: String): Long? =
        runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
            .getOrElse { runCatching { Instant.parse(text).toEpochMilli() }.getOrNull() }

    private fun uiMetaBotSection(row: JsonObject): JsonObject? =
        (row["ui_meta"] as? JsonObject)?.get("hermes-bots") as? JsonObject

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
