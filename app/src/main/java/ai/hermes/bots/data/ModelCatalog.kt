package ai.hermes.bots.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One provider row of a `model.options` result (MODEL-UX-PUNCHLIST.md rev 2 / P1).
 *
 * Protocol facts (verified 2026-09-13 against ~/.hermes/hermes-agent):
 * - RPC `model.options` with params `{include_unconfigured: true}` (tui_gateway/
 *   methods_complete.py:277-286) returns `{"providers": [...], "model": str, "provider": str}`.
 * - Per-provider row fields (hermes_cli/inventory.py:74-148, 548-562): `slug, name, is_current,
 *   is_user_defined, models[] (bare strings), total_models, source, authenticated(bool),
 *   auth_type, key_env, warning, featured_models[], aliases[], capabilities{}`.
 * - `pricing` may be absent (cache-gated) and is intentionally NOT modeled.
 * - `auth_type`/`key_env`/`warning` populate reliably only on UNAUTHENTICATED canonical rows;
 *   `aliases` only on is_user_defined rows (live: ["clinepass", "custom:clinepass",
 *   "custom:custom:clinepass"]); `featured_models` is empty for custom:* rows, so featured-first
 *   sorting is a no-op there (R8 — fine).
 * - `authenticated` is credential PRESENCE, not validity.
 */
data class ProviderOption(
    val slug: String,
    val name: String,
    val authenticated: Boolean,
    val authType: String?,
    val keyEnv: String?,
    val warning: String?,
    val models: List<String>,
    val featured: List<String>,
    val aliases: List<String>,
    val isCurrent: Boolean,
)

/** Pure parsing/matching helpers over `model.options` results — no gateway access. */
object ModelCatalog {

    /**
     * Parse the `providers` array of a `model.options` result. Tolerant of missing fields
     * (lists default empty; a missing `authenticated` field means "assume configured" so a
     * parser bug never hides providers — explicit `false` still wins). Nothing is filtered and
     * server order is preserved (canonical ordering is server-side). Rows that are not objects
     * or carry no slug are skipped.
     */
    fun parseProviders(result: JsonObject): List<ProviderOption> {
        val rows = result["providers"] as? JsonArray ?: return emptyList()
        return rows.mapNotNull { el ->
            val row = el as? JsonObject ?: return@mapNotNull null
            val slug = str(row, "slug")
            if (slug.isNullOrBlank()) return@mapNotNull null
            ProviderOption(
                slug = slug,
                name = str(row, "name") ?: "",
                authenticated = boolOr(row, "authenticated", default = true),
                authType = str(row, "auth_type"),
                keyEnv = str(row, "key_env"),
                warning = str(row, "warning"),
                models = stringList(row, "models"),
                featured = stringList(row, "featured_models"),
                aliases = stringList(row, "aliases"),
                isCurrent = boolOr(row, "is_current", default = false),
            )
        }
    }

    /** Humane label: the server name when non-blank, else the slug. */
    fun displayName(provider: ProviderOption): String =
        provider.name.trim().ifBlank { provider.slug }

    /**
     * The gateway's CURRENT model pin from the result's top-level `model`/`provider`
     * fields (`hermes_cli/inventory.py:148`) — the combo already working on this gateway,
     * used to prefill new bots so the default path needs zero manual entry. Null when
     * either half is blank.
     */
    fun parseCurrentPair(result: JsonObject): Pair<String, String>? {
        val provider = str(result, "provider")?.trim().orEmpty()
        val model = str(result, "model")?.trim().orEmpty()
        return if (provider.isBlank() || model.isBlank()) null else provider to model
    }

    /**
     * R14: start-from/prefill provider matching must accept live aliases, not just exact slug
     * equality (exact equality silently misses when one context spells it
     * `custom:clinepass` and the other `clinepass`). Case-insensitive.
     */
    fun matchByAlias(provider: ProviderOption, needle: String): Boolean {
        val n = needle.trim().lowercase()
        if (n.isEmpty()) return false
        if (provider.slug.lowercase() == n) return true
        return provider.aliases.any { it.trim().lowercase() == n }
    }

