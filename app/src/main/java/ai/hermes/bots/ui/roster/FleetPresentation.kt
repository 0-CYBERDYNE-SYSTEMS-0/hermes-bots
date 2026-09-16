package ai.hermes.bots.ui.roster

import ai.hermes.bots.data.ConnectionRecord
import ai.hermes.bots.data.MergedBot
import ai.hermes.bots.protocol.SocketState
import java.util.Locale

/** Pure presentation model for Fleet machine headers and their grouped bot rows. */
data class MachineGroup(
    val connection: ConnectionRecord,
    val state: SocketState,
    val bots: List<MergedBot>,
) {
    val activeCount: Int get() = bots.count { it.activeNow }
    val unreadCount: Int get() = bots.count { it.unread }
    val botCount: Int get() = bots.size
    val isPrimary: Boolean get() = connection.primary
}

object FleetPresentation {
    fun groups(
        rows: List<MergedBot>,
        connections: List<ConnectionRecord>,
        socketStates: Map<String, SocketState>,
    ): List<MachineGroup> {
        val byConnection = rows.groupBy { it.primary.bot.connectionId }
        return connections
            .sortedWith(compareByDescending<ConnectionRecord> { it.primary }.thenBy { it.label.lowercase(Locale.ROOT) })
            .map { connection ->
                MachineGroup(
                    connection = connection,
                    state = socketStates[connection.id] ?: SocketState.Idle,
                    bots = byConnection[connection.id].orEmpty().sortedWith(
                        compareByDescending<MergedBot> { it.name.equals(DEFAULT_ASSISTANT_NAME, ignoreCase = true) }
                            .thenBy { it.sectionId != null }
                            .thenBy { it.sectionId?.lowercase(Locale.ROOT) ?: "" }
                            .thenBy { it.name.lowercase(Locale.ROOT) },
                    ),
                )
            }
    }

    fun isExpanded(group: MachineGroup, override: Boolean?): Boolean =
        override ?: (
            group.isPrimary ||
                group.activeCount > 0 ||
                group.unreadCount > 0 ||
                group.state !is SocketState.Ready
            )
}
