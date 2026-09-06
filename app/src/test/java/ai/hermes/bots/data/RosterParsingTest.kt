package ai.hermes.bots.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RosterParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `iso last_active parses with offset and zulu equal`() {
        val a = RosterParsing.lastActiveMs(json.parseToJsonElement("\"2026-09-05T12:00:00Z\""))
        val b = RosterParsing.lastActiveMs(json.parseToJsonElement("\"2026-09-05T14:00:00+02:00\""))
        assertEquals(a, b)
        assertTrue(a != null && a > 1_700_000_000_000)
    }

    @Test
    fun `epoch number last_active parses`() {
        val ms = RosterParsing.lastActiveMs(json.parseToJsonElement("1736000000000"))
        assertEquals(1736000000000L, ms)
    }

    @Test
    fun `fractional epoch seconds last_active parses to ms`() {
        // Server sends last_active as epoch seconds with a fraction (PROTOCOL.md §5.3).
        val ms = RosterParsing.lastActiveMs(json.parseToJsonElement("1788676747.686302"))
        assertEquals(1788676747686L, ms)
    }

    @Test
    fun `garbage and null last_active are null`() {
        assertNull(RosterParsing.lastActiveMs(json.parseToJsonElement("\"not-a-date\"")))
        assertNull(RosterParsing.lastActiveMs(null))
    }

    @Test
    fun `section and hidden extracted from hermes-bots ui_meta`() {
        val row = json.parseToJsonElement(
            """{"name":"alf","ui_meta":{"hermes-bots":{"sectionId":"crew","hidden":true}}}""",
        ).jsonObject
        assertEquals("crew" to true, RosterParsing.sectionAndHidden(row))
    }

    @Test
    fun `missing ui_meta yields null section and false hidden`() {
        val row = json.parseToJsonElement("""{"name":"alf"}""").jsonObject
        assertEquals(null to false, RosterParsing.sectionAndHidden(row))
    }

    @Test
    fun `revisions map parsed`() {
        val row = json.parseToJsonElement(
            """{"name":"alf","ui_meta_revisions":{"hermes-bots":7}}""",
        ).jsonObject
        assertEquals(mapOf("hermes-bots" to 7), RosterParsing.revisions(row))
    }

    @Test
    fun `full row parses to BotRow`() {
        val row = json.parseToJsonElement(
            """{"name":"alf","display_name":"Alf","description":"helper","model":"m1","provider":"p1",
               "skill_count":3,"is_default":false,"has_avatar":true,
               "last_session":{"preview":"hi","last_active":"2026-09-05T12:00:00Z"},
               "worker_session":{"id":"w","last_active":"2026-09-05T12:00:30Z"},
               "canonical_session":{"id":"s0","resolved_id":"s1","root_title":"Bot Chat"},
               "ui_meta":{"hermes-bots":{"sectionId":"x"}},
               "ui_meta_revisions":{"hermes-bots":2}}""",
        ).jsonObject
        val bot = RosterParsing.botRow("conn1", row)!!
        assertEquals("alf", bot.name)
        assertEquals("Alf", bot.displayName)
        assertEquals("m1", bot.model)
        assertEquals(3, bot.skillCount)
        assertEquals(true, bot.hasAvatar)
        assertEquals("x", bot.sectionId)
        assertEquals("hi", bot.lastPreview)
        assertTrue(bot.lastActiveMs != null)
        assertTrue(bot.workerActiveMs != null)
        assertEquals("s1", bot.canonicalSessionId)
        assertEquals("Bot Chat", bot.canonicalRootTitle)
        assertEquals(2, bot.uiMetaRevisions["hermes-bots"])
        assertFalse(bot.hidden)
    }

    @Test
    fun `row without name is rejected`() {
        val row = json.parseToJsonElement("""{"display_name":"nope"}""").jsonObject
        assertNull(RosterParsing.botRow("conn1", row))
    }
}
