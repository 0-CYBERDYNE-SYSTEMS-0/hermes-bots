package ai.hermes.bots.ui.editor

import ai.hermes.bots.HermesBotsApp
import ai.hermes.bots.data.AvatarImage
import ai.hermes.bots.data.BotAdmin
import ai.hermes.bots.data.BotAdminRepository
import ai.hermes.bots.data.ConnectionRecord
import ai.hermes.bots.data.HealthEntry
import ai.hermes.bots.data.HealthState
import ai.hermes.bots.data.ModelCatalog
import ai.hermes.bots.data.ModelHealth
import ai.hermes.bots.data.ProviderOption
import ai.hermes.bots.data.RosterEntry
import ai.hermes.bots.data.RosterParsing
import ai.hermes.bots.protocol.Catalog
import ai.hermes.bots.protocol.HermesGateway
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure decision helpers for the bot editor (MODEL-UX-PUNCHLIST.md rev 2 W2) — unit-tested
 * in EditorModelTest, no gateway or Android dependencies.
 */
object EditorModel {

  /**
   * A1 "Same as <bot>": (name, provider, model) of roster rows on [connectionId] whose pin
   * is complete, excluding the bot being edited; sorted by name for stable chips.
   * (Kotlin has no 3-arity Pair — Triple stands in.)
   */
  fun sameAsCandidates(
    rows: List<RosterEntry>,
    connectionId: String,
    excludeName: String?,
  ): List<Triple<String, String, String>> = rows.asSequence()
    .filter { it.bot.connectionId == connectionId }
    .filter { it.bot.name != excludeName }
    .filter { !it.bot.provider.isNullOrBlank() && !it.bot.model.isNullOrBlank() }
    .map { Triple(it.bot.name, it.bot.provider.orEmpty(), it.bot.model.orEmpty()) }
    .sortedBy { it.first }
    .toList()

  /**
   * R9: the pin counts as changed when the current selection differs from the last
   * loaded/saved one — case-insensitive, blank == null.
   */
  fun pinChanged(provider: String, model: String, pinProvider: String?, pinModel: String?): Boolean {
    fun norm(v: String?): String? = v?.trim()?.lowercase()?.ifBlank { null }
    return norm(provider) != norm(pinProvider) || norm(model) != norm(pinModel)
  }

  /**
   * Everything the user can edit, flattened for the back-guard's dirty check. Volatile
   * fields (providers list, loading flags, messages, pins-as-loaded) are deliberately
   * excluded — only a real user edit turns the editor dirty.
   */
  fun formValues(st: EditorUiState): List<Any?> = listOf(
      st.name, st.description, st.soul, st.model, st.provider, st.sectionId, st.hidden,
      st.createOnConnectionId, st.startFrom, st.pickedAvatar?.bytes?.size ?: -1,
      st.skills.map { it.name to it.enabled },
      st.toolsets.map { it.name to it.enabled },
      st.mcpServers.map { it.name to it.enabled },
  )

  /** P4 dropdown order: authenticated providers first; relative order kept inside each group. */
  fun orderProviders(providers: List<ProviderOption>): List<ProviderOption> {
    val (authed, rest) = providers.partition { it.authenticated }
    return authed + rest
  }

  /** `connId|provider|model` store key → (connId, provider, model). */
  private fun segments(key: String): Triple<String, String, String>? {
    val parts = key.split('|', limit = 3)
    return if (parts.size == 3) Triple(parts[0], parts[1], parts[2]) else null
  }

  fun latencySeconds(latencyMs: Long): String =
    String.format(java.util.Locale.US, "%.1f s", latencyMs / 1000.0)

  /** A5: "verified · 2.1 s" when any WORKING health entry exists for this provider's models. */
  fun providerHealthHint(
    entries: Map<String, HealthEntry>,
    connectionId: String,
    providerSlug: String,
  ): String? {
    val best = entries.asSequence()
      .mapNotNull { (key, entry) -> segments(key)?.let { it to entry } }
      .filter { (seg, entry) ->
        seg.first == connectionId && entry.state == HealthState.WORKING &&
          seg.second.trim().equals(providerSlug.trim(), ignoreCase = true)
      }
      .minByOrNull { (_, entry) -> entry.latencyMs ?: Long.MAX_VALUE }
      ?.second
    return best?.latencyMs?.let { "verified · ${latencySeconds(it)}" }
  }

  /** A5 model-row hint: exact (provider, model) WORKING entry → "verified · X s". */
  fun modelHealthHint(
    entries: Map<String, HealthEntry>,
    connectionId: String,
    providerSlug: String,
    modelId: String,
  ): String? {
    val entry = entries[ModelHealth.keyFor(connectionId, providerSlug, modelId)] ?: return null
    if (entry.state != HealthState.WORKING) return null
    return "verified · ${latencySeconds(entry.latencyMs ?: 0L)}"
  }

