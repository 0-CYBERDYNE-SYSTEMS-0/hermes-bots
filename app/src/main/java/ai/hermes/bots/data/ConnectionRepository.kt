package ai.hermes.bots.data

import ai.hermes.bots.protocol.GatewayAuth
import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.connectionStore: androidx.datastore.core.DataStore<Preferences> by preferencesDataStore(name = "connections")

@Serializable
data class ConnectionRecord(
    val id: String,
    val label: String,
    val baseUrl: String, // normalized: scheme://host:port
    val auth: GatewayAuth,
    val primary: Boolean = false,
)

class ConnectionRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val keyRecords = stringPreferencesKey("connections_json")

    val connections: Flow<List<ConnectionRecord>> = context.connectionStore.data.map { prefs ->
        prefs[keyRecords]?.let { text ->
            runCatching { json.decodeFromString<List<ConnectionRecord>>(text) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    suspend fun upsert(record: ConnectionRecord) = mutate { cur ->
        val merged = cur.filterNot { it.id == record.id } + record
        val withPrimary = if (merged.none { it.primary }) {
            merged.map { if (it.id == record.id) it.copy(primary = true) else it }
        } else {
            merged
        }
        withPrimary to null
    }

    suspend fun delete(id: String) = mutate { cur ->
        val remaining = cur.filterNot { it.id == id }
        val withPrimary = if (remaining.isNotEmpty() && remaining.none { it.primary }) {
            remaining.mapIndexed { i, r -> if (i == 0) r.copy(primary = true) else r }
        } else {
            remaining
        }
        withPrimary to null
    }

    suspend fun setPrimary(id: String) = mutate { cur ->
        cur.map { it.copy(primary = it.id == id) } to null
    }

    /** First-run convenience: one local-dev connection so a fresh install can connect immediately. */
    suspend fun ensureSeed() = mutate { cur ->
        if (cur.isEmpty()) {
            listOf(
                ConnectionRecord(
                    id = UUID.randomUUID().toString(),
                    label = "Local gateway",
                    baseUrl = "http://127.0.0.1:9119",
                    auth = GatewayAuth.TokenAuth(token = "dev-token-9119"),
                    primary = true,
                ),
            ) to null
        } else {
            cur to null
        }
    }

    private suspend fun mutate(
        block: (List<ConnectionRecord>) -> Pair<List<ConnectionRecord>, String?>,
    ) {
        context.connectionStore.edit { prefs ->
            val (next, _) = block(currentList(prefs))
            prefs[keyRecords] = json.encodeToString(next)
        }
    }

    private fun currentList(prefs: Preferences): List<ConnectionRecord> =
        prefs[keyRecords]?.let { text ->
            runCatching { json.decodeFromString<List<ConnectionRecord>>(text) }.getOrDefault(emptyList())
        } ?: emptyList()
}
