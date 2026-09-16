package ai.hermes.bots.ui.editor

import ai.hermes.bots.data.BotRow
import ai.hermes.bots.data.HealthEntry
import ai.hermes.bots.data.HealthState
import ai.hermes.bots.data.ModelHealth
import ai.hermes.bots.data.ProviderOption
import ai.hermes.bots.data.RosterEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-helper coverage for the editor rebuild (MODEL-UX-PUNCHLIST.md rev 2 / W2-E). */
class EditorModelTest {

  private fun row(
    connectionId: String = "conn-1",
    name: String,
    provider: String? = null,
    model: String? = null,
  ): RosterEntry = RosterEntry(
    bot = BotRow(
      connectionId = connectionId,
      name = name,
      displayName = null,
      description = null,
      model = model,
      provider = provider,
      skillCount = 0,
      isDefault = false,
      hasAvatar = false,
      sectionId = null,
      hidden = false,
      lastPreview = null,
      lastActiveMs = null,
      workerActiveMs = null,
      canonicalSessionId = null,
      canonicalRootTitle = null,
      uiMetaRevisions = emptyMap(),
    ),
    unread = false,
    activeNow = false,
  )

  private fun provider(
    slug: String,
    authenticated: Boolean = true,
    aliases: List<String> = emptyList(),
    models: List<String> = emptyList(),
    featured: List<String> = emptyList(),
  ): ProviderOption = ProviderOption(
    slug = slug,
    name = slug,
    authenticated = authenticated,
    authType = if (authenticated) null else "api_key",
    keyEnv = null,
    warning = null,
    models = models,
    featured = featured,
    aliases = aliases,
    isCurrent = false,
  )

  @Test
  fun `same-as candidates keep full pins on one connection and skip the edited bot`() {
    val rows = listOf(
      row(name = "scout", provider = "custom:clinepass", model = "cline-pass/deepseek-v4-flash"),
      row(name = "default", provider = "custom", model = "deepseek-v4-flash"),
      // Wrong connection — never offered.
      row(connectionId = "conn-2", name = "aaa-other", provider = "openai", model = "gpt-5"),
      // Incomplete pin — not copyable.
      row(name = "bbb-nopin", provider = "openai", model = null),
      row(name = "ccc-noprovider", provider = null, model = "gpt-5"),
      row(name = "zz-blankpin", provider = "  ", model = "gpt-5"),
    )
    val candidates = EditorModel.sameAsCandidates(rows, "conn-1", excludeName = "default")
    assertEquals(listOf("scout"), candidates.map { it.first })
    assertEquals(Triple("scout", "custom:clinepass", "cline-pass/deepseek-v4-flash"), candidates.single())
  }

  @Test
  fun `same-as candidates sort by name for stable chips`() {
    val rows = listOf(
      row(name = "zeta", provider = "p1", model = "m1"),
      row(name = "alpha", provider = "p2", model = "m2"),
      row(name = "mid", provider = "p3", model = "m3"),
    )
    assertEquals(
      listOf("alpha", "mid", "zeta"),
      EditorModel.sameAsCandidates(rows, "conn-1", null).map { it.first },
    )
  }

  @Test
  fun `pin change compares case-insensitively and treats blank as unset`() {
    // Identical pin (modulo case/whitespace) is NOT a change.
    assertFalse(EditorModel.pinChanged("Custom:ClinePass", "DeepSeek-V4-Flash", "custom:clinepass", "deepseek-v4-flash"))
    // Blank selection == null pin (no pin configured).
    assertFalse(EditorModel.pinChanged("", "  ", null, null))
    assertFalse(EditorModel.pinChanged("custom", "m-1", "custom", "m-1"))
    // Any real difference is a change.
    assertTrue(EditorModel.pinChanged("custom", "m-1", null, null))
    assertTrue(EditorModel.pinChanged("custom", "m-1", "custom", null))
    assertTrue(EditorModel.pinChanged("custom", "m-2", "custom", "m-1"))
    assertTrue(EditorModel.pinChanged("openai", "m-1", "custom", "m-1"))
  }

