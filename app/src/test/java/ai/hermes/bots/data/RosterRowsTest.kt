package ai.hermes.bots.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RosterRowsTest {

    private fun bot(
        conn: String,
        name: String = "default",
        lastActive: Long? = null,
        hidden: Boolean = false,
        preview: String? = null,
        section: String? = null,
    ) = BotRow(
        connectionId = conn,
        name = name,
        displayName = null,
        description = "desc",
        model = null,
        provider = null,
        skillCount = 0,
        isDefault = name == "default",
        hasAvatar = false,
        sectionId = section,
        hidden = hidden,
        lastPreview = preview,
        lastActiveMs = lastActive,
        workerActiveMs = null,
        canonicalSessionId = null,
        canonicalRootTitle = null,
        uiMetaRevisions = emptyMap(),
    )

    private fun entry(bot: BotRow, unread: Boolean = false, activeNow: Boolean = false) =
        RosterEntry(bot, unread, activeNow)

    @Test
    fun `every instance gets its own row even when names collide across gateways`() {
        val rows = rowsPerGateway(
            listOf(
                entry(bot("gw1")),
                entry(bot("gw2")),
                entry(bot("gw1", name = "scout")),
            ),
            labels = mapOf("gw1" to "Local dev", "gw2" to "Relay GW"),
        )
        assertEquals(3, rows.size)
        assertEquals(listOf("gw1", "gw2", "gw1"), rows.map { it.primary.bot.connectionId })
        assertEquals(listOf("default", "default", "scout"), rows.map { it.name })
    }

    @Test
    fun `same-named rows cluster by name then gateway label`() {
        val rows = rowsPerGateway(
            listOf(
                entry(bot("m1", name = "scout")),
                entry(bot("gw2")),
                entry(bot("gw1")),
            ),
            labels = mapOf("gw1" to "Local dev", "gw2" to "Relay GW", "m1" to "tailnet-gateway"),
        )
        // default rows first (name), ordered by gateway label, then scout.
        assertEquals(listOf("gw1", "gw2", "m1"), rows.map { it.primary.bot.connectionId })
    }

    @Test
    fun `name clustering is case-insensitive and falls back to connection id`() {
        val rows = rowsPerGateway(
            listOf(
                entry(bot("b", name = "Scout")),
                entry(bot("a", name = "apple")),
                entry(bot("c", name = "scout")),
            ),
        )
        assertEquals(listOf("a", "b", "c"), rows.map { it.primary.bot.connectionId })
        assertEquals(listOf("Scout", "apple", "scout").sortedBy { it.lowercase() }, rows.map { it.name })
    }

    @Test
    fun `row state mirrors its single instance`() {
        val row = rowsPerGateway(
            listOf(entry(bot("gw1", lastActive = 5_000L, hidden = true, preview = "hi", section = "Scouts"), unread = true, activeNow = true)),
        ).single()
        assertTrue(row.unread)
        assertTrue(row.activeNow)
        assertTrue(row.hidden)
        assertEquals("Scouts", row.sectionId)
        assertEquals("hi", row.preview)
        assertEquals(5_000L, row.lastActiveMs)

        val plain = rowsPerGateway(listOf(entry(bot("gw1")))).single()
        assertFalse(plain.hidden)
        assertNull(plain.sectionId)
        assertEquals("desc", plain.preview)
    }
}
