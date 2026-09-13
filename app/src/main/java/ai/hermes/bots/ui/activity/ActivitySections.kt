package ai.hermes.bots.ui.activity

import ai.hermes.bots.data.CronJob
import ai.hermes.bots.data.NotificationEntry
import ai.hermes.bots.data.PendingApproval
import ai.hermes.bots.data.RosterEntry
import ai.hermes.bots.ui.util.Humanize
import java.util.Locale

/**
 * Pure section builders for the Activity screen (UI-SPEC.md §4.6). No Compose, no
 * repositories — repositories push raw data in, display-ready rows come out. Unit-tested
 * in ActivitySectionsTest.
 */
object ActivitySections {

  const val RECENT_LIMIT = 8

  /** One "Needs you" row: pending prompt plus the two verbatim quick actions. */
  data class NeedsYouRow(
    val pending: PendingApproval,
    val kindLabel: String,
    val quickActions: List<String>,
  )

  /** One "Upcoming" row: routine with humanized cadence and optional (never fake) next run. */
  data class UpcomingRow(
    val connectionId: String,
    val jobId: String,
    val name: String,
    val enabled: Boolean,
    val cadence: String,
    val nextRunAtMs: Long?,
  )

  /** "Needs you": newest first, quick actions = first two payload choices, verbatim. */
  fun needsYou(pending: List<PendingApproval>): List<NeedsYouRow> =
    pending
      .sortedByDescending { it.atMs }
      .map { row ->
        NeedsYouRow(
          pending = row,
          kindLabel = kindLabel(row.card.kind),
          quickActions = row.card.choices.take(2),
        )
      }

  /** Kind overline: "approval.request" → "Approval", "clarify.request" → "Question". */
  fun kindLabel(kind: String): String =
    when (val base = kind.substringBefore('.').trim().lowercase(Locale.ROOT)) {
      "approval" -> "Approval"
      "clarify" -> "Question"
      "sudo" -> "Sudo"
      "secret" -> "Secret"
      else -> base.replaceFirstChar { it.uppercaseChar() }.ifEmpty { "Prompt" }
    }

  /** "Live now": visible roster rows active within the window, most recent activity first. */
  fun liveNow(entries: List<RosterEntry>): List<RosterEntry> =
    entries
      .filter { it.activeNow && !it.bot.hidden }
      .sortedByDescending { it.bot.lastActiveMs ?: it.bot.workerActiveMs ?: 0L }

  /**
   * "Upcoming": routines across ALL connections. Enabled jobs with a known next run sort
   * first (ascending); enabled jobs with absent/invalid next_run_at follow them (never
   * rendered with a fabricated time); paused jobs group last. Ties break by name.
   */
  fun upcoming(jobsByConnection: Map<String, List<CronJob>>): List<UpcomingRow> =
    jobsByConnection
      .flatMap { (connectionId, jobs) ->
        jobs.map { job ->
          UpcomingRow(
            connectionId = connectionId,
            jobId = job.id,
            name = job.name,
            enabled = job.enabled,
            cadence = Humanize.cron(job.scheduleText),
            nextRunAtMs = job.nextRunAtMs,
          )
        }
      }
      .sortedWith(
        compareBy(
          { !it.enabled },
          { it.nextRunAtMs == null },
          { it.nextRunAtMs ?: Long.MAX_VALUE },
          { it.name.lowercase(Locale.ROOT) },
        ),
      )

  /** "Recent": the newest [limit] history entries, newest first. */
  fun recent(history: List<NotificationEntry>, limit: Int = RECENT_LIMIT): List<NotificationEntry> =
    history.sortedByDescending { it.atMs }.take(limit)

  /** Relative age for needs-you rows: "just now", "5m ago", "2h ago", "3d ago". */
  fun age(atMs: Long, nowMs: Long): String {
    val delta = (nowMs - atMs).coerceAtLeast(0)
    return when {
      delta < 60_000L -> "just now"
      delta < 3_600_000L -> "${delta / 60_000L}m ago"
      delta < 86_400_000L -> "${delta / 3_600_000L}h ago"
      else -> "${delta / 86_400_000L}d ago"
    }
  }

  /** Future-tense label for a routine's next run; null (never fake) when unknown. */
  fun nextRunLabel(nextRunAtMs: Long?, nowMs: Long): String? {
    if (nextRunAtMs == null) return null
    val delta = nextRunAtMs - nowMs
    return when {
      delta <= 0 -> "due now"
      delta < 60_000L -> "in under a minute"
      delta < 3_600_000L -> "in ${delta / 60_000L}m"
      delta < 86_400_000L -> "in ${delta / 3_600_000L}h"
      else -> "in ${delta / 86_400_000L}d"
    }
  }
}