    /**
     * Models of one provider, featured-first then natural (server) order, de-duplicated.
     * Lookup is exact-slug first with an alias fallback (R14). Featured entries not present in
     * `models` are ignored (never surface a model the row does not list).
     */
    fun modelsFor(providers: List<ProviderOption>, providerSlug: String): List<String> {
        val provider = providers.firstOrNull { it.slug == providerSlug }
            ?: providers.firstOrNull { matchByAlias(it, providerSlug) }
            ?: return emptyList()
        if (provider.models.isEmpty()) return emptyList()
        val featuredIndex = provider.featured.withIndex().associate { (i, m) -> m to i }
        val (featured, rest) = provider.models.partition { it in featuredIndex }
        return (featured.sortedBy { featuredIndex.getValue(it) } + rest).distinct()
    }

    /**
     * A2 vendor-prefix heuristic: a `custom:*` provider's model id needs a `vendor/` prefix
     * (the exact live breakage — the endpoint sends the id verbatim, per
     * hermes_cli/runtime_provider_custom.py:452-517, so bare ids 400 at turn time).
     */
    fun needsVendorPrefixWarning(providerSlug: String, modelId: String): Boolean =
        providerSlug.trim().lowercase().startsWith("custom") && !modelId.contains('/')

    /**
     * Client-side bot-name normalization (R3/A6): the server lowercases via
     * normalize_profile_name (hermes_cli/profiles.py:184-186,843) and echoes the RAW name, so
     * the app must send the normalized value itself and use it for roster matching.
     *
     * Server shape: `^[a-z0-9][a-z0-9_-]{0,63}$` (hermes_cli/profiles.py:24). Rule:
     * lowercase; whitespace runs become "-"; drop everything outside [a-z0-9_-]; collapse each
     * separator run into ONE separator ("-" when the run contains a dash, else "_" so lone
     * intentional underscores survive); trim separators at both ends; truncate to 64 chars
     * (server max) and re-trim trailing separators. Empty or all-separator input → "".
     */
    fun normalizeBotName(raw: String): String {
        var s = raw.trim().lowercase()
        s = s.replace(Regex("\\s+"), "-")
        s = s.filter { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '-' || c == '_') {
                var j = i
                var hasDash = false
                while (j < s.length && (s[j] == '-' || s[j] == '_')) {
                    if (s[j] == '-') hasDash = true
                    j++
                }
                sb.append(if (hasDash) '-' else '_')
                i = j
            } else {
                sb.append(c)
                i++
            }
        }
        s = sb.toString().trim('-', '_')
        if (s.length > MAX_NAME_LEN) s = s.take(MAX_NAME_LEN).trimEnd('-', '_')
        return s
    }

    /**
     * Humane validation mirroring the server, so the editor can block bad saves BEFORE the RPC:
     * shape `^[a-z0-9][a-z0-9_-]{0,63}$` (hermes_cli/profiles.py:24) and the reserved set
     * {hermes, default, test, tmp, root, sudo} (hermes_cli/profiles.py:121). Null when the
     * slug is acceptable; a user-facing message otherwise.
     */
    fun botNameError(slug: String): String? {
        val s = slug.trim()
        if (s.isEmpty()) return "Give the bot a name first"
        if (!NAME_REGEX.matches(s)) {
            return "Names use lowercase letters, numbers, \"-\" or \"_\", start with a letter or number, and stay under 64 characters"
        }
        if (s in RESERVED_NAMES) return "The name \"$s\" is reserved — pick another"
        return null
    }

    /** Server profile-id shape + limit (hermes_cli/profiles.py:24). */
    val NAME_REGEX = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
    const val MAX_NAME_LEN = 64

    /** Reserved profile names (hermes_cli/profiles.py:121). */
    val RESERVED_NAMES = setOf("hermes", "default", "test", "tmp", "root", "sudo")

    private fun str(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Absent/unparseable boolean → the given default (tolerant parse, never hides rows). */
    private fun boolOr(obj: JsonObject, key: String, default: Boolean): Boolean =
        (obj[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: default

    private fun stringList(obj: JsonObject, key: String): List<String> =
        (obj[key] as? JsonArray)?.mapNotNull { el ->
            (el as? JsonPrimitive)?.takeIf { it.isString }?.content
        }.orEmpty()
}