  /**
   * "· No key" badge gate. Bare `custom` never badges: the catalog lists it as an
   * unconfigured skeleton, but a bare-custom pin resolves through the launch home's
   * config (the key lives per custom entry on the gateway), so "No key" would lie —
   * exactly what the ✓ Working verdict next to it disproved in the model-UX gate.
   * A verified-working pin also suppresses the badge: the verdict is authoritative.
   */
  fun showNoKeyBadge(provider: ProviderOption, pinWorking: Boolean): Boolean {
    if (provider.authenticated) return false
    if (provider.slug.trim().lowercase() == "custom") return false
    return !pinWorking
  }

  /**
   * Show the gateway's notice only when it reads as humane copy. Setup instructions
   * ("run `hermes model` …", "paste X to activate") are commands for gateway hosts, not
   * for this screen — the key/sign-in guidance block covers those cases in app language.
   */
  fun showGatewayWarning(provider: ProviderOption): Boolean {
    val w = provider.warning?.trim().orEmpty()
    if (w.isEmpty()) return false
    val lower = w.lowercase()
    return !lower.contains("`") && !lower.contains("paste ") && !lower.contains("hermes model")
  }

  // ── Probe-backed "ready to use" grouping (2026-09-13 revision) ──────────────────
  // The gateway's `authenticated` flag only means credentials EXIST — live probes proved
  // rows can be dead (anthropic "no credentials", copilot "model not supported",
  // minimax "out of quota"). The editor probes candidates once and groups on the RESULT.

  /** Provider-level health entry (model key "") — the candidate-probe verdict. */
  fun providerProbe(entries: Map<String, HealthEntry>, connectionId: String, slug: String): HealthEntry? =
    entries[ModelHealth.keyFor(connectionId, slug, "")]

  /**
   * Primary ("ready to use") vs collapsed ("more") split. A provider is primary when it
   * has keys, lists models, and its probe did NOT fail — in-flight probes ("checking…")
   * stay primary so the user sees honest progress. Everything else (needs key, no
   * models, probe-failed) collapses under "Show N more" so nothing unusable tempts a tap.
   */
  fun splitProviders(
    providers: List<ProviderOption>,
    entries: Map<String, HealthEntry>,
    connectionId: String,
  ): Pair<List<ProviderOption>, List<ProviderOption>> {
    val primary = providers.filter { p ->
      p.authenticated && p.models.isNotEmpty() &&
        providerProbe(entries, connectionId, p.slug)?.state != HealthState.FAILED
    }
    return primary to (providers - primary.toSet())
  }

  /** Short humane reason a collapsed provider is not in the ready group. */
  fun moreReason(
    provider: ProviderOption,
    entries: Map<String, HealthEntry>,
    connectionId: String,
  ): String {
    val probe = providerProbe(entries, connectionId, provider.slug)
    if (probe?.state == HealthState.FAILED) return probe.reason ?: "Not working right now"
    if (probe?.state == HealthState.TESTING) return "Checking…"
    if (!provider.authenticated) {
      return when {
        provider.authType == "api_key" -> "Needs a key"
        provider.slug.trim().lowercase() == "custom" -> "Keyed on the gateway host"
        else -> "Needs sign-in on the gateway"
      }
    }
    if (provider.models.isEmpty()) return "Lists no models"
    return "Not available"
  }

  /** Best model to try for a provider: a verified one first, then featured, then first listed. */
  fun pickDefaultModel(
    provider: ProviderOption,
    entries: Map<String, HealthEntry>,
    connectionId: String,
  ): String? {
    val verified = entries.asSequence()
      .mapNotNull { (key, entry) -> segments(key)?.let { it to entry } }
      .firstOrNull { (seg, entry) ->
        seg.first == connectionId && seg.second.equals(provider.slug, ignoreCase = true) &&
          seg.third.isNotBlank() && entry.state == HealthState.WORKING
      }?.first?.third
    return verified ?: provider.featured.firstOrNull() ?: provider.models.firstOrNull()
  }

  private const val PROBE_MAX_AGE_MS = 24 * 60 * 60 * 1000L
  private const val PROBE_MAX_PROVIDERS = 6

  /**
   * Which providers need a candidate probe right now: keyed, model-listing, not custom*
   * (candidate overrides cannot resolve those), and no fresh provider-level verdict.
   * Capped so one editor open can never fan out into an unbounded turn storm.
   */
  fun probeCandidates(
    providers: List<ProviderOption>,
    entries: Map<String, HealthEntry>,
    connectionId: String,
    nowMs: Long,
  ): List<Pair<String, String>> = providers.asSequence()
    .filter { it.authenticated && it.models.isNotEmpty() }
    .filterNot { it.slug.trim().lowercase().startsWith("custom") }
    .mapNotNull { p ->
      val fresh = providerProbe(entries, connectionId, p.slug)
        ?.takeIf { nowMs - it.checkedAtMs < PROBE_MAX_AGE_MS }
      if (fresh == null) p to (pickDefaultModel(p, entries, connectionId) ?: return@mapNotNull null) else null
    }
    .take(PROBE_MAX_PROVIDERS)
    .map { it.first.slug to it.second }
    .toList()

