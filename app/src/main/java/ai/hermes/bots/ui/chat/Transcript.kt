package ai.hermes.bots.ui.chat

import ai.hermes.bots.data.ChatItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One renderable transcript row: a chat item or a centered time separator (audit A5). */
sealed interface TranscriptRow {
  val key: String

  data class Message(val item: ChatItem, val atMs: Long?) : TranscriptRow {
    override val key: String = item.id
  }

  data class TimeSeparator(val atMs: Long, val label: String) : TranscriptRow {
    override val key: String = "sep-$atMs"
  }
}

/**
 * Builds the display transcript: inserts a centered separator between items whose
 * receive times gap > 20 min or cross a calendar day. Times are VM-local receive
 * stamps (presentation-only); history items without stamps never gain separators.
 */
object Transcript {
  const val GAP_MS: Long = 20L * 60_000L

  private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
  private val dayFmt = DateTimeFormatter.ofPattern("MMM d", Locale.US)

  fun build(
    items: List<ChatItem>,
    times: Map<String, Long>,
    zone: ZoneId = ZoneId.systemDefault(),
  ): List<TranscriptRow> {
    val rows = mutableListOf<TranscriptRow>()
    var prevMs: Long? = null
    items.forEach { item ->
      val at = times[item.id]
      if (at != null && needsSeparator(prevMs, at, zone)) {
        rows += TranscriptRow.TimeSeparator(at, label(at, zone))
      }
      rows += TranscriptRow.Message(item, at)
      if (at != null) prevMs = at
    }
    return rows
  }

  fun needsSeparator(prevMs: Long?, atMs: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    val prev = prevMs ?: return false
    if (atMs < prev) return false
    val prevDay = Instant.ofEpochMilli(prev).atZone(zone).toLocalDate()
    val atDay = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
    if (prevDay != atDay) return true
    return atMs - prev > GAP_MS
  }

  fun label(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val zdt = Instant.ofEpochMilli(atMs).atZone(zone)
    val today = LocalDate.now(zone)
    return when (zdt.toLocalDate()) {
      today -> "Today ${timeFmt.format(zdt)}"
      today.minusDays(1) -> "Yesterday ${timeFmt.format(zdt)}"
      else -> "${dayFmt.format(zdt)} ${timeFmt.format(zdt)}"
    }
  }
}
