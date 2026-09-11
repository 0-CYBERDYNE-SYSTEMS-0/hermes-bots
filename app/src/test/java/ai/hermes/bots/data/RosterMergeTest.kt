package ai.hermes.bots.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RosterMergeTest {

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
    fun `same-named bots merge into one row with ready primary and freshest-first instances`() {
        val entries = listOf(
            entry(bot("gw1", lastActive = 1_000L, preview = "old")),
            entry(bot("gw2", lastActive = 3_000L, preview = "fresh"), unread = true),
            entry(bot("m1", lastActive = 2_000L, preview = "mid"), activeNow = true),
        )
        val merged = MergedBot.mergeByName(entries, isReady = { it == "gw2" || it == "m1" }, primaryConnectionId = "m1")
        assertEquals(1, merged.size)
        val row = merged.single()
        assertEquals("default", row.name)
        // Ready beats primary-gateway when picking what a tap opens? No — primary gateway
        // wins only among READY connections; gw1 is down, so Ready + primary = m1.
        assertEquals("m1", row.primary.bot.connectionId)
        assertEquals(listOf("gw2", "m1", "gw1"), row.instances.map { it.bot.connectionId })
        assertTrue(row.unread)
        assertTrue(row.activeNow)
        assertEquals("fresh", row.preview)
        assertEquals(3_000L, row.lastActiveMs)
        assertFalse(row.hidden)
    }

    @Test
    fun `primary prefers primary gateway among ready and skips hidden instances`() {
        val entries = listOf(
            entry(bot("a", lastActive = 9_000L, hidden = true)),
            entry(bot("b", lastActive = 1_000L)),
            entry(bot("c", lastActive = 5_000L)),
        )
        val merged = MergedBot.mergeByName(entries, isReady = { it != "a" }, primaryConnectionId = "c")
        // b and c are ready; c is the primary gateway → wins despite b being staler? No:
        // freshness ranks below ready/primary, so c wins. Hidden a loses outright.
        assertEquals("c", merged.single().primary.bot.connectionId)
        assertEquals(listOf("a", "c", "b"), merged.single().instances.map { it.bot.connectionId })
    }

    @Test
    fun `row is hidden only when every instance is hidden and section falls back`() {
        val entries = listOf(
            entry(bot("a", hidden = true)),
            entry(bot("b", hidden = true, section = "Scouts")),
        )
        val row = MergedBot.mergeByName(entries).single()
        assertTrue(row.hidden)
        assertEquals("Scouts", row.sectionId)
        val visible = MergedBot.mergeByName(entries + entry(bot("c", section = null))).single()
        assertFalse(visible.hidden)
        assertNull(visible.sectionId)
    }

    @Test
    fun `distinct names never merge and case is preserved`() {
        val entries = listOf(
            entry(bot("a", name = "scout")),
            entry(bot("b", name = "Scout")),
            entry(bot("b", name = "recon")),
        )
        val merged = MergedBot.mergeByName(entries)
        assertEquals(2, merged.size)
        assertEquals("scout", merged.first { it.instances.size == 2 }.name)
    }
}
