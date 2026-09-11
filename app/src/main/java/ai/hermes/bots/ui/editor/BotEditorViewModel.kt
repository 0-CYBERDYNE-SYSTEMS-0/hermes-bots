package ai.hermes.bots.ui.editor

import ai.hermes.bots.HermesBotsApp
import ai.hermes.bots.data.AvatarImage
import ai.hermes.bots.data.BotAdmin
import ai.hermes.bots.data.BotAdminRepository
import ai.hermes.bots.data.ConnectionRecord
import ai.hermes.bots.data.RosterEntry
import ai.hermes.bots.data.RosterParsing
import ai.hermes.bots.protocol.Catalog
import ai.hermes.bots.protocol.HermesGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

data class ModelOption(val provider: String, val model: String)

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
    val modelOptions: List<ModelOption> = emptyList(),
    val modelOptionsLoading: Boolean = false,
    val pickedAvatar: AvatarImage? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    /** null = unknown (not fetched / new bot); surfaced from profiles.list ui_meta (B3). */
    val relayCapable: Boolean? = null,
    val message: String? = null,
    val error: String? = null,
    val saved: Boolean = false,
)

class BotEditorViewModel(app: Application, private val editConnectionId: String?, private val editName: String?) :
    AndroidViewModel(app) {

    private val graph = (app as HermesBotsApp).graph
    private val admin = BotAdminRepository(graph.gateways)

    private val _ui = MutableStateFlow(EditorUiState(isEdit = editName != null))
    val ui: StateFlow<EditorUiState> = _ui

    val connections: StateFlow<List<ConnectionRecord>> = graph.connections.connections
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        load()
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
                    _ui.update {
                        it.copy(
                            loading = false,
                            name = editName,
                            description = snap.description.ifBlank { row.description.orEmpty() },
                            soul = snap.soul,
                            model = snap.modelDefault.ifBlank { row.model.orEmpty() },
                            provider = snap.modelProvider.ifBlank { row.provider.orEmpty() },
                            sectionId = row.sectionId.orEmpty(),
                            hidden = row.hidden,
                            skills = snap.skills,
                            toolsets = snap.toolsets,
                            mcpServers = snap.mcpServers,
                        )
                    }
                    // B3: per-bot "can message other bots" state from profiles.list ui_meta.
                    _ui.update { it.copy(relayCapable = fetchRelayCapable(connId, editName)) }
                } else {
                    _ui.update { it.copy(loading = false) }
                }
            } catch (e: Exception) {
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

    fun loadModelOptions() {
        if (_ui.value.modelOptionsLoading) return
        viewModelScope.launch {
            _ui.update { it.copy(modelOptionsLoading = true) }
            try {
                val connId = _ui.value.createOnConnectionId.ifBlank { editConnectionId ?: defaultConnectionId() }
                val gw = gatewayFor(connId)
                val result = gw.request(Catalog.METHOD_MODEL_OPTIONS, JsonObject(emptyMap()), 60_000)
                val options = (result["providers"] as? JsonArray)?.flatMap { p ->
                    val po = p as? JsonObject ?: return@flatMap emptyList()
                    val provider = listOf("slug", "id", "name").firstNotNullOfOrNull { k ->
                        (po[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    } ?: return@flatMap emptyList()
                    // Gateways emit model entries as bare strings ("claude-sonnet-5") —
                    // tolerate objects too, but never drop the string form (all-models-empty bug).
                    val models = (po["models"] as? JsonArray)?.mapNotNull { m ->
                        when {
                            m is JsonPrimitive && m.isString -> m.content
                            m is JsonObject -> listOf("id", "model", "name", "slug").firstNotNullOfOrNull { k ->
                                (m[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
                            }
                            else -> null
                        }
                    }.orEmpty()
                    models.map { ModelOption(provider, it) }
                }.orEmpty()
                _ui.update { it.copy(modelOptions = options, modelOptionsLoading = false) }
            } catch (e: Exception) {
                _ui.update { it.copy(modelOptionsLoading = false, error = "model options failed: ${e.message}") }
            }
        }
    }

    fun setPickedAvatar(image: AvatarImage?) = _ui.update { it.copy(pickedAvatar = image) }

    fun save() {
        val s = _ui.value
        if (s.saving) return
        viewModelScope.launch {
            _ui.update { it.copy(saving = true, error = null, message = null) }
            try {
                val connId = s.createOnConnectionId.ifBlank { editConnectionId ?: defaultConnectionId() }
                if (s.isEdit) {
                    val row = currentRow(connId, editName!!)
                    var revisions = row?.bot?.uiMetaRevisions
                    val save: suspend (Map<String, Int>?) -> BotAdmin.ConfigureResult = { revs ->
                        admin.configure(
                            connectionId = connId,
                            name = editName!!,
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
                        save(revisions)
                    } catch (e: Exception) {
                        // CAS conflict: re-read revisions once from the roster and retry.
                        revisions = currentRow(connId, editName!!)?.bot?.uiMetaRevisions
                        save(revisions)
                    }
                    if (result.confirmRequired) {
                        result = admin.configure(
                            connectionId = connId,
                            name = editName!!,
                            soul = s.soul,
                            description = s.description,
                            model = s.model.ifBlank { null },
                            provider = s.provider.ifBlank { null },
                            confirmExpensiveModel = true,
                        )
                    }
                    s.pickedAvatar?.let { admin.setAvatar(connId, editName!!, avatarToDataUrl(it)) }
                    // Edit-save writes ui_meta["hermes-bots"] unconditionally (hidden is always
                    // in the patch), so the bot is relay-enabled from now on (B3).
                    _ui.update { it.copy(saving = false, saved = true, relayCapable = true, message = "Saved ${editName}") }
                } else {
                    admin.create(
                        connectionId = connId,
                        name = s.name.trim(),
                        description = s.description.ifBlank { null },
                        cloneFrom = s.cloneFrom.ifBlank { null },
                        soul = s.soul.ifBlank { null },
                        model = s.model.ifBlank { null },
                        provider = s.provider.ifBlank { null },
                    )
                    // B3: relay-enable unconditionally. ui_meta["hermes-bots"] is what makes
                    // gateways inject `message_agent` into this bot's chats (bot_mode_probe
                    // is_bot_mode_managed). `hidden` is always written so the payload is never
                    // empty — profiles.list hides empty ui_meta, and we surface relay state
                    // from that field. A failure here surfaces via the outer handler; re-saving
                    // the bot in the editor re-writes the flag (edit path writes it too).
                    waitRosterRow(connId, s.name.trim())
                    admin.configure(
                        connectionId = connId,
                        name = s.name.trim(),
                        uiMeta = BotAdmin.mergeUiMeta(
                            null,
                            BotAdmin.UiMetaPatch(sectionId = s.sectionId.ifBlank { null }, hidden = s.hidden),
                        ),
                    )
                    s.pickedAvatar?.let {
                        waitRosterRow(connId, s.name.trim())
                        admin.setAvatar(connId, s.name.trim(), avatarToDataUrl(it))
                    }
                    _ui.update { it.copy(saving = false, saved = true, relayCapable = true, message = "Created ${s.name.trim()}") }
                }
            } catch (e: Exception) {
                _ui.update { it.copy(saving = false, error = e.message ?: "save failed") }
            }
        }
    }

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

    fun rosterBots(): List<RosterEntry> = graph.roster.roster.value
}
