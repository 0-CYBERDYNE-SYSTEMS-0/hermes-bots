package ai.hermes.bots.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** QA 2026-09-19: clarify payloads the wire actually carries — batch, text fallback, srq dialect. */
class ClarifyCardParseTest {

    private fun parse(json: String) = CanonicalChat.parseCard(
        "clarify.request",
        Json.parseToJsonElement(json).jsonObject,
    )

    @Test
    fun `legacy single clarify with question and choices`() {
        val card = parse("""{"request_id":"r1","question":"Go ahead?","choices":["yes","no"]}""")
        assertEquals("Go ahead?", card?.command)
        assertEquals(listOf("yes", "no"), card?.choices)
        assertNull(card?.serverRequestId)
        assertNull(card?.qid)
    }

    @Test
    fun `batch clarify surfaces the first question and its choices`() {
        val card = parse(
            """{"request_id":"srq-1","server_request":true,"questions":[""" +
                """{"qid":"q1","question":"Real question","choices":[],"multi_select":false}]}""",
        )
        assertEquals("Real question", card?.command)
        assertEquals(emptyList<String>(), card?.choices)
        assertEquals("srq-1", card?.serverRequestId)
        assertEquals("q1", card?.qid)
    }

    @Test
    fun `plain text payload is accepted as the ask`() {
        val card = parse("""{"request_id":"r2","text":"What port?"}""")
        assertEquals("What port?", card?.command)
    }

    @Test
    fun `no request id means no card`() {
        assertNull(parse("""{"question":"Q?"}"""))
    }
}