  @Test
  fun `provider ordering puts authenticated first and keeps relative order`() {
    val ordered = EditorModel.orderProviders(
      listOf(
        provider("openai", authenticated = false),
        provider("anthropic"),
        provider("custom:clinepass", authenticated = false),
        provider("custom"),
      ),
    )
    assertEquals(listOf("anthropic", "custom", "openai", "custom:clinepass"), ordered.map { it.slug })
  }

  @Test
  fun `provider health hint reports the fastest working entry for that provider only`() {
    val entries = mapOf(
      // Matching provider, two latencies → fastest wins.
      "conn-1|custom|a-model" to HealthEntry(HealthState.WORKING, 1, 2_400),
      "conn-1|custom|b-model" to HealthEntry(HealthState.WORKING, 1, 1_150),
      // Same provider but FAILED — ignored.
      "conn-1|custom|c-model" to HealthEntry(HealthState.FAILED, 1, 100),
      // Different connection / different provider — ignored.
      "conn-2|custom|fast-model" to HealthEntry(HealthState.WORKING, 1, 50),
      "conn-1|openai|fast-model" to HealthEntry(HealthState.WORKING, 1, 60),
    )
    assertEquals(
      "verified · 1.2 s",
      EditorModel.providerHealthHint(entries, "conn-1", "custom"),
    )
    assertEquals("verified · 0.1 s", EditorModel.providerHealthHint(entries, "conn-1", "openai"))
    assertNull(EditorModel.providerHealthHint(entries, "conn-1", "anthropic"))
    assertNull(EditorModel.providerHealthHint(emptyMap(), "conn-1", "custom"))
  }

  @Test
  fun `model health hint needs an exact working entry`() {
    val entries = mapOf(
      "conn-1|custom|vendor/model" to HealthEntry(HealthState.WORKING, 1, 850),
      "conn-1|custom|other" to HealthEntry(HealthState.FAILED, 1, 100),
    )
    assertEquals("verified · 0.9 s", EditorModel.modelHealthHint(entries, "conn-1", "custom", "vendor/model"))
    assertNull(EditorModel.modelHealthHint(entries, "conn-1", "custom", "other"))
    assertNull(EditorModel.modelHealthHint(entries, "conn-1", "custom", "missing"))
    assertNull(EditorModel.modelHealthHint(entries, "conn-2", "custom", "vendor/model"))
  }

  @Test
  fun `resolve provider slug snaps aliases to the canonical slug and passes unknown through`() {
    val providers = listOf(
      provider("custom", aliases = emptyList()),
      provider("custom:clinepass", aliases = listOf("clinepass", "custom:custom:clinepass")),
    )
    assertEquals("custom:clinepass", EditorModel.resolveProviderSlug(providers, "clinepass"))
    assertEquals("custom:clinepass", EditorModel.resolveProviderSlug(providers, "CUSTOM:CLINEPASS"))
    assertEquals("custom:clinepass", EditorModel.resolveProviderSlug(providers, "custom:custom:clinepass"))
    // Exact slug passes through; unknown spellings are never mangled.
    assertEquals("custom", EditorModel.resolveProviderSlug(providers, "custom"))
    assertEquals("mystery-provider", EditorModel.resolveProviderSlug(providers, "mystery-provider"))
    assertEquals("", EditorModel.resolveProviderSlug(providers, "  "))
  }

  @Test
  fun `name problem surfaces server-shaped validation errors`() {
    assertTrue(EditorModel.nameProblem("", emptyList(), "conn-1", null)?.contains("name") == true)
    assertTrue(
      EditorModel.nameProblem("My Bot!", emptyList(), "conn-1", null)
        ?.contains("lowercase") == true,
    )
    assertTrue(
      EditorModel.nameProblem("root", emptyList(), "conn-1", null)?.contains("reserved") == true,
    )
    assertNull(EditorModel.nameProblem("my-validator", emptyList(), "conn-1", null))
  }

