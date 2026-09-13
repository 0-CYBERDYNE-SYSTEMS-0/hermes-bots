package ai.hermes.bots.ui.chat

import ai.hermes.bots.data.ChatItem
import ai.hermes.bots.data.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleModeTest {

  private fun user(text: String = "hi") = ChatItem("u1", ItemKind.USER, text)
  private fun bot(id: String = "a1", text: String = "hello") = ChatItem(id, ItemKind.ASSISTANT, text)
  private fun tool(id: String, name: String = "terminal") = ChatItem(id, ItemKind.TOOL, "", toolName = name)
  private fun error(text: String = "boom") = ChatItem("e1", ItemKind.ERROR, text)

  @Test
  fun `empty list builds no rows`() {
    assertTrue(buildBubbleRows(emptyList()).isEmpty())
  }

  @Test
  fun `user and bot items map to their bubbles`() {
    val rows = buildBubbleRows(listOf(user(), bot()))
    assertEquals(listOf(BubbleRow.User::class, BubbleRow.Bot::class), rows.map { it::class })
  }

  @Test
  fun `contiguous tool runs collapse into one Work row`() {
    val rows = buildBubbleRows(listOf(tool("t1"), tool("t2"), tool("t3")))
    assertEquals(1, rows.size)
    val work = rows.single() as BubbleRow.Work
    assertEquals(listOf("t1", "t2", "t3"), work.items.map { it.id })
  }

  @Test
  fun `single tool run still collapses`() {
    val rows = buildBubbleRows(listOf(bot(text = "working…"), tool("t1"), bot("a2", "done")))
    assertEquals(3, rows.size)
    val work = rows[1] as BubbleRow.Work
    assertEquals(listOf("t1"), work.items.map { it.id })
  }

  @Test
  fun `tool runs at start and end collapse separately`() {
    val rows = buildBubbleRows(listOf(tool("t1"), bot(text = "mid"), tool("t2")))
    assertEquals(3, rows.size)
    assertTrue(rows[0] is BubbleRow.Work)
    assertTrue(rows[1] is BubbleRow.Bot)
    assertTrue(rows[2] is BubbleRow.Work)
  }

  @Test
  fun `non-adjacent tool runs stay separate`() {
    val rows = buildBubbleRows(listOf(tool("t1"), user(), tool("t2")))
    assertEquals(3, rows.size)
    assertTrue(rows[0] is BubbleRow.Work)
    assertTrue(rows[1] is BubbleRow.User)
    assertTrue(rows[2] is BubbleRow.Work)
  }

  @Test
  fun `relay messages classify as Always`() {
    val rows = buildBubbleRows(
      listOf(bot("a1", "Message from @scout: onboarded"), bot("a2", "Reply from @recon: done")),
    )
    assertTrue(rows.all { it is BubbleRow.Always })
  }

  @Test
  fun `upstream emoji relay format classifies as Always`() {
    // Current inject shape from tools/bot_mode_dm.py: "Message from 🤖 Scout (@scout): …"
    val rows = buildBubbleRows(
      listOf(
        bot("a1", "Message from 🤖 Scout (@scout): onboarded"),
        bot("a2", "Reply from 🤖 Recon (@recon): done"),
      ),
    )
    assertTrue(rows.all { it is BubbleRow.Always })
  }

  @Test
  fun `relay prefixes match after trimming`() {
    val rows = buildBubbleRows(listOf(bot("a1", "   Reply from @scout: ping")))
    assertTrue(rows.single() is BubbleRow.Always)
  }

  @Test
  fun `plain assistant lines mentioning at-sign stay Bot`() {
    val rows = buildBubbleRows(listOf(bot("a1", "ping @scout for me")))
    assertTrue(rows.single() is BubbleRow.Bot)
  }

  @Test
  fun `errors classify as Always`() {
    assertTrue(buildBubbleRows(listOf(error())).single() is BubbleRow.Always)
  }

  @Test
  fun `order is preserved end to end`() {
    val items = listOf(
      user("go"),
      tool("t1"),
      tool("t2"),
      bot("a1", "Message from @default: relayed"),
      bot("a2", "found it"),
      error("expired"),
    )
    val rows = buildBubbleRows(items)
    assertEquals(
      listOf(
        BubbleRow.User::class,
        BubbleRow.Work::class,
        BubbleRow.Always::class,
        BubbleRow.Bot::class,
        BubbleRow.Always::class,
      ),
      rows.map { it::class },
    )
    assertEquals(listOf("t1", "t2"), (rows[1] as BubbleRow.Work).items.map { it.id })
    assertEquals("found it", (rows[3] as BubbleRow.Bot).item.text)
  }
}
