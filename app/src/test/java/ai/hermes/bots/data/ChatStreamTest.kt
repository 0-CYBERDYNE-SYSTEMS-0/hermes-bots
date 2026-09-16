package ai.hermes.bots.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA 2026-09-14: Q2 interim-seal semantics (desktop parity, prompt_turn.py:526-533),
 * Q6 resumed-session history row, Q12 tool failure from the wire, Q13 image bubble form.
 */
class ChatStreamTest {
  private var counter = 0
  private fun nextId(): String = "a-${++counter}"

  private fun anchor(text: String): List<ChatItem> =
      listOf(ChatItem("a-0", ItemKind.ASSISTANT, text, streaming = true))

  // ── Q2: sealInterim ────────────────────────────────────────────────────────

  @Test
  fun `interim seal keeps streamed text as its own segment and opens a fresh anchor`() {
    val out = ChatStream.sealInterim(anchor("```sh\ndocker ps"), "commentary", alreadyStreamed = false, ::nextId)
    assertEquals(3, out.size)
    assertFalse(out[0].streaming)
    assertEquals("```sh\ndocker ps", out[0].text) // streamed content survives intact
    assertFalse(out[1].streaming)
    assertEquals("commentary", out[1].text) // unstreamed interim: its own sealed segment
    assertTrue(out[2].streaming)
    assertEquals("", out[2].text) // fresh anchor for subsequent deltas
  }

  @Test
  fun `interim with already_streamed never duplicates the streamed text`() {
    val streamed = "partial answer so far"
    val out = ChatStream.sealInterim(anchor(streamed), streamed, alreadyStreamed = true, ::nextId)
    val texts = out.filter { it.kind == ItemKind.ASSISTANT }.map { it.text }
    assertEquals(2, texts.size) // sealed segment + fresh blank anchor, no third copy
    assertEquals(1, texts.count { it == streamed })
    assertTrue(out.last().streaming)
  }

  @Test
  fun `blank interim seals nothing`() {
    assertEquals(anchor("kept"), ChatStream.sealInterim(anchor("kept"), "", alreadyStreamed = false, ::nextId))
    assertEquals(anchor("kept"), ChatStream.sealInterim(anchor("kept"), "   ", alreadyStreamed = true, ::nextId))
  }

  @Test
  fun `interim with no live anchor still shows unstreamed text`() {
    // No fresh anchor here: a stray blank streaming anchor would linger as a phantom
    // typing indicator; appendDelta re-creates an anchor if deltas follow.
    val out = ChatStream.sealInterim(emptyList(), "lost commentary", alreadyStreamed = false, ::nextId)
    assertEquals(1, out.size)
    assertEquals("lost commentary", out[0].text)
    assertFalse(out[0].streaming)
  }

  // ── Q2: completeAnchor ─────────────────────────────────────────────────────

  @Test
  fun `complete replaces only the newest anchor and leaves sealed segments untouched`() {
    var items = ChatStream.sealInterim(anchor("```sh\ndocker ps"), "same", alreadyStreamed = true, ::nextId)
    items = ChatStream.completeAnchor(items, "final answer", ::nextId)
    assertEquals(2, items.size)
    assertFalse(items[0].streaming)
    assertEquals("```sh\ndocker ps", items[0].text) // sealed streamed segment survives
    assertFalse(items[1].streaming)
    assertEquals("final answer", items[1].text) // only the fresh anchor was replaced
  }

  @Test
  fun `complete with blank text keeps already-rendered content`() {
    val out = ChatStream.completeAnchor(anchor("streamed body"), "", ::nextId)
    assertEquals(1, out.size)
    assertEquals("streamed body", out[0].text)
    assertFalse(out[0].streaming)
  }

  @Test
  fun `complete drops a never-rendered blank anchor`() {
    val out = ChatStream.completeAnchor(anchor(""), "", ::nextId)
    assertTrue(out.isEmpty()) // D4 phantom-pill gate
  }

  @Test
  fun `complete with no anchor appends the final text as a settled segment`() {
    val out = ChatStream.completeAnchor(emptyList(), "final", ::nextId)
    assertEquals(1, out.size)
    assertEquals("final", out[0].text)
    assertFalse(out[0].streaming)
    assertTrue(ChatStream.completeAnchor(emptyList(), "", ::nextId).isEmpty())
  }

