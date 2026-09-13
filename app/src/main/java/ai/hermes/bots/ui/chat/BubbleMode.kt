package ai.hermes.bots.ui.chat

import ai.hermes.bots.data.ChatItem
import ai.hermes.bots.data.ItemKind

/**
 * Bubble Mode row model (UI-SPEC.md §4.2 rev): the raw chat items regrouped into
 * iMessage-style renderable rows. Time separators stay a concern of the screen.
 *
 * - User turns → [BubbleRow.User] (right-aligned powder-blue bubble).
 * - Bot turns → [BubbleRow.Bot] (left-aligned bubble with the per-bot accent edge).
 * - Contiguous runs of tool activity, of ANY length (including 1) → [BubbleRow.Work],
 *   one collapsible "Show work · N steps" chip.
 * - Errors and bot-to-bot relay lines → [BubbleRow.Always] (visible in both modes).
 */
sealed interface BubbleRow {
  data class User(val item: ChatItem) : BubbleRow
  data class Bot(val item: ChatItem) : BubbleRow
  data class Work(val items: List<ChatItem>) : BubbleRow
  data class Always(val item: ChatItem) : BubbleRow
}

/**
 * Relay lines a bot's canonical session receives on behalf of another bot. Current upstream
 * (tools/bot_mode_dm.py) injects "Message from 🤖 name (@handle): …"; older builds used the
 * bare "Message from @handle:" form — the desktop reference accepts both, so match on the
 * prefix alone and let the emoji/display-name/handle follow (D3).
 */
const val RELAY_MESSAGE_PREFIX = "Message from"
const val RELAY_REPLY_PREFIX = "Reply from"

fun isRelayLine(text: String): Boolean {
  val trimmed = text.trim()
  return trimmed.startsWith(RELAY_MESSAGE_PREFIX) || trimmed.startsWith(RELAY_REPLY_PREFIX)
}

/**
 * Groups chat items into Bubble Mode rows, preserving order. Pure Kotlin — no Android
 * imports — so the grouping rules are unit-testable on the JVM.
 */
fun buildBubbleRows(items: List<ChatItem>): List<BubbleRow> {
  val rows = mutableListOf<BubbleRow>()
  var index = 0
  while (index < items.size) {
    val item = items[index]
    when {
      item.kind == ItemKind.TOOL -> {
        var end = index
        while (end < items.size && items[end].kind == ItemKind.TOOL) end++
        rows += BubbleRow.Work(items.subList(index, end).toList())
        index = end
      }
      item.kind == ItemKind.USER -> {
        rows += BubbleRow.User(item)
        index++
      }
      item.kind == ItemKind.ERROR || isRelayLine(item.text) -> {
        rows += BubbleRow.Always(item)
        index++
      }
      else -> {
        rows += BubbleRow.Bot(item)
        index++
      }
    }
  }
  return rows
}