  /**
   * Zero-touch preselect: when creating a bot with nothing picked yet, adopt the
   * gateway's CURRENT working combo (model.options top-level `provider`/`model`) —
   * "the setup already working for Hermes agent", exactly what the user asked to have
   * plugged in. A `custom:<name>` provider is snapped to bare `custom`: the named form
   * resolves only for the launch home (its config carries the custom_providers entry);
   * NEW profiles fail agent init with "Unknown provider" while bare `custom` + the same
   * (vendor-prefixed) model id is the proven profile shape (scout/dalesa/validator).
   * Returns null when the editor should leave the fields alone (edit mode, saved phase,
   * manual entry, or anything already picked).
   */
  fun autoPreselect(
    isEdit: Boolean,
    savedPhase: Boolean,
    manualEntry: Boolean,
    currentProvider: String,
    currentModel: String,
    gatewayProvider: String?,
    gatewayModel: String?,
    providers: List<ProviderOption>,
  ): Pair<String, String>? {
    if (isEdit || savedPhase || manualEntry) return null
    if (currentProvider.isNotBlank() || currentModel.isNotBlank()) return null
    val gp = gatewayProvider?.trim().orEmpty()
    val gm = gatewayModel?.trim().orEmpty()
    if (gp.isBlank() || gm.isBlank()) return null
    val slug = resolveProviderSlug(providers, gp)
    val profileSafe = if (slug.lowercase().startsWith("custom:")) "custom" else slug
    return profileSafe to gm
  }

  /**
   * R14: resolve a raw provider spelling (this bot's or a donor bot's) to the canonical
   * slug this gateway lists — alias spellings snap, unknown values pass through untouched.
   */
  fun resolveProviderSlug(providers: List<ProviderOption>, raw: String): String {
    val needle = raw.trim()
    if (needle.isEmpty()) return ""
    providers.firstOrNull { it.slug == needle }?.let { return it.slug }
    return providers.firstOrNull { ModelCatalog.matchByAlias(it, needle) }?.slug ?: needle
  }

  /** P5/R3: server-shaped validation first, then a roster-collision check on the target connection. */
  fun nameProblem(
    slug: String,
    rows: List<RosterEntry>,
    connectionId: String,
    selfName: String?,
  ): String? {
    ModelCatalog.botNameError(slug)?.let { return it }
    val taken = rows.any {
      it.bot.connectionId == connectionId && it.bot.name == slug && it.bot.name != selfName
    }
    return if (taken) "A bot named \"$slug\" already exists on this gateway — pick another name" else null
  }
}

data class EditorUiState(
    val isEdit: Boolean = false,
    val name: String = "",
    val description: String = "",
    val soul: String = "",
    val model: String = "",
    val provider: String = "",
    val sectionId: String = "",
    val hidden: Boolean = false,
    val createOnConnectionId: String = "",
    val cloneFrom: String = "",
    val skills: List<BotAdmin.SkillRow> = emptyList(),
    val toolsets: List<BotAdmin.ToolsetRow> = emptyList(),
    val mcpServers: List<BotAdmin.McpRow> = emptyList(),
    val pickedAvatar: AvatarImage? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    /** null = unknown (not fetched / new bot); surfaced from profiles.list ui_meta (B3). */
    val relayCapable: Boolean? = null,
    val message: String? = null,
    val error: String? = null,
    val saved: Boolean = false,
    // --- MODEL-UX-PUNCHLIST.md rev 2 / W2 ---
    /** Per-gateway provider rows from model.options (authenticated + unconfigured). */
    val providers: List<ProviderOption> = emptyList(),
    val optionsLoading: Boolean = false,
    val optionsError: String? = null,
    /** Free-text escape hatch (desktop parity) instead of the provider/model dropdowns. */
    val manualEntry: Boolean = false,
    /** Live normalized preview of the create name (R3/A6) — this exact value is what save() sends. */
    val nameSlug: String = "",
    /** botNameError OR roster-collision on the target connection; blocks Save with a reason. */
    val nameProblem: String? = null,
    /** Chosen donor bot for create ("Start from"). */
    val startFrom: String? = null,
    /** The pin as LAST LOADED/SAVED — null for a fresh create until one lands. */
    val pinProvider: String? = null,
    val pinModel: String? = null,
    /** A4: the loaded pin's provider is not offered (exactly or by alias) on this gateway. */
    val pinUnlisted: Boolean = false,
    /** D: post-save phase — editor stays open showing the saved state + verification row. */
    val savedPhase: Boolean = false,
) {
  val pinChanged: Boolean
    get() = EditorModel.pinChanged(provider, model, pinProvider, pinModel)
}