  @Test
  fun `final superseding an already_streamed interim grows the sealed row instead of duplicating`() {
    // already_streamed: the server supersedes/extends the interim — the DB keeps ONE row,
    // so the live transcript must show one assistant bubble with the full final text.
    val streamed = "partial answer so far"
    var items = ChatStream.sealInterim(anchor(streamed), streamed, alreadyStreamed = true, ::nextId)
    val sealedId = items.first().id
    items = ChatStream.completeAnchor(items, "$streamed — and the rest of the answer", ::nextId)
    assertEquals(1, items.size)
    assertFalse(items[0].streaming)
    assertEquals("$streamed — and the rest of the answer", items[0].text)
    assertEquals(sealedId, items[0].id) // sealed row kept its id
  }

  @Test
  fun `unrelated final after a sealed interim keeps both rows`() {
    val streamed = "draft answer"
    var items = ChatStream.sealInterim(anchor(streamed), streamed, alreadyStreamed = true, ::nextId)
    items = ChatStream.completeAnchor(items, "a completely different reply", ::nextId)
    assertEquals(2, items.size)
    assertEquals("draft answer", items[0].text) // sealed segment untouched
    assertFalse(items[0].streaming)
    assertEquals("a completely different reply", items[1].text) // fresh anchor filled
    assertFalse(items[1].streaming)
  }

  @Test
  fun `final equal to the sealed interim stays one row`() {
    var items = ChatStream.sealInterim(anchor("same text"), "same text", alreadyStreamed = true, ::nextId)
    items = ChatStream.completeAnchor(items, "same text", ::nextId)
    assertEquals(1, items.size)
    assertEquals("same text", items[0].text)
    assertFalse(items[0].streaming)
  }

  // ── Q12: ToolResult ────────────────────────────────────────────────────────

  @Test
  fun `terminal non-zero exit marks failure`() {
    val payload = Json.parseToJsonElement("""{"exit_code": 1, "error": "boom"}""")
    assertTrue(ToolResult.isFailure("terminal", payload))
    assertFalse(ToolResult.isFailure("terminal", Json.parseToJsonElement("""{"exit_code": 0}""")))
  }

  @Test
  fun `json success false marks failure for non-terminal tools`() {
    val payload = Json.parseToJsonElement("""{"success": false, "error": "nope"}""")
    assertTrue(ToolResult.isFailure("read_file", payload))
    assertFalse(ToolResult.isFailure("read_file", Json.parseToJsonElement("""{"ok": true}""")))
  }

  @Test
  fun `string result payloads are inspected with the same heuristics`() {
    // The wire often carries result as a JSON-encoded STRING (tool_progress.py json.loads fallback).
    assertTrue(
      ToolResult.isFailure(
        "terminal",
        JsonPrimitive("""{"exit_code": 2}"""),
      ),
    )
    assertTrue(ToolResult.isFailure("web_fetch", JsonPrimitive("Error: timeout")))
    assertFalse(ToolResult.isFailure("read_file", JsonPrimitive("plain output")))
  }

  @Test
  fun `absent or ambiguous results never mark failure`() {
    assertFalse(ToolResult.isFailure("terminal", null))
    assertFalse(ToolResult.isFailure(null, Json.parseToJsonElement("""{"exit_code": 0}""")))
  }

  // ── Q6: resumed-session history row ────────────────────────────────────────

  @Test
  fun `resumed session row is rewritten without the raw session id`() {
    val raw = "↻ Resumed session 20260912_141500_a1b2 \"Bot Chat\" (6 user messages, 31 total messages)"
    assertEquals("↻ Resumed this conversation — 6 of your messages, 31 total.", HistoryDisplay.assistant(raw))
  }

  @Test
  fun `singular user message count parses too`() {
    val raw = "↻ Resumed session abc \"T\" (1 user message, 5 total messages)"
    assertEquals("↻ Resumed this conversation — 1 of your messages, 5 total.", HistoryDisplay.assistant(raw))
  }

  @Test
  fun `unparseable resumed row falls back to generic humane copy`() {
    assertEquals("↻ Resumed this conversation.", HistoryDisplay.assistant("↻ Resumed session weird-shape"))
    assertEquals("hello", HistoryDisplay.assistant("hello"))
  }

