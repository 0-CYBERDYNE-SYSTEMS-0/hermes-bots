package ai.hermes.bots.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CronGroupTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `cron job row parses schedule dict and bare string`() {
        val a = CronRepository.parseJob(
            "c1",
            json.parseToJsonElement("""{"id":"j1","name":"Brief","enabled":true,"schedule":{"kind":"cron","expr":"0 9 * * *"},"prompt":"summarize"}""").jsonObject,
        )!!
        assertEquals("0 9 * * *", a.scheduleText)
        assertEquals("Brief", a.name)
        assertTrue(a.enabled)
        val b = CronRepository.parseJob(
            "c1",
            json.parseToJsonElement("""{"id":"j2","schedule":"every 30m","prompt":"p"}""").jsonObject,
        )!!
        assertEquals("every 30m", b.scheduleText)
        assertEquals("j2", b.name)
        assertTrue(b.enabled)
    }

    @Test
    fun `cron job without id rejected`() {
        assertNull(CronRepository.parseJob("c1", json.parseToJsonElement("""{"name":"x"}""").jsonObject))
    }

    @Test
    fun `room parse tolerates room_id and members variants`() {
        val room = GroupRepository.parseRoomForTest(
            "c1",
            json.parseToJsonElement(
                """{"room_id":"r1","name":"warroom","members":[{"profile":"alf","member_id":"alf"},{"profile":"bob"}],"disbanded_at":null}""",
            ).jsonObject,
        )!!
        assertEquals("r1", room.roomId)
        assertEquals("warroom", room.name)
        assertEquals(listOf("alf", "bob"), room.members)
        assertFalse(room.disbanded)
    }

    @Test
    fun `room parse keeps member display names and pending action choice set`() {
        // Q10 (QA 2026-09-14): display_name rides the wire members array; the pending
        // action's choices mirror the driver-sanitized once/deny set.
        val room = GroupRepository.parseRoomForTest(
            "c1",
            json.parseToJsonElement(
                """{"room_id":"r9","name":"fleet","members":[
                     {"profile":"scout","member_id":"scout","display_name":"Scout"},
                     {"profile":"default"}],"disbanded_at":null}""",
            ).jsonObject,
        )!!
        assertEquals(mapOf("scout" to "Scout"), room.memberNames)
        val approval = GroupRepository.parsePendingActionForTest(
            json.parseToJsonElement(
                """{"kind":"approval","task_id":"t9","member_id":"scout","request_id":"q9",
                   "approval":{"choices":["once","session","deny"],"command":"ls"}}""",
            ).jsonObject,
        )!!
        assertEquals(listOf("once", "deny"), approval.choices) // "session" filtered like the driver does
        val noChoices = GroupRepository.parsePendingActionForTest(
            json.parseToJsonElement("""{"kind":"approval","task_id":"t8","member_id":"s"}""").jsonObject,
        )!!
        assertEquals(listOf("once", "deny"), noChoices.choices) // driver default when empty
    }

    @Test
    fun `log entry parses actor variants and text`() {
        val e1 = GroupRepository.parseLogEntryForTest(
            json.parseToJsonElement("""{"event_id":"e1","type":"message.user","actor":{"kind":"user","id":"u1"},"payload":{"text":"hi"}}""").jsonObject,
        )!!
        assertEquals("message.user", e1.kind)
        assertEquals("user", e1.actor)
        assertEquals("hi", e1.text)
        val e2 = GroupRepository.parseLogEntryForTest(
            json.parseToJsonElement("""{"kind":"message.member","actor":"alf","payload":{"text":"yo"}}""").jsonObject,
        )!!
        assertEquals("alf", e2.actor)
        val e3 = GroupRepository.parseLogEntryForTest(
            json.parseToJsonElement(
                """{"kind":"message.member","actor":{"kind":"member","id":"default","profile":"default","display_name":"default"},"payload":{"text":"@default: hi"}}""",
            ).jsonObject,
        )!!
        assertEquals("default", e3.actor)
    }

    @Test
    fun `room member params shape`() {
        val m = GroupRepository.RoomMember("Alf", "Alf").toParams()
        assertEquals("Alf", m["member_id"]!!.jsonPrimitive.content)
        assertEquals("alf", m["handle"]!!.jsonPrimitive.content)
    }

    @Test
    fun `pending action parses approval and retry kinds`() {
        val approval = GroupRepository.parsePendingActionForTest(
            json.parseToJsonElement(
                """{"kind":"approval","task_id":"t1","execution_generation":3,"run_id":"r1",
                   "session_id":"s1","request_id":"q1","member_id":"default",
                   "approval":{"request_id":"q1","choices":["once","deny"],"command":"rm -rf /"}}""",
            ).jsonObject,
        )!!
        assertEquals("approval", approval.kind)
        assertEquals("t1", approval.taskId)
        assertEquals("default", approval.memberId)
        assertEquals(3L, approval.executionGeneration)
        assertEquals("q1", approval.requestId)
        assertEquals("rm -rf /", approval.command)
        val retry = GroupRepository.parsePendingActionForTest(
            json.parseToJsonElement("""{"kind":"retry","task_id":"t2"}""").jsonObject,
        )!!
        assertEquals("retry", retry.kind)
        assertEquals("t2", retry.taskId)
        assertNull(retry.memberId)
        assertEquals(0L, retry.executionGeneration)
        assertNull(retry.command)
        assertNull(GroupRepository.parsePendingActionForTest(json.parseToJsonElement("""{"task_id":"t3"}""").jsonObject))
    }
}
