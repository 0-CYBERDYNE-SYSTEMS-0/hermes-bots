package ai.hermes.bots.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure parsing/matching rules for `model.options` (MODEL-UX-PUNCHLIST.md rev 2 / P1). */
class ModelCatalogTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun result(payload: String) = json.parseToJsonElement(payload).jsonObject

    @Test
    fun `parse full provider row with all documented fields`() {
        val providers = ModelCatalog.parseProviders(
            result(
                """{"model":"m1","provider":"openrouter","providers":[
                   {"slug":"openrouter","name":"OpenRouter","is_current":true,"is_user_defined":false,
                    "models":["a/model-1","b/model-2"],"total_models":2,"source":"builtin",
                    "authenticated":true,"auth_type":"api_key","key_env":"OPENROUTER_API_KEY",
                    "warning":null,"featured_models":["a/model-1"],"aliases":["or"],"capabilities":{}}]}""",
            ),
        )
        assertEquals(1, providers.size)
        val p = providers[0]
        assertEquals("openrouter", p.slug)
        assertEquals("OpenRouter", p.name)
        assertTrue(p.authenticated)
        assertEquals("api_key", p.authType)
        assertEquals("OPENROUTER_API_KEY", p.keyEnv)
        assertNull(p.warning)
        assertEquals(listOf("a/model-1", "b/model-2"), p.models)
        assertEquals(listOf("a/model-1"), p.featured)
        assertEquals(listOf("or"), p.aliases)
        assertTrue(p.isCurrent)
    }

    @Test
    fun `parse tolerates missing fields and unknown extras`() {
        // pricing absent (cache-gated), no models/aliases/warning at all.
        val providers = ModelCatalog.parseProviders(
            result("""{"providers":[{"slug":"anthropic","name":"Anthropic","extra_unknown":1}]}"""),
        )
        val p = providers.single()
        assertEquals("anthropic", p.slug)
        assertEquals("Anthropic", p.name)
        assertTrue("absent authenticated means assume configured", p.authenticated)
        assertNull(p.authType)
        assertNull(p.keyEnv)
        assertNull(p.warning)
        assertTrue(p.models.isEmpty())
        assertTrue(p.featured.isEmpty())
        assertTrue(p.aliases.isEmpty())
        assertFalse(p.isCurrent)
    }

    @Test
    fun `explicit authenticated false wins over the absent-means-true default`() {
        val providers = ModelCatalog.parseProviders(
            result(
                """{"providers":[{"slug":"a","authenticated":false},
                                 {"slug":"b","authenticated":true}]}""",
            ),
        )
        assertFalse(providers[0].authenticated)
        assertTrue(providers[1].authenticated)
    }

    @Test
    fun `server order preserved and nothing filtered`() {
        val providers = ModelCatalog.parseProviders(
            result(
                """{"providers":[{"slug":"custom:clinepass","name":""},
                                 {"slug":"anthropic"},
                                 {"slug":"openai","authenticated":false}]}""",
            ),
        )
        assertEquals(listOf("custom:clinepass", "anthropic", "openai"), providers.map { it.slug })
    }

    @Test
    fun `display name falls back to slug`() {
        val providers = ModelCatalog.parseProviders(
            result("""{"providers":[{"slug":"custom:clinepass","name":""},{"slug":"or","name":" OpenRouter "}]}"""),
        )
        assertEquals("custom:clinepass", ModelCatalog.displayName(providers[0]))
        assertEquals("OpenRouter", ModelCatalog.displayName(providers[1]))
    }

    @Test
    fun `match by alias accepts live alias lists`() {
        val providers = ModelCatalog.parseProviders(
            result(
                """{"providers":[{"slug":"custom:clinepass","aliases":["clinepass","custom:clinepass","custom:custom:clinepass"]}]}""",
            ),
        )
        val p = providers.single()
        assertTrue(ModelCatalog.matchByAlias(p, "custom:clinepass"))
        assertTrue(ModelCatalog.matchByAlias(p, "clinepass"))
        assertTrue(ModelCatalog.matchByAlias(p, "  CUSTOM:CLINEPASS  "))
        assertFalse(ModelCatalog.matchByAlias(p, "custom:opencode-go"))
        assertFalse(ModelCatalog.matchByAlias(p, " "))
    }

    @Test
    fun `models for sorts featured first then natural order`() {
        val providers = ModelCatalog.parseProviders(
            result(
                """{"providers":[{"slug":"openrouter",
                   "models":["m/alpha","m/beta","m/gamma","m/delta"],
                   "featured_models":["m/gamma","m/alpha"]}]}""",
            ),
        )
        assertEquals(
            listOf("m/gamma", "m/alpha", "m/beta", "m/delta"),
            ModelCatalog.modelsFor(providers, "openrouter"),
        )
    }

    @Test
    fun `models for falls back to alias lookup and handles misses`() {
        val providers = ModelCatalog.parseProviders(
            result("""{"providers":[{"slug":"custom:clinepass","models":["x/y"],"aliases":["clinepass"]}]}"""),
        )
        assertEquals(listOf("x/y"), ModelCatalog.modelsFor(providers, "clinepass"))
        assertTrue(ModelCatalog.modelsFor(providers, "nope").isEmpty())
        // featured entries not present in models are never surfaced (no invented pins).
        val withPhantom = ModelCatalog.parseProviders(
            result("""{"providers":[{"slug":"p","models":["a"],"featured_models":["a","phantom"]}]}"""),
        )
        assertEquals(listOf("a"), ModelCatalog.modelsFor(withPhantom, "p"))
    }

    @Test
    fun `vendor prefix warning fires only for custom slugs without a slash`() {
        assertTrue(ModelCatalog.needsVendorPrefixWarning("custom:clinepass", "deepseek-v4-flash"))
        assertFalse(ModelCatalog.needsVendorPrefixWarning("custom:clinepass", "cline-pass/deepseek-v4-flash"))
        assertFalse(ModelCatalog.needsVendorPrefixWarning("openrouter", "deepseek-v4-flash"))
        assertTrue(ModelCatalog.needsVendorPrefixWarning("Custom:Opencode-Go", "m1"))
    }

    @Test
    fun `normalize bot name lowercases and slugs spaces`() {
        assertEquals("validator-2", ModelCatalog.normalizeBotName("Validator 2!"))
        assertEquals("my-bot", ModelCatalog.normalizeBotName("  My Bot "))
        assertEquals("validator", ModelCatalog.normalizeBotName("validator"))
        assertEquals("", ModelCatalog.normalizeBotName(""))
        assertEquals("", ModelCatalog.normalizeBotName("   "))
        // separator runs collapse to one; lone underscores survive intentional use
        assertEquals("a-b", ModelCatalog.normalizeBotName("a -_ b"))
        assertEquals("my_bot", ModelCatalog.normalizeBotName("my_bot"))
        assertEquals("my_bot", ModelCatalog.normalizeBotName("my__bot"))
        // leading/trailing separators are trimmed, never left for the server regex to reject
        assertEquals("lead", ModelCatalog.normalizeBotName("--lead--"))
    }

    @Test
    fun `normalize bot name truncates to the server 64-char limit`() {
        assertEquals(64, ModelCatalog.normalizeBotName("a".repeat(70)).length)
        // truncation re-trims trailing separators left at the cut point
        val long = "a".repeat(63) + "-b"
        assertEquals("a".repeat(63), ModelCatalog.normalizeBotName(long))
    }

    @Test
    fun `bot name error mirrors server shape and reserved set`() {
        assertNull(ModelCatalog.botNameError("validator-2"))
        assertNull(ModelCatalog.botNameError("my_bot"))
        assertTrue("empty name flagged", ModelCatalog.botNameError("") != null)
        assertTrue(
            "invalid shape flagged",
            ModelCatalog.botNameError("Bad Name")?.contains("lowercase", ignoreCase = true) == true,
        )
        assertTrue(ModelCatalog.botNameError("a".repeat(65)) != null)
        assertTrue(
            "reserved names flagged",
            ModelCatalog.botNameError("test")?.contains("reserved", ignoreCase = true) == true,
        )
        assertTrue(ModelCatalog.botNameError("default")?.contains("reserved", ignoreCase = true) == true)
        assertTrue(ModelCatalog.botNameError("hermes")?.contains("reserved", ignoreCase = true) == true)
    }
}