class BotEditorViewModel(app: Application, private val editConnectionId: String?, private val editName: String?) :
    AndroidViewModel(app) {

  private val graph = (app as HermesBotsApp).graph
  private val admin = BotAdminRepository(graph.gateways)

  private val _ui = MutableStateFlow(EditorUiState(isEdit = editName != null))
  val ui: StateFlow<EditorUiState> = _ui

  val connections: StateFlow<List<ConnectionRecord>> = graph.connections.connections
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  val rosterRows: StateFlow<List<RosterEntry>> = graph.roster.roster
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  /** Verification verdict for the currently selected (connection, provider, model). */
  @OptIn(ExperimentalCoroutinesApi::class)
  val health: StateFlow<HealthEntry?> = _ui
      .map { Triple(it.createOnConnectionId, it.provider.trim(), it.model.trim()) }
      .distinctUntilChanged()
      .flatMapLatest { (connId, p, m) ->
        if (connId.isBlank() || p.isBlank() || m.isBlank()) {
          flowOf(null)
        } else {
          graph.modelHealth.entry(connId, p, m)
        }
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  /** Full health cache for dropdown row hints (A5). */
  val healthMap: StateFlow<Map<String, HealthEntry>> = graph.modelHealth.entries
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

  private var optionsJob: Job? = null
  private var probeJob: Job? = null
  private var verifyJob: Job? = null

  /** Form as last loaded/saved — a back-guard against losing edits (rotations, stray swipes). */
  private var initialForm: List<Any?> = emptyList()

  /** True once any editable field differs from the last loaded/saved form. */
  val dirty: StateFlow<Boolean> = _ui
      .map { EditorModel.formValues(it) != initialForm }
      .distinctUntilChanged()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

  private fun markClean() {
    initialForm = EditorModel.formValues(_ui.value)
  }

  init {
    load()
    // B1/R8: the model catalog loads on init, whenever the create-on connection changes,
    // and when that connection's gateway epoch bumps (reconnect = fresh catalog).
    viewModelScope.launch {
      _ui.map { it.createOnConnectionId }.distinctUntilChanged().collectLatest { connId ->
        loadOptions(forConnectionId = connId)
      }
    }
    viewModelScope.launch {
      combine(
        _ui.map { it.createOnConnectionId }.distinctUntilChanged(),
        graph.gateways.live,
      ) { connId, live -> connId to live[connId]?.gateway }
          .flatMapLatest { (_, gw) -> gw?.epochGeneration ?: flowOf(0) }
          .drop(1)
          .collect { loadOptions() }
    }
  }

  fun load() {
    viewModelScope.launch {
      _ui.update { it.copy(loading = true, error = null) }
      try {
        val connId = editConnectionId ?: defaultConnectionId()
        _ui.update { it.copy(createOnConnectionId = connId) }
        if (editName != null) {
          val row = withTimeout(15_000) {
            graph.roster.roster
                .first { rows -> rows.any { it.bot.connectionId == connId && it.bot.name == editName } }
                .first { it.bot.connectionId == connId && it.bot.name == editName }.bot
          }
          val snap = admin.describe(connId, editName)
          val loadedProvider = snap.modelProvider.ifBlank { row.provider.orEmpty() }
          val loadedModel = snap.modelDefault.ifBlank { row.model.orEmpty() }
          _ui.update {
            it.copy(
                loading = false,
                name = editName,
                description = snap.description.ifBlank { row.description.orEmpty() },
                soul = snap.soul,
                model = loadedModel,
                provider = loadedProvider,
                pinProvider = loadedProvider.trim().ifBlank { null },
                pinModel = loadedModel.trim().ifBlank { null },
                sectionId = row.sectionId.orEmpty(),
                hidden = row.hidden,
                skills = snap.skills,
                toolsets = snap.toolsets,
                mcpServers = snap.mcpServers,
            )
          }
          evaluatePinVsProviders()
          markClean()
          // B3: per-bot "can message other bots" state from profiles.list ui_meta.
          _ui.update { it.copy(relayCapable = fetchRelayCapable(connId, editName)) }
        } else {
          _ui.update { it.copy(loading = false) }
          markClean()
        }
      } catch (e: Exception) {
        // Screen went away / job superseded — never surface cancellation as an error.
        if (e is kotlinx.coroutines.CancellationException) throw e
        _ui.update { it.copy(loading = false, error = e.message ?: "load failed") }
      }
    }
  }

  private suspend fun defaultConnectionId(): String {
    val conns = graph.connections.connections.first()
    return conns.firstOrNull { it.primary }?.id ?: conns.firstOrNull()?.id
    ?: throw IllegalStateException("no gateway connections configured")
  }

  fun set(action: (EditorUiState) -> EditorUiState) = _ui.update(action)

  fun toggleSkill(name: String, enabled: Boolean) = _ui.update { st ->
    st.copy(skills = st.skills.map { if (it.name == name) it.copy(enabled = enabled) else it })
  }

  fun toggleToolset(name: String, enabled: Boolean) = _ui.update { st ->
    st.copy(toolsets = st.toolsets.map { if (it.name == name) it.copy(enabled = enabled) else it })
  }

  fun toggleMcpServer(name: String, enabled: Boolean) = _ui.update { st ->
    st.copy(mcpServers = st.mcpServers.map { if (it.name == name) it.copy(enabled = enabled) else it })
  }

  /** Bulk toggle for the chip groups — one tap to strip every skill (etc.) and re-enable
   * only the wanted few instead of tapping each chip.
   */
  fun setAllSkills(enabled: Boolean) = _ui.update { st ->
    st.copy(skills = st.skills.map { it.copy(enabled = enabled) })
  }

  fun setAllToolsets(enabled: Boolean) = _ui.update { st ->
    st.copy(toolsets = st.toolsets.map { it.copy(enabled = enabled) })
  }

  fun setAllMcpServers(enabled: Boolean) = _ui.update { st ->
    st.copy(mcpServers = st.mcpServers.map { it.copy(enabled = enabled) })
  }

  // --- model options (B1/R8) ---

  /** Manual retry / "Refresh catalog" (refresh = true forces a server-side rebuild). */
  fun loadOptions(refresh: Boolean = false, forConnectionId: String? = null) {
    val connId = (forConnectionId ?: _ui.value.createOnConnectionId).ifBlank { return }
    optionsJob?.cancel()
    optionsJob = viewModelScope.launch {
      _ui.update { it.copy(optionsLoading = true, optionsError = null) }
      try {
        val result = admin.modelOptions(connId, refresh = refresh)
        val providers = ModelCatalog.parseProviders(result)
        val current = ModelCatalog.parseCurrentPair(result)
        _ui.update { it.copy(providers = providers, optionsLoading = false, optionsError = null) }
        // Zero-touch default: adopt the gateway's current working combo on a fresh create.
        EditorModel.autoPreselect(
            isEdit = _ui.value.isEdit,
            savedPhase = _ui.value.savedPhase,
            manualEntry = _ui.value.manualEntry,
            currentProvider = _ui.value.provider,
            currentModel = _ui.value.model,
            gatewayProvider = current?.first,
            gatewayModel = current?.second,
            providers = providers,
        )?.let { (p, m) -> _ui.update { it.copy(provider = p, model = m) } }
        evaluatePinVsProviders()
        probeEligibleProviders(connId)
      } catch (e: Exception) {
        // Cancellation means the job was superseded or the screen went away — never an
        // error state (and never the raw "StandaloneCoroutine was cancelled" string).
        if (e is kotlinx.coroutines.CancellationException) throw e
        val raw = e.message ?: "couldn't load the model list"
        _ui.update {
          it.copy(
              optionsLoading = false,
              optionsError = if ("not live" in raw.lowercase()) {
                "Gateway isn't connected yet — try again in a moment"
              } else {
                "Couldn't load the model list — check the gateway and retry"
              },
          )
        }
      }
    }
  }

  /**
   * One-time (per day, per provider) background candidate probes so the provider
   * dropdown only shows rows that VERIFIABLY work — the gateway's `authenticated` flag
   * lies (live-proven: anthropic "no credentials", copilot "model not supported",
   * minimax "out of quota" all showed ✓). Results land in the shared ModelHealthStore:
   * provider-level under model key "", the winning model duplicated under its own key.
   * Sequential on purpose — bounded, ordered load on the gateway.
   */
  private fun probeEligibleProviders(connId: String) {
    probeJob?.cancel()
    probeJob = viewModelScope.launch {
      val candidates = EditorModel.probeCandidates(
          _ui.value.providers,
          healthMap.value,
          connId,
          System.currentTimeMillis(),
      )
      for ((slug, model) in candidates) {
        try {
          graph.modelVerifier.verifyCandidate(connId, slug, model)
        } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
        } catch (e: Exception) {
          // One bad provider never blocks the rest; the row simply stays unverified.
        }
      }
    }
  }

  /**
   * A4/R14 (edit mode): the loaded pin must resolve against the loaded provider list.
   * Found by alias → snap the field to the canonical slug (the copy-from-another-bot fix);
   * not found at all → keep the raw value editable (manualEntry) and flag it.
   */
  private fun evaluatePinVsProviders() {
    val s = _ui.value
    if (!s.isEdit || s.providers.isEmpty()) return
    val raw = s.provider.trim()
    if (raw.isEmpty()) return
    val exact = s.providers.firstOrNull { it.slug == raw }
    val resolved = exact ?: s.providers.firstOrNull { ModelCatalog.matchByAlias(it, raw) }
    _ui.update {
      when {
        resolved == null -> it.copy(pinUnlisted = true, manualEntry = true)
        exact == null -> it.copy(provider = resolved.slug, pinUnlisted = false, manualEntry = false)
        else -> it.copy(pinUnlisted = false, manualEntry = false)
      }
    }
  }

  // --- field setters ---

  /** R3/A6: every name change recomputes the normalized slug this editor will actually send. */
  fun setName(raw: String) {
    _ui.update { st ->
      if (st.isEdit) return@update st.copy(name = raw)
      val slug = ModelCatalog.normalizeBotName(raw)
      val self = if (st.savedPhase) st.nameSlug else null
      st.copy(
          name = raw,
          nameSlug = slug,
          nameProblem = EditorModel.nameProblem(slug, graph.roster.roster.value, st.createOnConnectionId, self),
      )
    }
  }

  /** Create-on chips: switching gateways invalidates the donor and the picked pin. */
  fun setCreateOn(connId: String) {
    _ui.update { st ->
      if (st.isEdit || st.createOnConnectionId == connId) return@update st
      val slug = ModelCatalog.normalizeBotName(st.name)
      val self = if (st.savedPhase) st.nameSlug else null
      st.copy(
          createOnConnectionId = connId,
          nameSlug = slug,
          nameProblem = EditorModel.nameProblem(slug, graph.roster.roster.value, connId, self),
          provider = "",
          model = "",
          manualEntry = false,
          startFrom = null,
          cloneFrom = "",
      )
    }
  }

  /** B3: picking a provider auto-picks its first (featured-first) model when the current one is absent. */
  fun setProvider(slug: String) {
    _ui.update { st ->
      val connId = st.createOnConnectionId
      val row = st.providers.firstOrNull { it.slug == slug }
      val models = ModelCatalog.modelsFor(st.providers, slug)
      val keep = st.model.trim()
      val nextModel = when {
        models.any { it.equals(keep, ignoreCase = true) } -> st.model
        // Prefer a probe-verified model (opencode-free's first catalog row can be dead
        // while its second works — the probe knows which).
        row != null -> EditorModel.pickDefaultModel(row, healthMap.value, connId) ?: models.firstOrNull() ?: st.model
        else -> models.firstOrNull() ?: st.model
      }
      st.copy(provider = slug, model = nextModel, manualEntry = false, pinUnlisted = false)
    }
  }

  /** A1: one tap adopts another bot's working pin (provider snapped to this gateway's canonical slug). */
  fun adoptPinFrom(provider: String, model: String) {
    _ui.update { st ->
      st.copy(
          provider = EditorModel.resolveProviderSlug(st.providers, provider),
          model = model,
          manualEntry = false,
          pinUnlisted = false,
      )
    }
  }

  fun setManualEntry(on: Boolean) = _ui.update { it.copy(manualEntry = on) }

  /** A4: Keep keeps the raw pin editable; Clear empties it so the list takes over. */
  fun resolvePinUnlisted(keepPin: Boolean) = _ui.update { st ->
    if (keepPin) st.copy(pinUnlisted = false) else st.copy(pinUnlisted = false, manualEntry = false, provider = "", model = "")
  }

  /**
   * P6/R14: "Start from" — describe the donor and prefill description/soul/model/provider
   * (clone_from still rides the create so the server-side clone applies). Already-typed
   * text wins; a describe failure leaves the form untouched.
   */
  fun startFrom(name: String) {
    if (name.isBlank()) {
      _ui.update { it.copy(startFrom = null, cloneFrom = "") }
      return
    }
    viewModelScope.launch {
      val connId = _ui.value.createOnConnectionId
      val snap = try {
        admin.describe(connId, name)
      } catch (e: Exception) {
        _ui.update { it.copy(message = "Couldn't copy from $name — try again") }
        return@launch
      }
      _ui.update { st ->
        st.copy(
            description = st.description.ifBlank { snap.description },
            soul = st.soul.ifBlank { snap.soul },
            provider = EditorModel.resolveProviderSlug(st.providers, snap.modelProvider).ifBlank { st.provider },
            model = snap.modelDefault.ifBlank { st.model },
            cloneFrom = name,
            startFrom = name,
        )
      }
    }
  }

  fun setPickedAvatar(image: AvatarImage?) = _ui.update { it.copy(pickedAvatar = image) }

  // --- key paste (P7/A3) ---

  fun saveKey(slug: String, apiKey: String) {
    viewModelScope.launch {
      val connId = _ui.value.createOnConnectionId
      try {
        admin.saveKey(connId, slug, apiKey)
      } catch (e: Exception) {
        // Repository messages are already humane — surface them verbatim (the error path
        // genericizes unknown strings).
        _ui.update { it.copy(message = e.message ?: "Couldn't save the key — try again") }
        return@launch
      }
      loadOptions(refresh = true)
      _ui.update { it.copy(message = "Key saved — check the ✓") }
      // A3: when this bot's saved pin uses that provider, bridge presence→validity right away.
      val s = _ui.value
      val botSlug = editName
      val pinP = s.pinProvider
      val pinM = s.pinModel
      if (s.isEdit && botSlug != null && pinP != null && !pinM.isNullOrBlank() &&
        pinP.trim().equals(slug.trim(), ignoreCase = true)
      ) {
        startVerify(connId, botSlug, pinP, pinM)
      }
    }
  }

  // --- save (P8/R3 idempotent create; R9 edit) ---

  fun save() {
    val s = _ui.value
    if (s.saving) return
    // Validate BEFORE any gateway call (P5): a bad name can never create then half-fail.
    val problem = s.nameProblem
    if (!s.isEdit && problem != null) {
      _ui.update { it.copy(error = problem) }
      return
    }
    viewModelScope.launch {
      _ui.update { it.copy(saving = true, error = null, message = null) }
      try {
        val connId = s.createOnConnectionId.ifBlank { editConnectionId ?: defaultConnectionId() }
        if (s.isEdit) saveEdit(connId, s) else saveCreate(connId, s)
      } catch (e: Exception) {
        _ui.update { it.copy(saving = false, error = e.message ?: "save failed") }
      }
    }
  }

  private suspend fun saveCreate(connId: String, s: EditorUiState) {
    val slug = s.nameSlug.ifBlank { ModelCatalog.normalizeBotName(s.name) }
    var recovered = false
    var createResult: JsonObject? = null

    // F2/R3 idempotency: ANY failure after (or during) create — "already exists",
    // roster-wait timeouts, transient RPCs — probes the server first; when the profile
    // actually landed we finish the remaining steps instead of reporting failure for a
    // bot that exists.
    suspend fun guarded(step: suspend () -> Unit) {
      try {
        step()
      } catch (e: Exception) {
        if (profileExists(connId, slug)) {
          if (!recovered) {
            recovered = true
            _ui.update { it.copy(message = "Finishing setup for $slug…") }
          }
        } else {
          throw e
        }
      }
    }

    val pinPicked = s.provider.isNotBlank() && s.model.isNotBlank()
    guarded {
      createResult = admin.create(
          connectionId = connId,
          name = slug,
          description = s.description.ifBlank { null },
          cloneFrom = s.cloneFrom.ifBlank { null },
          soul = s.soul.ifBlank { null },
          model = s.model.ifBlank { null },
          provider = s.provider.ifBlank { null },
      )
    }
    guarded { waitRosterRow(connId, slug) }

    // F3: model_set says whether the pin actually landed; a silently skipped pin is
    // re-applied via configure (same handshake as the edit path). Also covers the
    // recovered / pre-existing-bot path, where create never carried the pin.
    var pinOk = true
    if (pinPicked) {
      val landed = createResult?.let { BotAdmin.parseCreate(it) }?.modelSet != false
      if (recovered || !landed) {
        pinOk = applyPin(connId, slug, s.provider.trim(), s.model.trim())
      }
    }

    // B3: relay-enable unconditionally — ui_meta["hermes-bots"] is what makes gateways
    // inject `message_agent` into this bot's chats; hidden always written so the payload
    // is never empty (profiles.list hides empty ui_meta).
    guarded {
      admin.configure(
          connectionId = connId,
          name = slug,
          uiMeta = BotAdmin.mergeUiMeta(
              null,
              BotAdmin.UiMetaPatch(sectionId = s.sectionId.ifBlank { null }, hidden = s.hidden),
          ),
      )
    }
    s.pickedAvatar?.let { img ->
      guarded {
        waitRosterRow(connId, slug)
        admin.setAvatar(connId, slug, avatarToDataUrl(img))
      }
    }

    _ui.update {
      it.copy(
          saving = false,
          saved = true,
          // D: create-with-pin stays in the editor for the verification row; no-pin saves
          // pop immediately as before.
          savedPhase = pinPicked,
          relayCapable = true,
          pinProvider = if (pinPicked && pinOk) s.provider.trim().ifBlank { null } else null,
          pinModel = if (pinPicked && pinOk) s.model.trim().ifBlank { null } else null,
          message = when {
            pinPicked && !pinOk -> "Model was not saved — pick it again"
            recovered -> "Saved $slug"
            else -> "Created $slug"
          },
      )
    }
    markClean()
    // R13: auto-test the just-saved pin; save never blocks on it.
    if (pinPicked && pinOk) autoTest(connId, slug)
  }

  private suspend fun saveEdit(connId: String, s: EditorUiState) {
    val name = editName ?: return
    val row = currentRow(connId, name)
    var revisions = row?.bot?.uiMetaRevisions
    val pinPicked = s.provider.isNotBlank() && s.model.isNotBlank()
    val send: suspend (Map<String, Int>?) -> BotAdmin.ConfigureOutcome = { revs ->
      admin.configureOutcome(
          connectionId = connId,
          name = name,
          uiMeta = BotAdmin.mergeUiMeta(null, BotAdmin.UiMetaPatch(sectionId = s.sectionId.ifBlank { null }, hidden = s.hidden)),
          expectedRevisions = revs,
          soul = s.soul,
          description = s.description,
          model = s.model.ifBlank { null },
          provider = s.provider.ifBlank { null },
          disabledSkills = s.skills.filterNot { it.enabled }.map { it.name }.takeIf { it.isNotEmpty() },
          enabledToolsets = s.toolsets.filter { it.enabled }.map { it.name }.takeIf { it.isNotEmpty() },
          enabledMcpServers = s.mcpServers.filter { it.enabled }.map { it.name }.takeIf { it.isNotEmpty() },
      )
    }
    var result = try {
      send(revisions)
    } catch (e: Exception) {
      // CAS conflict: re-read revisions once from the roster and retry.
      revisions = currentRow(connId, name)?.bot?.uiMetaRevisions
      send(revisions)
    }
    if (result.confirmRequired) {
      result = admin.configureOutcome(
          connectionId = connId,
          name = name,
          soul = s.soul,
          description = s.description,
          model = s.model.ifBlank { null },
          provider = s.provider.ifBlank { null },
          confirmExpensiveModel = true,
      )
    }
    s.pickedAvatar?.let { admin.setAvatar(connId, name, avatarToDataUrl(it)) }
    val pinOk = !pinPicked || result.modelApplied != false
    val savedPinProvider = when {
      !pinPicked -> null
      pinOk -> s.provider.trim().ifBlank { null }
      else -> s.pinProvider
    }
    val savedPinModel = when {
      !pinPicked -> null
      pinOk -> s.model.trim().ifBlank { null }
      else -> s.pinModel
    }
    // Edit-save writes ui_meta["hermes-bots"] unconditionally (hidden is always in the
    // patch), so the bot is relay-enabled from now on (B3).
    _ui.update {
      it.copy(
          saving = false,
          saved = true,
          // D/R9: saved phase (with the verification row) whenever a pin rode along or the
          // pin changed; plain no-pin edits keep the instant-pop behavior.
          savedPhase = pinPicked || s.pinChanged,
          relayCapable = true,
          pinProvider = savedPinProvider,
          pinModel = savedPinModel,
          message = if (pinPicked && !pinOk) "Model was not saved — try again" else "Saved $name",
      )
    }
    markClean()
    // R13: auto-test only when the pin actually changed — unchanged edits don't burn a turn.
    if (pinPicked && pinOk && s.pinChanged) autoTest(connId, name)
  }

  /** Re-apply the model pin via profiles.configure (F3 recovery); true when it landed. */
  private suspend fun applyPin(connId: String, slug: String, provider: String, model: String): Boolean = try {
    var outcome = admin.configureOutcome(connectionId = connId, name = slug, model = model, provider = provider)
    if (outcome.confirmRequired) {
      outcome = admin.configureOutcome(
          connectionId = connId, name = slug, model = model, provider = provider, confirmExpensiveModel = true,
      )
    }
    outcome.modelApplied != false
  } catch (e: Exception) {
    false
  }

  /**
   * profiles.list probe for create idempotency (F2/R3): names in the list are canonical
   * slugs, matching the normalized [slug] this flow always sends. Best-effort false.
   */
  private suspend fun profileExists(connId: String, slug: String): Boolean = try {
    val gw = gatewayFor(connId)
    val result = gw.request(
        Catalog.METHOD_PROFILES_LIST,
        buildJsonObject { put("include_sessions", false) },
        30_000,
    )
    val rows = (result["profiles"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    rows.any { (it["name"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content == slug }
  } catch (e: Exception) {
    false
  }

  // --- verification (P9/R13) ---

  /** Retest button: one test at a time, against the currently selected pin. */
  fun retest() {
    val s = _ui.value
    if (verifyJob?.isActive == true) return
    val p = s.provider.trim().ifBlank { null } ?: return
    val m = s.model.trim().ifBlank { null } ?: return
    val slug = if (s.isEdit) editName ?: return else s.nameSlug.ifBlank { return }
    startVerify(s.createOnConnectionId, slug, p, m)
  }

  /** R13: runs in the background; only failure mode is a dead connection — recorded honestly. */
  private fun autoTest(connId: String, slug: String) {
    val s = _ui.value
    val p = s.pinProvider ?: return
    val m = s.pinModel ?: return
    startVerify(connId, slug, p, m)
  }

  private fun startVerify(connId: String, slug: String, provider: String, model: String) {
    if (verifyJob?.isActive == true) return
    verifyJob = viewModelScope.launch {
      try {
        graph.modelVerifier.verify(connId, slug, provider, model)
      } catch (e: Exception) {
        runCatching {
          graph.modelHealth.record(
              connId, provider, model, HealthState.UNTESTED,
              reason = "Gateway isn't connected right now",
          )
        }
      }
    }
  }

  // --- plumbing ---

  private suspend fun gatewayFor(connId: String): HermesGateway =
      graph.gateways.live.first()[connId]?.gateway ?: throw IllegalStateException("connection not live")

  /**
   * B3: per-bot relay state from profiles.list — ui_meta["hermes-bots"] present means the
   * gateway injects `message_agent` into this bot's chats. Best-effort: null on any failure.
   */
  private suspend fun fetchRelayCapable(connId: String, name: String): Boolean? = try {
    val gw = gatewayFor(connId)
    val result = gw.request(
        Catalog.METHOD_PROFILES_LIST,
        buildJsonObject { put("include_sessions", false) },
        30_000,
    )
    val rows = (result["profiles"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    rows.firstOrNull {
      (it["name"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content == name
    }?.let { RosterParsing.relayCapable(it) }
  } catch (e: Exception) {
    null
  }

  private suspend fun currentRow(connId: String, name: String): RosterEntry? =
      graph.roster.roster.firstOrNull()?.firstOrNull { it.bot.connectionId == connId && it.bot.name == name }
          ?: runCatching {
            withTimeout(10_000) {
              graph.roster.roster.first { rows -> rows.any { it.bot.connectionId == connId && it.bot.name == name } }
                  .first { it.bot.connectionId == connId && it.bot.name == name }
            }
          }.getOrNull()

  private suspend fun waitRosterRow(connId: String, name: String) {
    withTimeout(15_000) {
      graph.roster.roster.first { rows -> rows.any { it.bot.connectionId == connId && it.bot.name == name } }
    }
  }

  private fun avatarToDataUrl(image: AvatarImage): String =
      "data:${image.mime};base64," + android.util.Base64.encodeToString(image.bytes, android.util.Base64.NO_WRAP)
}
