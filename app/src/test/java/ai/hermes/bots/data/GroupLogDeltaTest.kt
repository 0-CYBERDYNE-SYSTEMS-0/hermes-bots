package ai.hermes.bots.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * QA 2026-09-18: groups.log used to be fetched as a fixed window {since_seq: 0, limit: 100}
 * on every poll, so rounds longer than the window never arrived (the log "froze"). The
 * fetch is now a delta from the last seen seq; these tests pin the merge contract.
 */
class GroupLogDeltaTest {

    private fun entry(seq: Long, text: String = "m$seq") = GroupLogEntry(
        eventId = "e$seq",
        kind = "message.member",
        actor = "bot",
        text = text,
        raw = buildJsonObject { put("seq", seq) },
        seq = seq,
    )

    @Test
    fun `first fetch replaces the transcript and adopts the highest seq`() {
        val (merged, next) = GroupRepository.mergeLog(emptyList(), listOf(entry(1), entry(2), entry(3)), 0L)
        assertEquals(listOf("m1", "m2", "m3"), merged.map { it.text })
        assertEquals(3L, next)
    }

    @Test
    fun `delta appends only strictly newer events`() {
        val prev = listOf(entry(1), entry(2), entry(3))
        val (merged, next) = GroupRepository.mergeLog(prev, listOf(entry(3), entry(4), entry(5)), 3L)
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5"), merged.map { it.text })
        assertEquals(5L, next)
    }

    @Test
    fun `server that ignores since_seq and replays old events changes nothing`() {
        val prev = listOf(entry(1), entry(2), entry(3))
        val (merged, next) = GroupRepository.mergeLog(prev, listOf(entry(1), entry(2), entry(3)), 3L)
        assertEquals(prev, merged)
        assertEquals(3L, next)
    }

    @Test
    fun `unsequenced deltas keep the old replace-per-poll behavior`() {
        val stale = listOf(entry(9))
        val unsequenced = listOf(entry(0, "fresh a"), entry(0, "fresh b"))
        val (merged, next) = GroupRepository.mergeLog(stale, unsequenced, 9L)
        assertEquals(listOf("fresh a", "fresh b"), merged.map { it.text })
        assertEquals(9L, next)
    }

    @Test
    fun `empty delta keeps the transcript as is`() {
        val prev = listOf(entry(1), entry(2))
        val (merged, next) = GroupRepository.mergeLog(prev, emptyList(), 2L)
        assertEquals(prev, merged)
        assertEquals(2L, next)
    }

    @Test
    fun `log parser reads the event seq`() {
        val parsed = GroupRepository.parseLogEntryForTest(
            buildJsonObject {
                put("type", "message.member")
                put("seq", 42)
                put("actor", buildJsonObject { put("profile", "grunt") })
                put("payload", buildJsonObject { put("text", "hello") })
            },
        )
        assertEquals(42L, parsed?.seq)
        assertEquals("hello", parsed?.text)
    }

    @Test
    fun `log parser tolerates a string seq`() {
        val parsed = GroupRepository.parseLogEntryForTest(
            buildJsonObject {
                put("type", "message.member")
                put("seq", JsonPrimitive("7"))
                put("payload", buildJsonObject { put("text", "hi") })
            },
        )
        assertEquals(7L, parsed?.seq)
    }
}