  // ── Q13: image attachment form unification ─────────────────────────────────

  @Test
  fun `history image attachment renders in the live bubble form`() {
    assertEquals("🖼 photo.jpg", HistoryDisplay.user("[User attached image: photo.jpg]"))
    assertEquals("look\n🖼 pic.png", HistoryDisplay.user("look\n[User attached image: pic.png]"))
    assertEquals("no attachment here", HistoryDisplay.user("no attachment here"))
  }

  // ── history parser wiring ──────────────────────────────────────────────────

  @Test
  fun `parser applies the humane rewrites to restored history`() {
    val messages = Json.parseToJsonElement(
        """[
          {"role":"assistant","text":"↻ Resumed session xyz \"Bot Chat\" (2 user messages, 4 total messages)"},
          {"role":"user","text":"[User attached image: cat.jpg]"}
        ]""",
    )
    val items = ChatMessagesParser.parse(messages.jsonArray)
    assertEquals("↻ Resumed this conversation — 2 of your messages, 4 total.", items[0].text)
    assertEquals("🖼 cat.jpg", items[1].text)
  }

  // ── Q7 follow-up (live QA 2026-09-14): non-verbose tool payloads ───────────

  @Test
  fun `displayText flattens terminal output from the result payload`() {
    val result = Json.parseToJsonElement(
        """{"output": "QA_FIX_PROBE_8\nDarwin localhost arm64", "exit_code": 0, "error": null}""",
    )
    assertEquals(
        "QA_FIX_PROBE_8\nDarwin localhost arm64",
        ToolResult.displayText(result),
    )
  }

  @Test
  fun `displayText accepts the known text keys and plain strings only`() {
    assertEquals(
        "some stdout",
        ToolResult.displayText(Json.parseToJsonElement("""{"stdout": "some stdout", "n": 3}""")),
    )
    assertEquals("plain output", ToolResult.displayText(JsonPrimitive("plain output")))
    // Unknown shapes stay hidden — no raw protocol dumped into the UI.
    assertEquals(null, ToolResult.displayText(Json.parseToJsonElement("""{"path": "/etc/hosts"}""")))
    assertEquals(null, ToolResult.displayText(null))
  }

  @Test
  fun `commandFromArgs reads terminal and code_execution commands`() {
    assertEquals(
        "echo hi",
        ToolResult.commandFromArgs("terminal", Json.parseToJsonElement("""{"command": "echo hi"}""")),
    )
    assertEquals(
        null,
        ToolResult.commandFromArgs("code_execution", Json.parseToJsonElement("""{"code": "1+1"}""")),
    )
    assertEquals(null, ToolResult.commandFromArgs("read_file", Json.parseToJsonElement("""{"path": "x"}""")))
    assertEquals(null, ToolResult.commandFromArgs("terminal", null))
  }

  @Test
  fun `final extending a NON-interim settled row never merges into it`() {
    // Red-team SF-3: only interim-sealed rows may grow — a previous turn's final
    // that happens to be a prefix of the next final must stay its own row.
    var items = listOf(ChatItem(nextId(), ItemKind.ASSISTANT, "Sure", streaming = false))
    items = ChatStream.sealInterim(items, "", alreadyStreamed = true, nextId = ::nextId)
    items = ChatStream.completeAnchor(items, "Sure thing", nextId = ::nextId)
    assertEquals(2, items.size)
    assertEquals("Sure", items[0].text)
    assertEquals("Sure thing", items[1].text)
  }

  @Test
  fun `displayText hides JsonNull values instead of rendering them`() {
    // Red-team SF-1: JsonNull IS a JsonPrimitive whose content is "null".
    val payload = Json.parseToJsonElement("""{"stdout": null, "output": "real output"}""")
    assertEquals("real output", ToolResult.displayText(payload))
    assertEquals(null, ToolResult.displayText(Json.parseToJsonElement("null")))
    assertEquals(null, ToolResult.displayText(Json.parseToJsonElement("""{"text": null}""")))
  }

  @Test
  fun `commandFromArgs reads the real execute_code tool`() {
    // Red-team SF-2: the tool is execute_code (code_execution_tool.py:862), not code_execution.
    assertEquals(
        "1+1",
        ToolResult.commandFromArgs("execute_code", Json.parseToJsonElement("""{"code": "1+1"}""")),
    )
  }
}
