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
import androidx.lifecycle.SavedStateHandle
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
 * Pure decision helpers for the bot editor — unit-tested
 * in EditorModelTest, no gateway or Android dependencies.
 */
object EditorModel {

  /**
   * "Same as <bot>": (name, provider, model) of roster rows on [connectionId] whose pin
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
   * The pin counts as changed when the current selection differs from the last
   * loaded/saved one — case-insensitive, blank == null.
   */
  fun pinChanged(provider: String, model: String, pinProvider: String?, pinModel: String?): Boolean {
    fun norm(v: String?): String? = v?.trim()?.lowercase()?.ifBlank { null }
    return norm(provider) != norm(pinProvider) || norm(model) != norm(pinModel)
  }

  /** Declining a create-time model warning keeps the rest of the authored form intact. */
  fun withoutModelPin(state: EditorUiState): EditorUiState = state.copy(model = "", provider = "")

  /** A create-time model warning follows profiles.create, so only edit warnings can be canceled. */
  fun canDismissModelWarning(isEdit: Boolean): Boolean = isEdit

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

  /** Dropdown order: authenticated providers first; relative order kept inside each group. */
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

  /** "verified · 2.1 s" when any WORKING health entry exists for this provider's models. */
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

  /** Model-row hint: exact (provider, model) WORKING entry → "verified · X s". */
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
   * Line under one model row. WORKING and FAILED both require the exact model key.
   * A provider-level probe of a different model never appears here.
   */
  fun modelVerdictLine(
    entries: Map<String, HealthEntry>,
    connectionId: String,
    providerSlug: String,
    modelId: String,
  ): String? {
    val entry = entries[ModelHealth.keyFor(connectionId, providerSlug, modelId)] ?: return null
    return when (entry.state) {
      HealthState.WORKING -> modelHealthHint(entries, connectionId, providerSlug, modelId)
      HealthState.FAILED -> entry.reason
      HealthState.TESTING -> "Checking…"
      HealthState.UNTESTED -> null
    }
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

  // ── Probe-backed "ready to use" grouping ──────────────────
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
   *
   * A model-specific failure (credits, rejected id) on the provider the user has selected
   * does not hide that provider. The line belongs on the model that failed, and the user
   * must still be able to pick a different model.
   */
  fun splitProviders(
    providers: List<ProviderOption>,
    entries: Map<String, HealthEntry>,
    connectionId: String,
    selectedProvider: String = "",
    selectedModel: String = "",
  ): Pair<List<ProviderOption>, List<ProviderOption>> {
    val primary = providers.filter { p ->
      p.authenticated && p.models.isNotEmpty() &&
        !providerBlocked(entries, connectionId, p.slug, selectedProvider, selectedModel)
    }
    return primary to (providers - primary.toSet())
  }

  /**
   * Provider-level FAILED hides the row, except a model-specific failure on the
   * provider the user currently has selected. That verdict is for one model.
   */
  fun providerBlocked(
    entries: Map<String, HealthEntry>,
    connectionId: String,
    providerSlug: String,
    selectedProvider: String = "",
    selectedModel: String = "",
  ): Boolean {
    val probe = providerProbe(entries, connectionId, providerSlug) ?: return false
    if (probe.state != HealthState.FAILED) return false
    val selectedHere = providerSlug.trim().equals(selectedProvider.trim(), ignoreCase = true) &&
      selectedModel.isNotBlank()
    return !(selectedHere && ModelHealth.isModelSpecificFailure(probe.reason))
  }

  /** Model id that carries a model-specific FAILED entry for this provider, when there is one. */
  fun failedModelId(
    entries: Map<String, HealthEntry>,
    connectionId: String,
    providerSlug: String,
  ): String? {
    val ids = entries.mapNotNull { (key, entry) ->
      val seg = segments(key) ?: return@mapNotNull null
      if (seg.first == connectionId &&
        seg.second.trim().equals(providerSlug.trim(), ignoreCase = true) &&
        seg.third.isNotBlank() &&
        entry.state == HealthState.FAILED &&
        ModelHealth.isModelSpecificFailure(entry.reason)
      ) {
        seg.third
      } else {
        null
      }
    }.distinct()
    return ids.singleOrNull()
  }

  /** Short humane reason a collapsed provider is not in the ready group. */
  fun moreReason(
    provider: ProviderOption,
    entries: Map<String, HealthEntry>,
    connectionId: String,
  ): String {
    val probe = providerProbe(entries, connectionId, provider.slug)
    if (probe?.state == HealthState.FAILED) {
      val reason = probe.reason ?: "Not working right now"
      val model = failedModelId(entries, connectionId, provider.slug)
      return if (model != null && ModelHealth.isModelSpecificFailure(probe.reason)) {
        "$model — $reason"
      } else {
        reason
      }
    }
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
    val rows = entries.asSequence()
      .mapNotNull { (key, entry) -> segments(key)?.let { it to entry } }
      .filter { (seg, _) ->
        seg.first == connectionId && seg.second.equals(provider.slug, ignoreCase = true) &&
          seg.third.isNotBlank()
      }
      .toList()
    val verified = rows.firstOrNull { (_, entry) -> entry.state == HealthState.WORKING }?.first?.third
    val failed = rows.filter { (_, entry) ->
      entry.state == HealthState.FAILED && ModelHealth.isModelSpecificFailure(entry.reason)
    }.map { it.first.third }.toSet()
    fun usable(id: String) = failed.none { it.equals(id, ignoreCase = true) }
    return verified
      ?: provider.featured.firstOrNull { usable(it) }
      ?: provider.models.firstOrNull { usable(it) }
      ?: provider.featured.firstOrNull()
      ?: provider.models.firstOrNull()
  }

  private const val PROBE_MAX_AGE_MS = 24 * 60 * 60 * 1000L
  private const val PROBE_MAX_PROVIDERS = 6

  /**
   * Model a candidate probe should hit. The selected model wins for the selected
   * provider. Other providers keep the verified / featured / first pick.
   */
  fun modelToProbe(
    provider: ProviderOption,
    entries: Map<String, HealthEntry>,
    connectionId: String,
    selectedProvider: String = "",
    selectedModel: String = "",
  ): String? {
    val selected = selectedModel.trim()
    if (provider.slug.trim().equals(selectedProvider.trim(), ignoreCase = true) && selected.isNotBlank()) {
      val listed = provider.models.firstOrNull { it.equals(selected, ignoreCase = true) }
      if (listed != null || provider.models.isEmpty()) return listed ?: selected
    }
    return pickDefaultModel(provider, entries, connectionId)
  }

  /**
   * Which providers need a candidate probe right now: keyed, model-listing, not custom*
   * (candidate overrides cannot resolve those), and no fresh verdict for the model
   * we would actually test. A credit failure cached for a different model does not
   * block a probe of the selected model. Capped so one editor open cannot fan out.
   */
  fun probeCandidates(
    providers: List<ProviderOption>,
    entries: Map<String, HealthEntry>,
    connectionId: String,
    nowMs: Long,
    selectedProvider: String = "",
    selectedModel: String = "",
  ): List<Pair<String, String>> = providers.asSequence()
    .filter { it.authenticated && it.models.isNotEmpty() }
    .filterNot { it.slug.trim().lowercase().startsWith("custom") }
    .mapNotNull { p ->
      val model = modelToProbe(p, entries, connectionId, selectedProvider, selectedModel)
        ?: return@mapNotNull null
      fun fresh(entry: HealthEntry?) = entry?.takeIf { nowMs - it.checkedAtMs < PROBE_MAX_AGE_MS }
      val exact = fresh(entries[ModelHealth.keyFor(connectionId, p.slug, model)])
      if (exact != null && exact.state != HealthState.TESTING) return@mapNotNull null
      val providerLevel = fresh(providerProbe(entries, connectionId, p.slug))
      val probingSelection = p.slug.trim().equals(selectedProvider.trim(), ignoreCase = true) &&
        model.equals(selectedModel.trim(), ignoreCase = true)
      if (providerLevel != null && providerLevel.state != HealthState.TESTING) {
        val otherModelsFailure = probingSelection && ModelHealth.isModelSpecificFailure(providerLevel.reason)
        if (!otherModelsFailure) return@mapNotNull null
      }
      p.slug to model
    }
    .sortedBy { (slug, _) ->
      if (slug.equals(selectedProvider.trim(), ignoreCase = true)) 0 else 1
    }
    .take(PROBE_MAX_PROVIDERS)
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
   * Resolve a raw provider spelling (this bot's or a donor bot's) to the canonical
   * slug this gateway lists — alias spellings snap, unknown values pass through untouched.
   */
  fun resolveProviderSlug(providers: List<ProviderOption>, raw: String): String {
    val needle = raw.trim()
    if (needle.isEmpty()) return ""
    providers.firstOrNull { it.slug == needle }?.let { return it.slug }
    return providers.firstOrNull { ModelCatalog.matchByAlias(it, needle) }?.slug ?: needle
  }

  /** Server-shaped validation first, then a roster-collision check on the target connection. */
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

internal data class PendingCreateModel(
  val connectionId: String,
  val name: String,
  val provider: String,
  val model: String,
  val warning: String,
)

internal object PendingCreateModelState {
  private const val CONNECTION = "pending_create_connection"
  private const val NAME = "pending_create_name"
  private const val PROVIDER = "pending_create_provider"
  private const val MODEL = "pending_create_model"
  private const val WARNING = "pending_create_warning"

  fun save(handle: SavedStateHandle, pending: PendingCreateModel) {
    handle[CONNECTION] = pending.connectionId
    handle[NAME] = pending.name
    handle[PROVIDER] = pending.provider
    handle[MODEL] = pending.model
    handle[WARNING] = pending.warning
  }

  fun restore(handle: SavedStateHandle): PendingCreateModel? {
    val connectionId = handle.get<String>(CONNECTION) ?: return null
    val name = handle.get<String>(NAME) ?: return null
    val provider = handle.get<String>(PROVIDER) ?: return null
    val model = handle.get<String>(MODEL) ?: return null
    val warning = handle.get<String>(WARNING) ?: "This model may incur extra cost."
    return PendingCreateModel(connectionId, name, provider, model, warning)
  }

  fun clear(handle: SavedStateHandle) {
    handle.remove<String>(CONNECTION)
    handle.remove<String>(NAME)
    handle.remove<String>(PROVIDER)
    handle.remove<String>(MODEL)
    handle.remove<String>(WARNING)
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
    /** null = unknown (not fetched / new bot); surfaced from profiles.list ui_meta. */
    val relayCapable: Boolean? = null,
    val message: String? = null,
    val error: String? = null,
    val saved: Boolean = false,
    // --- Editor decisions ---
    /** Per-gateway provider rows from model.options (authenticated + unconfigured). */
    val providers: List<ProviderOption> = emptyList(),
    val optionsLoading: Boolean = false,
    val optionsError: String? = null,
    /** Free-text escape hatch instead of the provider/model dropdowns. */
    val manualEntry: Boolean = false,
    /** Live normalized preview of the create name — this exact value is what save() sends. */
    val nameSlug: String = "",
    /** botNameError OR roster-collision on the target connection; blocks Save with a reason. */
    val nameProblem: String? = null,
    /** Chosen donor bot for create ("Start from"). */
    val startFrom: String? = null,
    /** The pin as LAST LOADED/SAVED — null for a fresh create until one lands. */
    val pinProvider: String? = null,
    val pinModel: String? = null,
    /** The loaded pin's provider is not offered (exactly or by alias) on this gateway. */
    val pinUnlisted: Boolean = false,
    /** D: post-save phase — editor stays open showing the saved state + verification row. */
    val savedPhase: Boolean = false,
    // --- Per-profile readiness / Prep settings ---
    val prepLoading: Boolean = false,
    val prepLoaded: Boolean = false,
    val prepSaving: Boolean = false,
    val prepError: String? = null,
    val approvalMode: String = "manual",
    val approvalTimeoutSeconds: String = "",
    val clarifyTimeoutSeconds: String = "",
    val commandAllowlist: List<String> = emptyList(),
    val commandAllowlistReported: Boolean = false,
    /** Gateway warning returned by profiles.configure; shown until the user taps Confirm. */
    val confirmMessage: String? = null,
) {
  val pinChanged: Boolean
    get() = EditorModel.pinChanged(provider, model, pinProvider, pinModel)
}

class BotEditorViewModel(
  app: Application,
  private val editConnectionId: String?,
  private val editName: String?,
  private val savedStateHandle: SavedStateHandle,
) :
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

  /** Full health cache for dropdown row hints. */
  val healthMap: StateFlow<Map<String, HealthEntry>> = graph.modelHealth.entries
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

  private var optionsJob: Job? = null
  private var probeJob: Job? = null
  private var verifyJob: Job? = null

  private data class PendingModelSave(
    val connectionId: String,
    val state: EditorUiState,
    val create: Boolean,
  )

  private var pendingModelSave: PendingModelSave? = restorePendingCreateModel()

  private fun restorePendingCreateModel(): PendingModelSave? {
    val pending = PendingCreateModelState.restore(savedStateHandle) ?: return null
    return PendingModelSave(
        connectionId = pending.connectionId,
        state = EditorUiState(
            name = pending.name,
            nameSlug = pending.name,
            model = pending.model,
            provider = pending.provider,
            createOnConnectionId = pending.connectionId,
            confirmMessage = pending.warning,
        ),
        create = true,
    )
  }

  private fun rememberPendingCreateModel(pending: PendingModelSave) {
    if (!pending.create) return
    PendingCreateModelState.save(
        savedStateHandle,
        PendingCreateModel(
            connectionId = pending.connectionId,
            name = pending.state.nameSlug.ifBlank { pending.state.name },
            provider = pending.state.provider,
            model = pending.state.model,
            warning = pending.state.confirmMessage ?: "This model may incur extra cost.",
        ),
    )
  }

  private fun clearPendingCreateModel() {
    PendingCreateModelState.clear(savedStateHandle)
  }

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
    // The model catalog loads on init, whenever the create-on connection changes,
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
      _ui.update { it.copy(loading = true, error = null, prepLoaded = false) }
      try {
        val pendingCreate = pendingModelSave?.takeIf { it.create }
        val connId = editConnectionId ?: pendingCreate?.connectionId ?: defaultConnectionId()
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
          // Per-bot "can message other bots" state from profiles.list ui_meta.
          _ui.update { it.copy(relayCapable = fetchRelayCapable(connId, editName)) }
          loadPrep(connId, editName)
        } else {
          _ui.update { current ->
            val pending = pendingCreate?.state
            if (pending == null) {
              current.copy(loading = false)
            } else {
              current.copy(
                  loading = false,
                  name = pending.name,
                  nameSlug = pending.nameSlug,
                  model = pending.model,
                  provider = pending.provider,
                  confirmMessage = pending.confirmMessage,
              )
            }
          }
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

  /** Load the per-profile values shown in the Prep section. */
  private fun loadPrep(connectionId: String, profile: String) {
    viewModelScope.launch {
      _ui.update {
        it.copy(
            prepLoading = true,
            prepLoaded = false,
            prepError = null,
            approvalTimeoutSeconds = "",
            clarifyTimeoutSeconds = "",
            commandAllowlist = emptyList(),
            commandAllowlistReported = false,
        )
      }
      try {
        val parsed = BotAdmin.parsePrepConfig(admin.configGet(connectionId, profile, "full"))
        _ui.update {
          it.copy(
              prepLoading = false,
              prepLoaded = true,
              prepError = null,
              approvalMode = parsed.approvalMode,
              approvalTimeoutSeconds = parsed.approvalTimeoutSeconds?.toString().orEmpty(),
              clarifyTimeoutSeconds = parsed.clarifyTimeoutSeconds?.toString().orEmpty(),
              commandAllowlist = parsed.commandAllowlist.orEmpty(),
              commandAllowlistReported = parsed.commandAllowlist != null,
          )
        }
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        _ui.update {
          it.copy(
              prepLoading = false,
              prepLoaded = false,
              prepError = e.message ?: "Couldn't read Prep settings",
          )
        }
      }
    }
  }

  fun retryPrep() {
    val state = _ui.value
    if (state.isEdit && state.createOnConnectionId.isNotBlank() && state.name.isNotBlank()) {
      loadPrep(state.createOnConnectionId, state.name)
    }
  }

  /** Approval mode is a persistent profile setting, so commit it as soon as it is tapped. */
  fun setApprovalMode(mode: String) {
    if (mode !in setOf("manual", "smart", "off")) return
    val state = _ui.value
    if (!state.isEdit || state.name.isBlank() || !state.prepLoaded || state.prepLoading ||
        state.prepError != null || state.prepSaving || state.approvalMode == mode
    ) return
    viewModelScope.launch {
      _ui.update { it.copy(approvalMode = mode, prepSaving = true, prepError = null) }
      try {
        admin.configSet(
            connectionId = state.createOnConnectionId,
            profile = state.name,
            key = Catalog.CONFIG_APPROVAL_MODE,
            value = JsonPrimitive(mode),
        )
        _ui.update { it.copy(prepSaving = false) }
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        _ui.update {
          it.copy(approvalMode = state.approvalMode, prepSaving = false, prepError = e.message)
        }
      }
    }
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

  // --- model options ---

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
      val snap = _ui.value
      val candidates = EditorModel.probeCandidates(
          snap.providers,
          healthMap.value,
          connId,
          System.currentTimeMillis(),
          selectedProvider = snap.provider,
          selectedModel = snap.model,
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
   * In edit mode, the loaded pin must resolve against the loaded provider list.
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

  /** Every name change recomputes the normalized slug this editor will actually send. */
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

  /** Picking a provider auto-picks its first (featured-first) model when the current one is absent. */
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
    probeSelectedIfNeeded()
  }

  /** Dropdown pick. The selected model is the one a later probe or retest will hit. */
  fun setModel(model: String) {
    _ui.update { it.copy(model = model) }
    probeSelectedIfNeeded()
  }

  /** One tap adopts another bot's working pin (provider snapped to this gateway's canonical slug). */
  fun adoptPinFrom(provider: String, model: String) {
    _ui.update { st ->
      st.copy(
          provider = EditorModel.resolveProviderSlug(st.providers, provider),
          model = model,
          manualEntry = false,
          pinUnlisted = false,
      )
    }
    probeSelectedIfNeeded()
  }

  /**
   * Probe the model now showing in the editor when it has no fresh verdict of its own.
   * Does not run for custom providers (session overrides cannot resolve those).
   */
  private fun probeSelectedIfNeeded() {
    val s = _ui.value
    val connId = s.createOnConnectionId
    if (connId.isBlank() || s.provider.isBlank() || s.model.isBlank()) return
    if (s.provider.trim().lowercase().startsWith("custom")) return
    val row = s.providers.filter { it.slug.equals(s.provider, ignoreCase = true) }
    val pair = EditorModel.probeCandidates(
        row,
        healthMap.value,
        connId,
        System.currentTimeMillis(),
        selectedProvider = s.provider,
        selectedModel = s.model,
    ).singleOrNull() ?: return
    viewModelScope.launch {
      try {
        graph.modelVerifier.verifyCandidate(connId, pair.first, pair.second)
      } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
      } catch (e: Exception) {
        // The row stays unverified. Retest is still available.
      }
    }
  }

  fun setManualEntry(on: Boolean) = _ui.update { it.copy(manualEntry = on) }

  /** Keep leaves the raw pin editable; Clear empties it so the list takes over. */
  fun resolvePinUnlisted(keepPin: Boolean) = _ui.update { st ->
    if (keepPin) st.copy(pinUnlisted = false) else st.copy(pinUnlisted = false, manualEntry = false, provider = "", model = "")
  }

  /**
   * "Start from" — describe the donor and prefill description/soul/model/provider
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

  // --- Key paste ---

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
      // When this bot's saved pin uses that provider, bridge presence→validity right away.
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

  // --- Save (idempotent create; edit) ---

  fun save() {
    val s = _ui.value
    if (s.saving || s.confirmMessage != null) return
    // Validate before any gateway call: a bad name can never create then half-fail.
    val problem = s.nameProblem
    if (!s.isEdit && problem != null) {
      _ui.update { it.copy(error = problem) }
      return
    }
    viewModelScope.launch {
      pendingModelSave = null
      _ui.update { it.copy(saving = true, error = null, message = null, confirmMessage = null) }
      try {
        val connId = s.createOnConnectionId.ifBlank { editConnectionId ?: defaultConnectionId() }
        if (s.isEdit) saveEdit(connId, s) else saveCreate(connId, s)
      } catch (e: Exception) {
        _ui.update { it.copy(saving = false, error = e.message ?: "save failed") }
      }
    }
  }

  /** Accept the gateway's expensive-model warning and resume the exact save that was paused. */
  fun confirmModelSave() {
    if (_ui.value.saving) return
    val pending = pendingModelSave ?: return
    if (pending.create) {
      _ui.update { it.copy(saving = true, error = null) }
      viewModelScope.launch {
        val outcome = applyPin(
            pending.connectionId,
            pending.state.nameSlug.ifBlank { pending.state.name },
            pending.state.provider.trim(),
            pending.state.model.trim(),
            confirmExpensiveModel = true,
        )
        if (outcome.confirmRequired) {
          val warning = outcome.confirmMessage ?: "This model may incur extra cost."
          val retry = pending.copy(state = pending.state.copy(confirmMessage = warning))
          pendingModelSave = retry
          rememberPendingCreateModel(retry)
          _ui.update { it.copy(saving = false, confirmMessage = warning) }
          return@launch
        }
        if (!outcome.ok || outcome.modelApplied == false) {
          _ui.update {
            it.copy(
                saving = false,
                error = outcome.confirmMessage ?: "Couldn't save the model pin. Try again.",
            )
          }
          return@launch
        }
        pendingModelSave = null
        clearPendingCreateModel()
        _ui.update {
          it.copy(
              saving = false,
              saved = true,
              savedPhase = true,
              relayCapable = true,
              pinProvider = pending.state.provider.trim().ifBlank { null },
              pinModel = pending.state.model.trim().ifBlank { null },
              confirmMessage = null,
              error = null,
              message = "Created ${pending.state.nameSlug.ifBlank { pending.state.name }}",
          )
        }
        markClean()
        autoTest(
            pending.connectionId,
            pending.state.nameSlug.ifBlank { pending.state.name },
        )
      }
      return
    }
    pendingModelSave = null
    clearPendingCreateModel()
    _ui.update { it.copy(saving = true, confirmMessage = null, error = null) }
    viewModelScope.launch {
      try {
        saveEdit(pending.connectionId, pending.state, confirmExpensiveModel = true)
      } catch (e: Exception) {
        _ui.update { it.copy(saving = false, error = e.message ?: "save failed") }
      }
    }
  }

  /** Dismiss a pending model warning without sending `confirm_expensive_model`. */
  fun dismissModelConfirmation() {
    if (pendingModelSave?.create == true) return
    pendingModelSave = null
    _ui.update { it.copy(confirmMessage = null, saving = false) }
  }

  /** Finish a new profile after declining only its optional model pin. */
  fun createWithoutModel() {
    if (_ui.value.saving) return
    val pending = pendingModelSave?.takeIf { it.create } ?: return
    pendingModelSave = null
    _ui.update {
      EditorModel.withoutModelPin(it).copy(
          saving = false,
          saved = true,
          savedPhase = false,
          relayCapable = true,
          pinProvider = null,
          pinModel = null,
          confirmMessage = null,
          error = null,
          message = "Created ${pending.state.nameSlug.ifBlank { pending.state.name }}",
      )
    }
    clearPendingCreateModel()
    markClean()
  }

  private suspend fun saveCreate(
    connId: String,
    s: EditorUiState,
    confirmExpensiveModel: Boolean = false,
  ) {
    val slug = s.nameSlug.ifBlank { ModelCatalog.normalizeBotName(s.name) }
    var recovered = false
    var createResult: JsonObject? = null

    // Idempotency: any failure after (or during) create — "already exists",
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

    suspend fun persistForCreatedProfile(step: suspend () -> Unit) {
      try {
        step()
      } catch (e: Exception) {
        if (!profileExists(connId, slug)) throw e
        if (!recovered) {
          recovered = true
          _ui.update { it.copy(message = "Finishing setup for $slug…") }
        }
        // A profile existing proves only that create landed; retry the idempotent settings
        // write before showing a model warning that may outlive this process.
        step()
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
          // Keep the model out of profiles.create so the guarded configure path below can
          // surface confirm_message before an expensive model is persisted.
          model = null,
          provider = null,
      )
    }
    guarded { waitRosterRow(connId, slug) }

    // Persist profile metadata and avatar before a create-time model confirmation can
    // pause the flow. A process recreation can then resume only the pending model pin.
    persistForCreatedProfile {
      val result = admin.configure(
          connectionId = connId,
          name = slug,
          uiMeta = BotAdmin.mergeUiMeta(
              null,
              BotAdmin.UiMetaPatch(sectionId = s.sectionId.ifBlank { null }, hidden = s.hidden),
          ),
      )
      if (!result.ok) throw IllegalStateException("The gateway didn't save the bot's section and visibility.")
    }
    s.pickedAvatar?.let { img ->
      persistForCreatedProfile {
        val result = admin.setAvatar(connId, slug, avatarToDataUrl(img))
        val ok = (result["ok"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
        if (ok != true) throw IllegalStateException("The gateway didn't save the bot avatar.")
      }
    }

    // model_set says whether the pin actually landed; a silently skipped pin is
    // re-applied via configure (same handshake as the edit path). Also covers the
    // recovered / pre-existing-bot path, where create never carried the pin.
    var pinOk = true
    if (pinPicked) {
      val landed = createResult?.let { BotAdmin.parseCreate(it) }?.modelSet != false
      if (recovered || !landed) {
        val outcome = applyPin(
            connId,
            slug,
            s.provider.trim(),
            s.model.trim(),
            confirmExpensiveModel = confirmExpensiveModel,
        )
        if (outcome.confirmRequired && !confirmExpensiveModel) {
          val warning = outcome.confirmMessage ?: "This model may incur extra cost."
          val pending = PendingModelSave(
              connId,
              s.copy(confirmMessage = warning),
              create = true,
          )
          pendingModelSave = pending
          rememberPendingCreateModel(pending)
          _ui.update {
            it.copy(
                saving = false,
                confirmMessage = warning,
            )
          }
          return
        }
        pinOk = outcome.modelApplied != false
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
    // Auto-test the just-saved pin; save never blocks on it.
    if (pinPicked && pinOk) autoTest(connId, slug)
  }

  private suspend fun saveEdit(
    connId: String,
    s: EditorUiState,
    confirmExpensiveModel: Boolean = false,
  ) {
    val name = editName ?: return
    val row = currentRow(connId, name)
    var revisions = row?.bot?.uiMetaRevisions
    val pinPicked = s.provider.isNotBlank() && s.model.isNotBlank()
    var allowModelWrite = confirmExpensiveModel

    // Probe the model guard by itself first. `profiles.configure` applies its other fields
    // before checking the model, so this keeps a warning from partially saving the editor.
    if (pinPicked && s.pinChanged && !confirmExpensiveModel) {
      val guard = admin.configureOutcome(
          connectionId = connId,
          name = name,
          model = s.model.trim(),
          provider = s.provider.trim(),
      )
      if (guard.confirmRequired) {
        pendingModelSave = PendingModelSave(connId, s, create = false)
        _ui.update {
          it.copy(
              saving = false,
              confirmMessage = guard.confirmMessage ?: "This model may incur extra cost.",
          )
        }
        return
      }
      // The guard call already landed the model; sending the complete patch with the
      // acknowledgement flag avoids asking the gateway to guard the same pick twice.
      allowModelWrite = true
    }

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
          confirmExpensiveModel = allowModelWrite,
          disabledSkills = s.skills.filterNot { it.enabled }.map { it.name }.takeIf { it.isNotEmpty() },
          enabledToolsets = s.toolsets.filter { it.enabled }.map { it.name }.takeIf { it.isNotEmpty() },
          enabledMcpServers = s.mcpServers.filter { it.enabled }.map { it.name }.takeIf { it.isNotEmpty() },
      )
    }
    val result = try {
      send(revisions)
    } catch (e: Exception) {
      // CAS conflict: re-read revisions once from the roster and retry.
      revisions = currentRow(connId, name)?.bot?.uiMetaRevisions
      send(revisions)
    }
    if (result.confirmRequired) {
      // Defensive fallback for gateways that guard after applying another field. Do not
      // auto-confirm: the user must explicitly tap the warning in the editor.
      pendingModelSave = PendingModelSave(connId, s, create = false)
      _ui.update {
        it.copy(
            saving = false,
            confirmMessage = result.confirmMessage ?: "This model may incur extra cost.",
        )
      }
      return
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
    // patch), so the bot is relay-enabled from now on.
    _ui.update {
      it.copy(
          saving = false,
          saved = true,
          // Show the saved phase (with the verification row) whenever a pin rode along or the
          // pin changed; plain no-pin edits keep the instant-pop behavior.
          savedPhase = pinPicked || s.pinChanged,
          relayCapable = true,
          pinProvider = savedPinProvider,
          pinModel = savedPinModel,
          message = if (pinPicked && !pinOk) "Model was not saved — try again" else "Saved $name",
      )
    }
    markClean()
    // Auto-test only when the pin actually changed — unchanged edits don't burn a turn.
    if (pinPicked && pinOk && s.pinChanged) autoTest(connId, name)
  }

  /** Re-apply the model pin via profiles.configure after an unsuccessful save. */
  private suspend fun applyPin(
    connId: String,
    slug: String,
    provider: String,
    model: String,
    confirmExpensiveModel: Boolean = false,
  ): BotAdmin.ConfigureOutcome = try {
    admin.configureOutcome(
        connectionId = connId,
        name = slug,
        model = model,
        provider = provider,
        confirmExpensiveModel = confirmExpensiveModel,
    )
  } catch (e: Exception) {
    BotAdmin.ConfigureOutcome(ok = false, modelApplied = false, confirmRequired = false, confirmMessage = e.message)
  }

  /**
   * profiles.list probe for create idempotency: names in the list are canonical
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

  // --- Verification ---

  /** Retest button: one test at a time, against the currently selected pin. */
  fun retest() {
    val s = _ui.value
    if (verifyJob?.isActive == true) return
    val p = s.provider.trim().ifBlank { null } ?: return
    val m = s.model.trim().ifBlank { null } ?: return
    val slug = if (s.isEdit) editName ?: return else s.nameSlug.ifBlank { return }
    startVerify(s.createOnConnectionId, slug, p, m)
  }

  /** Runs in the background; the only failure mode is a dead connection, recorded honestly. */
  private fun autoTest(connId: String, slug: String) {
    val s = _ui.value
    val p = s.pinProvider ?: return
    val m = s.pinModel ?: return
    startVerify(connId, slug, p, m)
  }

  private fun savedPinMatches(provider: String, model: String): Boolean {
    val s = _ui.value
    fun norm(v: String?) = v?.trim()?.lowercase()?.ifBlank { null }
    return norm(provider) == norm(s.pinProvider) && norm(model) == norm(s.pinModel)
  }

  /**
   * Test the model the editor is showing.
   * Saved pin: [ModelVerifier.verify] runs the profile as stored (no overrides).
   * Unsaved registry pick: [ModelVerifier.verifyCandidate] sends that model.
   * Unsaved custom pick: overrides cannot resolve, so we do not record another
   * model's result under this selection.
   */
  private fun startVerify(connId: String, slug: String, provider: String, model: String) {
    if (verifyJob?.isActive == true) return
    verifyJob = viewModelScope.launch {
      try {
        val custom = provider.trim().lowercase().startsWith("custom")
        val saved = savedPinMatches(provider, model)
        when {
          custom && !saved -> graph.modelHealth.record(
              connId, provider, model, HealthState.UNTESTED,
              reason = "Save to test this model",
          )
          saved || custom -> graph.modelVerifier.verify(connId, slug, provider, model)
          else -> graph.modelVerifier.verifyCandidate(connId, provider, model)
        }
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
   * Per-bot relay state from profiles.list — ui_meta["hermes-bots"] present means the
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
