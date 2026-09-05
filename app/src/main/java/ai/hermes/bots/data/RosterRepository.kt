package ai.hermes.bots.data

import ai.hermes.bots.protocol.Catalog
import ai.hermes.bots.protocol.GatewayEvent
import ai.hermes.bots.protocol.SocketState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import android.util.Base64

data class BotRow(
    val connectionId: String,
    val name: String,
    val displayName: String?,
    val description: String?,
    val model: String?,
    val provider: String?,
    val skillCount: Int,
    val isDefault: Boolean,
    val hasAvatar: Boolean,
    val sectionId: String?,
    val hidden: Boolean,
    val lastPreview: String?,
    val lastActiveMs: Long?,
    val workerActiveMs: Long?,
    val canonicalSessionId: String?,
    val canonicalRootTitle: String?,
    val uiMetaRevisions: Map<String, Int>,
)

data class RosterEntry(
    val bot: BotRow,
    val unread: Boolean,
    val activeNow: Boolean,
)

data class AvatarImage(val mime: String, val bytes: ByteArray)

/**
 * Union roster across connections: profiles.list every Catalog.ROSTER_POLL_MS per live
 * connection, re-polled early on `sessions.changed` (PROTOCOL.md §5.9). Unread = row
 * last_active newer than the client watermark (BOTS-MODE-PARITY.md §3); active-now =
 * last_active within 90 s or fresh worker_session.
 */
class RosterRepository(
    private val manager: GatewayManager,
    private val scope: CoroutineScope,
) {
    private val perConnection = MutableStateFlow<Map<String, List<BotRow>>>(emptyMap())
    private val watermarks = mutableMapOf<String, Long>() // "connId:name" -> last seen lastActiveMs
    private val jobs = mutableMapOf<String, Job>()
    private var syncJob: Job? = null

    private val _roster = MutableStateFlow<List<RosterEntry>>(emptyList())
    val roster: StateFlow<List<RosterEntry>> = _roster

    private val _avatars = MutableStateFlow<Map<String, AvatarImage>>(emptyMap())
    val avatars: StateFlow<Map<String, AvatarImage>> = _avatars

    fun start() {
        if (syncJob != null) return
        syncJob = scope.launch {
            manager.live.collect { live -> reconcile(live) }
        }
    }

    fun markRead(connectionId: String, botName: String) {
        val bot = perConnection.value[connectionId]?.firstOrNull { it.name == botName } ?: return
        val last = bot.lastActiveMs ?: bot.workerActiveMs ?: return
        watermarks["$connectionId:$botName"] = last
        recompute()
    }

    private suspend fun reconcile(live: Map<String, ConnectionLive>) {
        jobs.keys.filterNot { it in live }.forEach { id ->
            jobs.remove(id)?.cancel()
            perConnection.update { it - id }
            _avatars.update { it - id }
            recompute()
        }
        live.forEach { (id, conn) ->
            if (jobs[id] == null) jobs[id] = scope.launch { pollLoop(id, conn) }
        }
    }

    private suspend fun pollLoop(connectionId: String, conn: ConnectionLive) {
        while (true) {
            // First connect can take a moment: wake as soon as the socket is Ready, else re-check
            // on the poll interval. StateFlow.first{} returns immediately when already Ready.
            val ready = withTimeoutOrNull(Catalog.ROSTER_POLL_MS) {
                conn.gateway.state.first { it is SocketState.Ready }
            }
            if (ready == null) {
                withTimeoutOrNull(Catalog.ROSTER_POLL_MS) {
                    conn.gateway.events.first { it.type == Catalog.EVENT_SESSIONS_CHANGED }
                    delay(300)
                }
            } else {
                runCatching { pollOnce(connectionId, conn) }
                    .onFailure { android.util.Log.e("RosterRepo", "pollOnce failed", it) }
                // Normal cadence ROSTER_POLL_MS; sessions.changed wakes us early (debounced).
                withTimeoutOrNull(Catalog.ROSTER_POLL_MS) {
                    conn.gateway.events.first { it.type == Catalog.EVENT_SESSIONS_CHANGED }
                    delay(300)
                }
            }
        }
    }

    private suspend fun pollOnce(connectionId: String, conn: ConnectionLive) {
        val result = conn.gateway.request(
            Catalog.METHOD_PROFILES_LIST,
            buildJsonObject { put("include_sessions", true) },
        )
        val rows = (result["profiles"] as? JsonArray)
            ?.mapNotNull { RosterParsing.botRow(connectionId, it) }
            .orEmpty()
        perConnection.update { it + (connectionId to rows) }
        recompute()
        refreshAvatars(conn, rows)
    }

    private fun recompute() {
        val now = System.currentTimeMillis()
        val entries = perConnection.value.values.flatten().map { bot ->
            val key = "${bot.connectionId}:${bot.name}"
            val last = bot.lastActiveMs ?: bot.workerActiveMs
            val unread = last != null && watermarks.containsKey(key) && last > watermarks.getValue(key)
            val activeNow = bot.lastActiveMs?.let { now - it <= Catalog.ACTIVE_NOW_WINDOW_MS } == true ||
                bot.workerActiveMs?.let { now - it <= Catalog.ACTIVE_NOW_WINDOW_MS } == true
            RosterEntry(bot, unread, activeNow)
        }
        _roster.value = entries
    }

    private suspend fun refreshAvatars(conn: ConnectionLive, rows: List<BotRow>) {
        rows.filter { it.hasAvatar }.forEach { bot ->
            val key = "${bot.connectionId}:${bot.name}"
            try {
                val result = conn.gateway.request(
                    Catalog.METHOD_PROFILES_GET_ASSET,
                    buildJsonObject {
                        put("name", bot.name)
                        put("asset", Catalog.ASSET_AVATAR)
                    },
                )
                val found = (result["found"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
                val cached = _avatars.value[key]
                if (!found) {
                    if (cached != null) _avatars.update { it - key }
                    return@forEach
                }
                val mime = (result["mime"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "image/png"
                val size = (result["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: -1L
                if (cached != null && cached.bytes.size.toLong() == size) return@forEach
                val dataText = (result["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@forEach
                val base64 = if (dataText.startsWith("data:")) dataText.substringAfter(',') else dataText
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                _avatars.update { it + (key to AvatarImage(mime, bytes)) }
            } catch (_: Exception) {
                // avatar fetch is best-effort; roster row still renders with fallback
            }
        }
    }
}
