package ai.hermes.bots.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Per-connection relay capability (FLEET-CONNECT-SPEC B5). Unknown until first probe. */
enum class RelaySupport { Unknown, Supported, Unsupported }

data class RelayStatus(
    val support: RelaySupport = RelaySupport.Unknown,
    val lastDrainMs: Long? = null,
)

/**
 * Observable per-connection relay state (B5): never latches — the engine re-probes and flips
 * Supported/Unsupported; a successful outbox drain stamps lastDrainMs. Pure holder so the
 * transitions are JVM-unit-testable; GatewayManager owns the instance and exposes `states`.
 */
class RelayStatusBoard {

    private val _states = MutableStateFlow<Map<String, RelayStatus>>(emptyMap())
    val states: StateFlow<Map<String, RelayStatus>> = _states

    fun markSupported(id: String) = transform(id) { it.copy(support = RelaySupport.Supported) }

    fun markUnsupported(id: String) = transform(id) { it.copy(support = RelaySupport.Unsupported) }

    /** A successful drain proves the relay RPCs exist — stamps freshness in one step. */
    fun markDrained(id: String, atMs: Long) =
        transform(id) { it.copy(support = RelaySupport.Supported, lastDrainMs = atMs) }

    /** Connection came up (or was re-created after an edit): back to Unknown. */
    fun reset(id: String) = transform(id) { RelayStatus(lastDrainMs = it.lastDrainMs) }

    /** Connection went away: drop its row so the UI shows nothing stale. */
    fun remove(id: String) {
        _states.update { it - id }
    }

    private fun transform(id: String, f: (RelayStatus) -> RelayStatus) {
        _states.update { cur -> cur + (id to f(cur[id] ?: RelayStatus())) }
    }
}