  @Test
  fun `name problem flags roster collisions on the target connection but not self`() {
    val rows = listOf(row(name = "validator"), row(connectionId = "conn-2", name = "validator"))
    assertTrue(
      EditorModel.nameProblem("validator", rows, "conn-1", null)?.contains("already exists") == true,
    )
    // The bot being re-saved never collides with itself, and a free name never collides.
    assertNull(EditorModel.nameProblem("validator", rows, "conn-1", selfName = "validator"))
    assertNull(EditorModel.nameProblem("fresh-name", rows, "conn-2", null))
  }

  @Test
  fun `latency seconds renders one decimal`() {
    assertEquals("1.2 s", EditorModel.latencySeconds(1_200L))
    assertEquals("0.4 s", EditorModel.latencySeconds(420L))
    assertEquals("12.0 s", EditorModel.latencySeconds(12_000L))
  }

  @Test
  fun `no key badge hides for bare custom and for verified pins`() {
    // Bare `custom` resolves through the launch home's config — "No key" would be a lie.
    assertFalse(EditorModel.showNoKeyBadge(provider("custom", authenticated = false), pinWorking = false))
    // A ✓ Working verdict is authoritative over the catalog's presence-only flag.
    assertTrue(EditorModel.showNoKeyBadge(provider("fireworks", authenticated = false), pinWorking = false))
    assertFalse(EditorModel.showNoKeyBadge(provider("fireworks", authenticated = false), pinWorking = true))
    assertFalse(EditorModel.showNoKeyBadge(provider("anthropic"), pinWorking = false))
  }

  @Test
  fun `gateway warnings surface only when they read as humane copy`() {
    assertTrue(
      EditorModel.showGatewayWarning(
        provider("moa").copy(warning = "Aggregator acts as the selected model; references provide analysis."),
      ),
    )
    // Setup commands are gateway-host language; the in-app guidance block covers them.
    assertFalse(EditorModel.showGatewayWarning(provider("custom").copy(warning = "run `hermes model` to configure (api_key)")))
    assertFalse(EditorModel.showGatewayWarning(provider("deepseek").copy(warning = "paste DEEPSEEK_API_KEY to activate")))
    assertFalse(EditorModel.showGatewayWarning(provider("x").copy(warning = null)))
  }

  @Test
  fun `split providers keeps only keyed model-listing probe-passed rows up top`() {
    val providers = listOf(
      provider("custom:clinepass", models = List(3) { "m$it" }),
      provider("moa", models = listOf("default")),
      provider("opencode-free", models = List(2) { "f$it" }),
      provider("anthropic", models = List(2) { "a$it" }),          // probe FAILED
      provider("copilot", models = List(2) { "c$it" }),            // probe FAILED
      provider("fireworks", authenticated = false, models = List(2) { "x$it" }),
      provider("openrouter", models = emptyList()),                // keyed but lists nothing
    )
    val entries = mapOf(
      ModelHealth.keyFor("conn-1", "anthropic", "") to HealthEntry(HealthState.FAILED, 1L, reason = "Key rejected or missing on the gateway"),
      ModelHealth.keyFor("conn-1", "copilot", "") to HealthEntry(HealthState.FAILED, 1L, reason = "The endpoint rejected that model id"),
    )
    val (ready, more) = EditorModel.splitProviders(providers, entries, "conn-1")
    assertEquals(listOf("custom:clinepass", "moa", "opencode-free"), ready.map { it.slug })
    assertEquals(setOf("anthropic", "copilot", "fireworks", "openrouter"), more.map { it.slug }.toSet())
  }

  @Test
  fun `probes in flight stay in the ready group with an honest checking state`() {
    val providers = listOf(provider("opencode-free", models = listOf("f1", "f2")))
    val entries = mapOf(
      ModelHealth.keyFor("conn-1", "opencode-free", "") to HealthEntry(HealthState.TESTING, 1L),
    )
    val (ready, more) = EditorModel.splitProviders(providers, entries, "conn-1")
    assertEquals(listOf("opencode-free"), ready.map { it.slug })
    assertTrue(more.isEmpty())
    assertEquals("Checking…", EditorModel.moreReason(providers[0], entries, "conn-1"))
  }

