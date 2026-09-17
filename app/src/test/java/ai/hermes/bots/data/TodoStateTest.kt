package ai.hermes.bots.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Agent-ux P0 (spec §5): the todo.updated payload is only "normalized todo state" in
 * PROTOCOL.md, so the parser must accept the common shapes and render nothing on junk.
 */
class TodoStateTest {

    @Test
    fun `array payload parses with status mapping`() {
        val payload = Json.parseToJsonElement(
            """[
                {"content": "ship it", "status": "completed"},
                {"content": "testing", "status": "in_progress"},
                {"content": "later", "status": "pending"}
            ]""",
        )
        assertEquals(
            listOf(
                TodoItem("ship it", TodoStatus.DONE),
                TodoItem("testing", TodoStatus.ACTIVE),
                TodoItem("later", TodoStatus.PENDING),
            ),
            TodoState.parse(payload),
        )
    }

    @Test
    fun `object wrapper keys are tolerated`() {
        val viaTodos = Json.parseToJsonElement(
            """{"todos": [{"content": "a", "status": "done"}]}""",
        )
        val viaItems = Json.parseToJsonElement(
            """{"items": [{"text": "b", "state": "active"}]}""",
        )
        assertEquals(listOf(TodoItem("a", TodoStatus.DONE)), TodoState.parse(viaTodos))
        assertEquals(listOf(TodoItem("b", TodoStatus.ACTIVE)), TodoState.parse(viaItems))
    }

    @Test
    fun `status aliases and unknown values map conservatively`() {
        val payload = Json.parseToJsonElement(
            """[
                {"content": "a", "status": "finished"},
                {"content": "b", "status": "running"},
                {"content": "c", "status": "huh"},
                {"content": "d"}
            ]""",
        )
        assertEquals(
            listOf(
                TodoItem("a", TodoStatus.DONE),
                TodoItem("b", TodoStatus.ACTIVE),
                TodoItem("c", TodoStatus.PENDING),
                TodoItem("d", TodoStatus.PENDING),
            ),
            TodoState.parse(payload),
        )
    }

    @Test
    fun `items without content are skipped`() {
        val payload = Json.parseToJsonElement(
            """[{"status": "done"}, {"content": "kept"}]""",
        )
        assertEquals(listOf(TodoItem("kept", TodoStatus.PENDING)), TodoState.parse(payload))
    }

    @Test
    fun `unparseable payloads render nothing`() {
        assertTrue(TodoState.parse(null).isEmpty())
        assertTrue(TodoState.parse(Json.parseToJsonElement("null")).isEmpty())
        assertTrue(TodoState.parse(Json.parseToJsonElement("\"junk\"")).isEmpty())
        assertTrue(TodoState.parse(Json.parseToJsonElement("{}")).isEmpty())
        assertTrue(TodoState.parse(Json.parseToJsonElement("[1, 2, 3]")).isEmpty())
    }

    @Test
    fun `long plans are capped`() {
        val items = (1..25).joinToString(",") { n -> """{"content": "step $n"}""" }
        val payload = Json.parseToJsonElement("[$items]")
        assertEquals(TodoState.MAX_ITEMS, TodoState.parse(payload).size)
    }
}
