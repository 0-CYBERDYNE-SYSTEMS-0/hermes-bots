package ai.hermes.bots.ui.roster

import ai.hermes.bots.data.BotRow
import ai.hermes.bots.data.ConnectionRecord
import ai.hermes.bots.data.MergedBot
import ai.hermes.bots.data.RosterEntry
import ai.hermes.bots.protocol.GatewayAuth
import ai.hermes.bots.protocol.SocketState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetPresentationTest {
    @Test
    fun `groups include empty gateways and put primary first`() {
        val primary = connection("zulu", primary = true)
        val empty = connection("Alpha")
        val rows = listOf(merged("zulu", "worker"))

        val groups = FleetPresentation.groups(rows, listOf(empty, primary), mapOf("zulu" to ready()))

        assertEquals(listOf("zulu", "Alpha"), groups.map { it.connection.id })
        assertEquals(1, groups.first().bots.size)
        assertTrue(groups[1].bots.isEmpty())
        assertEquals(1, groups.first().activeCount)
        assertEquals(1, groups.first().unreadCount)
    }

    @Test
    fun `saved override wins over smart default`() {
        val primary = FleetPresentation.groups(
            listOf(merged("one", "worker", active = false, unread = false)),
            listOf(connection("one", primary = true)),
            emptyMap(),
        ).single()
        val quiet = FleetPresentation.groups(
            emptyList(),
            listOf(connection("two")),
            mapOf("two" to ready()),
        ).single()

        assertTrue(FleetPresentation.isExpanded(primary, null))
        assertFalse(FleetPresentation.isExpanded(quiet, null))
        assertFalse(FleetPresentation.isExpanded(primary, false))
        assertTrue(FleetPresentation.isExpanded(quiet, true))
    }

    @Test
    fun `smart default opens attention and connection-problem machines`() {
        val readyQuiet = FleetPresentation.groups(
            listOf(merged("quiet", "idle", active = false, unread = false)),
            listOf(connection("quiet")),
            mapOf("quiet" to ready()),
        ).single()
        val readyUnread = FleetPresentation.groups(
            listOf(merged("unread", "scout", active = false, unread = true)),
            listOf(connection("unread")),
            mapOf("unread" to ready()),
        ).single()
        val offline = FleetPresentation.groups(
            emptyList(),
            listOf(connection("offline")),
            emptyMap(),
        ).single()

        assertFalse(FleetPresentation.isExpanded(readyQuiet, null))
        assertTrue(FleetPresentation.isExpanded(readyUnread, null))
        assertTrue(FleetPresentation.isExpanded(offline, null))
    }

    @Test
    fun `default assistant and unsectioned bots sort before custom sections`() {
        val rows = listOf(
            merged("one", "zeta", sectionId = "Work"),
            merged("one", "alpha"),
            merged("one", "default"),
        )

        val names = FleetPresentation.groups(
            rows,
            listOf(connection("one")),
            mapOf("one" to ready()),
        ).single().bots.map { it.name }

        assertEquals(listOf("default", "alpha", "zeta"), names)
    }

    private fun connection(id: String, primary: Boolean = false) = ConnectionRecord(
        id = id,
        label = id,
        baseUrl = "http://$id",
        auth = GatewayAuth.TokenAuth("token"),
        primary = primary,
    )

    private fun ready() = SocketState.Ready("epoch")

    private fun merged(
        connectionId: String,
        name: String,
        active: Boolean = true,
        unread: Boolean = true,
        sectionId: String? = null,
    ) = MergedBot(
        name,
        RosterEntry(
            BotRow(
                connectionId = connectionId,
                name = name,
                displayName = null,
                description = null,
                model = null,
                provider = null,
                skillCount = 0,
                isDefault = name == "default",
                hasAvatar = false,
                sectionId = sectionId,
                hidden = false,
                lastPreview = null,
                lastActiveMs = null,
                workerActiveMs = null,
                canonicalSessionId = null,
                canonicalRootTitle = null,
                uiMetaRevisions = emptyMap(),
            ),
            unread,
            active,
        ),
        emptyList(),
    )
}
