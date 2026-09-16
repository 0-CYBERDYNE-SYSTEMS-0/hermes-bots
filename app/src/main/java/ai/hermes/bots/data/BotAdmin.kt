package ai.hermes.bots.data

import ai.hermes.bots.protocol.Catalog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Pure builders/parsers for the bot-admin RPCs (PROTOCOL.md §5.3). */
object BotAdmin {

    fun createParams(
        name: String,
        description: String? = null,
        cloneFrom: String? = null,
        cloneAll: Boolean = false,
        soul: String? = null,
        model: String? = null,
        provider: String? = null,
    ): JsonObject = buildJsonObject {
        put("name", name)
        description?.let { put("description", it) }
        cloneFrom?.let { put("clone_from", it) }
        if (cloneAll) put("clone_all", true)
        soul?.let { put("soul", it) }
        if (model != null && provider != null) {
            put("model", model)
            put("provider", provider)
        }
    }

    fun describeParams(name: String): JsonObject = buildJsonObject { put("name", name) }

    data class UiMetaPatch(
        val sectionId: String? = null,
        val hidden: Boolean? = null,
        val extra: JsonObject? = null,
    )

    /** Deep-merge a hermes-bots ui_meta patch over the current value. */
    fun mergeUiMeta(current: JsonObject?, patch: UiMetaPatch): JsonObject {
        val section = (current?.get(Catalog.UI_META_KEY) as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        patch.sectionId?.let { section["sectionId"] = JsonPrimitive(it) }
        patch.hidden?.let { section["hidden"] = JsonPrimitive(it) }
        patch.extra?.forEach { (k, v) -> section[k] = v }
        return buildJsonObject {
            putJsonObject(Catalog.UI_META_KEY) {
                section.forEach { (k, v) -> put(k, v) }
            }
        }
    }

    fun configureParams(
        name: String,
        uiMeta: JsonObject? = null,
        expectedRevisions: Map<String, Int>? = null,
        soul: String? = null,
        description: String? = null,
        model: String? = null,
        provider: String? = null,
        confirmExpensiveModel: Boolean = false,
        disabledSkills: List<String>? = null,
        enabledToolsets: List<String>? = null,
        enabledMcpServers: List<String>? = null,
    ): JsonObject = buildJsonObject {
        put("name", name)
        uiMeta?.let {
            put("ui_meta", it)
            expectedRevisions?.let { revs ->
                putJsonObject("ui_meta_expected_revisions") {
                    revs.forEach { (k, v) -> put(k, v) }
                }
            }
        }
        soul?.let { put("soul", it) }
        description?.let { put("description", it) }
        if (model != null && provider != null) {
            put("model", model)
            put("provider", provider)
            if (confirmExpensiveModel) put("confirm_expensive_model", true)
        }
        disabledSkills?.let { put("disabled_skills", JsonArray(it.map { s -> JsonPrimitive(s) })) }
        enabledToolsets?.let { put("enabled_toolsets", JsonArray(it.map { s -> JsonPrimitive(s) })) }
        enabledMcpServers?.let { put("enabled_mcp_servers", JsonArray(it.map { s -> JsonPrimitive(s) })) }
    }

    fun setAssetParams(name: String, dataUrl: String): JsonObject = buildJsonObject {
        put("name", name)
        put("asset", Catalog.ASSET_AVATAR)
        put("data", dataUrl)
    }

    data class DescribeSnapshot(
        val name: String,
        val description: String,
        val soul: String,
        val modelProvider: String,
        val modelDefault: String,
        val skills: List<SkillRow>,
        val toolsets: List<ToolsetRow>,
        val mcpServers: List<McpRow>,
    )

    data class SkillRow(val name: String, val enabled: Boolean)
    data class ToolsetRow(val name: String, val enabled: Boolean)
    data class McpRow(val name: String, val enabled: Boolean, val transport: String)

    fun parseDescribe(result: JsonObject): DescribeSnapshot {
        val model = result["model"] as? JsonObject
        fun rows(key: String): List<Pair<String, Boolean>> =
            (result[key] as? JsonArray)?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val n = (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                n to ((o["enabled"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true)
            }.orEmpty()
        return DescribeSnapshot(
            name = str(result, "name") ?: "",
            description = str(result, "description") ?: "",
            soul = str(result, "soul") ?: "",
            modelProvider = str(model, "provider") ?: "",
            modelDefault = str(model, "default") ?: "",
            skills = rows("skills").map { SkillRow(it.first, it.second) },
            toolsets = rows("toolsets").map { ToolsetRow(it.first, it.second) },
            mcpServers = (result["mcp_servers"] as? JsonArray)?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                McpRow(
                    name = str(o, "name") ?: return@mapNotNull null,
                    enabled = (o["enabled"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true,
                    transport = str(o, "transport") ?: "http",
                )
            }.orEmpty(),
        )
    }

    data class ConfigureResult(
        val ok: Boolean,
        val confirmRequired: Boolean,
        val confirmMessage: String?,
    )

    fun parseConfigure(result: JsonObject): ConfigureResult = ConfigureResult(
        ok = (result["ok"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true,
        confirmRequired = (result["confirm_required"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false,
        confirmMessage = str(result, "confirm_message"),
    )

    // --- model catalog / keys (MODEL-UX-PUNCHLIST.md rev 2 / P1, P3, P7) ---

    /**
     * Params for `model.options` (tui_gateway/methods_complete.py:277-286): the picker asks for
     * UNCONFIGURED rows too, so the key-paste flow (P7) can offer them. `refresh: true` forces a
     * server-side catalog rebuild (R8: empty models on an authenticated row is real, e.g.
     * openrouter).
     */
    fun modelOptionsParams(includeUnconfigured: Boolean = true, refresh: Boolean = false): JsonObject =
        buildJsonObject {
            put("include_unconfigured", includeUnconfigured)
            if (refresh) put("refresh", true)
        }

    /** Params for `model.save_key` `{slug, api_key}` (tui_gateway/methods_complete.py:289-320). */
    fun saveKeyParams(slug: String, apiKey: String): JsonObject = buildJsonObject {
        put("slug", slug)
        put("api_key", apiKey)
    }

    /**
     * `profiles.create` result `{ok, name, path, soul_written, model_set, mirrored{...}}`
     * (tui_gateway/methods_profiles.py:367-375). R3: the echoed `name` is the RAW input while
     * the server lowercases it (hermes_cli/profiles.py:184-186,843) — the canonical profile id
     * is the last `path` component, so prefer it and fall back to `name`. `model_set` is only
     * meaningful when BOTH model and provider were sent.
     */
    data class CreateOutcome(
        val ok: Boolean,
        val name: String,
        val modelSet: Boolean,
        val path: String?,
    )

    fun parseCreate(result: JsonObject): CreateOutcome {
        val path = str(result, "path")?.takeIf { it.isNotBlank() }
        val canonical = path?.trim('/')?.substringAfterLast('/').orEmpty()
        return CreateOutcome(
            ok = boolOr(result, "ok", default = true),
            name = canonical.ifBlank { str(result, "name") ?: "" },
            modelSet = boolOr(result, "model_set", default = false),
            path = path,
        )
    }

    /**
     * Extended configure parse (R9): the result carries an `applied` dict of which patches
     * landed (tui_gateway/methods_profiles.py:483-502); `applied.model` is true/false when a
     * model pin was part of the request and ABSENT otherwise (null here). The existing
     * [ConfigureResult]/[parseConfigure] are kept untouched for BotEditorViewModel
     * compatibility.
     */
    data class ConfigureOutcome(
        val ok: Boolean,
        val modelApplied: Boolean?,
        val confirmRequired: Boolean,
        val confirmMessage: String?,
    )

    fun parseConfigureOutcome(result: JsonObject): ConfigureOutcome {
        val applied = result["applied"] as? JsonObject
        return ConfigureOutcome(
            ok = boolOr(result, "ok", default = true),
            modelApplied = applied?.let { boolOrNull(it, "model") },
            confirmRequired = boolOr(result, "confirm_required", default = false),
            confirmMessage = str(result, "confirm_message"),
        )
    }

    /** data-URL sanity per PROTOCOL.md §5.3 (≤2 MB; png/jpeg/webp). Returns null when invalid. */
    fun validateAvatarDataUrl(dataUrl: String): String? {
        val okPrefix = dataUrl.startsWith("data:image/png;base64,") ||
            dataUrl.startsWith("data:image/jpeg;base64,") ||
            dataUrl.startsWith("data:image/webp;base64,")
        if (!okPrefix) return null
        val base64 = dataUrl.substringAfter(";base64,")
        val approxBytes = base64.length * 3 / 4
        return if (approxBytes <= Catalog.MAX_AVATAR_BYTES) dataUrl else null
    }

    private fun str(obj: JsonObject?, key: String): String? =
        (obj?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun boolOr(obj: JsonObject, key: String, default: Boolean): Boolean =
        (obj[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: default

    private fun boolOrNull(obj: JsonObject, key: String): Boolean? =
        (obj[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
}