  @Test
  fun `more reasons name the actual blocker`() {
    val failed = mapOf(
      ModelHealth.keyFor("conn-1", "anthropic", "") to
        HealthEntry(HealthState.FAILED, 1L, reason = "Key rejected or missing on the gateway"),
    )
    assertEquals(
      "Key rejected or missing on the gateway",
      EditorModel.moreReason(provider("anthropic", models = listOf("a")), failed, "conn-1"),
    )
    assertEquals("Needs a key", EditorModel.moreReason(provider("fireworks", authenticated = false), emptyMap(), "conn-1"))
    assertEquals("Lists no models", EditorModel.moreReason(provider("openrouter"), emptyMap(), "conn-1"))
  }

  @Test
  fun `probe candidates skip custom, cached, and keyless providers and cap the fan-out`() {
    val providers = listOf(
      provider("custom:clinepass", models = listOf("m")),                    // never candidate (override trap)
      provider("moa", models = listOf("default")),                            // candidate
      provider("opencode-free", models = listOf("f1", "f2"), featured = listOf("f2")), // verified pick f2
      provider("anthropic", models = listOf("a")),                            // fresh FAILED cache → skipped
      provider("openrouter", models = emptyList()),                           // no models → skipped
      provider("minimax-oauth", models = listOf("mm")),                       // candidate
      provider("copilot", models = listOf("c")),                              // candidate
      provider("zai", models = listOf("z")),                                  // candidate
      provider("deepseek", models = listOf("d")),                             // candidate (hits the cap)
      provider("xai", models = listOf("x")),                                  // beyond the cap
    )
    val entries = mapOf(
      // Fresh (1h ago) provider-level verdicts suppress re-probing.
      ModelHealth.keyFor("conn-1", "anthropic", "") to
        HealthEntry(HealthState.FAILED, System.currentTimeMillis() - 3_600_000L),
    )
    val candidates = EditorModel.probeCandidates(providers, entries, "conn-1", System.currentTimeMillis())
    assertEquals(listOf("moa", "opencode-free", "minimax-oauth", "copilot", "zai", "deepseek"), candidates.map { it.first })
    // The opencode-free candidate is the verified model, not the stale first row.
    assertEquals("f2", candidates.first { it.first == "opencode-free" }.second)
  }

  @Test
  fun `auto preselect adopts the gateway current combo only on a clean create`() {
    val providers = listOf(provider("custom:clinepass", aliases = listOf("clinepass")))
    // Happy path: fresh create, nothing picked → gateway's working pair, alias-snapped.
    // A custom:<name> gateway provider snaps to bare `custom` — the named form only
    // resolves for the launch home; new profiles need the bare spelling (live-proven).
    assertEquals(
      "custom" to "deepseek/deepseek-v4.1-flash",
      EditorModel.autoPreselect(false, false, false, "", "", "clinepass", "deepseek/deepseek-v4.1-flash", providers),
    )
    // Never override an existing choice, an edit, a saved phase, or manual entry.
    assertNull(EditorModel.autoPreselect(false, false, false, "moa", "default", "clinepass", "m", providers))
    assertNull(EditorModel.autoPreselect(true, false, false, "", "", "clinepass", "m", providers))
    assertNull(EditorModel.autoPreselect(false, true, false, "", "", "clinepass", "m", providers))
    assertNull(EditorModel.autoPreselect(false, false, true, "", "", "clinepass", "m", providers))
    // Incomplete gateway info → leave alone.
    assertNull(EditorModel.autoPreselect(false, false, false, "", "", "clinepass", "", providers))
  }

  @Test
  fun `pick default model prefers verified then featured then first`() {
    val p = provider("opencode-free", models = listOf("f1", "f2"), featured = listOf("f1"))
    val verified = mapOf(
      ModelHealth.keyFor("conn-1", "opencode-free", "f2") to HealthEntry(HealthState.WORKING, 1L, latencyMs = 900L),
    )
    assertEquals("f2", EditorModel.pickDefaultModel(p, verified, "conn-1"))
    assertEquals("f1", EditorModel.pickDefaultModel(p, emptyMap(), "conn-1"))
  }
}
