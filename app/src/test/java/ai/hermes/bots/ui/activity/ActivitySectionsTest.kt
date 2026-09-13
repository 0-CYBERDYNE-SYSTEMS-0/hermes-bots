package ai.hermes.bots.ui.activity

import ai.hermes.bots.data.ApprovalCard
import ai.hermes.bots.data.BotRow
import ai.hermes.bots.data.CronJob
import ai.hermes.bots.data.CronRepository
import ai.hermes.bots.data.NotificationEntry
import ai.hermes.bots.data.PendingApproval
import ai.hermes.bots.data.RosterEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure section building/sorting for the Activity screen (UI-SPEC.md §4.6). */
class ActivitySectionsTest {

  private fun pending(
    requestId: String,
    atMs: Long,
    kind: String = "approval.request",
    command: String = "rm -rf /tmp/x",
    choices: List<String> = listOf("once", "session", "always", "deny"),
  ) = PendingApproval(
    connectionId = "c1",
    sessionId = "s-$requestId",
    botName = "scout",
    card = ApprovalCard(requestId, kind, command, choices),
    atMs = atMs,
  )

  private fun bot(
    connectionId: String = "c1",
    name: String = "scout",
    lastActiveMs: Long? = null,
    workerActiveMs: Long? = null,
    hidden: Boolean = false,
  ) = BotRow(
    connectionId = connectionId,
    name = name,
    displayName = null,
    description = null,
    model = null,
    provider = null,
    skillCount = 0,
    isDefault = false,
    hasAvatar = false,
    sectionId = null,
    hidden = hidden,
    lastPreview = null,
    lastActiveMs = lastActiveMs,
    workerActiveMs = workerActiveMs,
    canonicalSessionId = null,
    canonicalRootTitle = null,
    uiMetaRevisions = emptyMap(),
  )

  private fun job(
    id: String,
    name: String,
    enabled: Boolean = true,
    schedule: String = "0 9 * * *",
    nextRunAtMs: Long? = null,
    connectionId: String = "c1",
  ) = CronJob(
    connectionId = connectionId,
    id = id,
    name = name,
    enabled = enabled,
    scheduleText = schedule,
    prompt = "do things",
    nextRunAtMs = nextRunAtMs,
  )

  @Test
  fun `needs-you sorts newest first with kind labels and two verbatim actions`() {
    val rows = ActivitySections.needsYou(
      listOf(
        pending("r1", 1_000L),
        pending("r2", 3_000L, kind = "clarify.request"),
        pending("r3", 2_000L, kind = "sudo.request"),
      ),
    )
    assertEquals(listOf("r2", "r3", "r1"), rows.map { it.pending.card.requestId })
    assertEquals("Approval", rows[2].kindLabel)
    assertEquals("Question", rows[0].kindLabel)
    assertEquals("Sudo", rows[1].kindLabel)
    assertEquals(listOf("once", "session"), rows[0].quickActions)
  }

  @Test
  fun `kind labels cover secret and pass unknown kinds through`() {
    assertEquals("Secret", ActivitySections.kindLabel("secret.request"))
    assertEquals("Approval", ActivitySections.kindLabel("approval.request"))
    assertEquals("Mystery", ActivitySections.kindLabel("mystery.request"))
  }

  @Test
  fun `expired rows still render in needs-you`() {
    val row = pending("r1", 1_000L).copy(expired = true)
    val rows = ActivitySections.needsYou(listOf(row))
    assertEquals(1, rows.size)
    assertTrue(rows.single().pending.expired)
  }

  @Test
  fun `live-now filters hidden and inactive and sorts by latest activity`() {
    val entries = listOf(
      RosterEntry(bot(name = "idle", lastActiveMs = null), unread = false, activeNow = false),
      RosterEntry(bot(name = "old", lastActiveMs = 100L), unread = false, activeNow = true),
      RosterEntry(bot(name = "fresh", lastActiveMs = 300L), unread = false, activeNow = true),
      RosterEntry(
        bot(name = "worker", lastActiveMs = null, workerActiveMs = 200L),
        unread = false,
        activeNow = true,
      ),
      RosterEntry(bot(name = "shy", lastActiveMs = 400L, hidden = true), unread = false, activeNow = true),
    )
    val names = ActivitySections.liveNow(entries).map { it.bot.name }
    assertEquals(listOf("fresh", "worker", "old"), names)
  }

  @Test
  fun `upcoming sorts enabled-with-next-run first then absent then paused last`() {
    val jobs = mapOf(
      "c1" to listOf(
        job("paused-soon", "Paused with run", enabled = false, nextRunAtMs = 500L),
        job("j3", "C absent", nextRunAtMs = null),
        job("j2", "B later", nextRunAtMs = 2_000L),
        job("j1", "A sooner", nextRunAtMs = 1_000L),
        job("paused-blank", "Paused absent", enabled = false, nextRunAtMs = null),
      ),
      "c2" to listOf(job("j4", "D other gateway", nextRunAtMs = 1_500L, connectionId = "c2")),
    )
    val rows = ActivitySections.upcoming(jobs)
    assertEquals(
      listOf("j1", "j4", "j2", "j3", "paused-soon", "paused-blank"),
      rows.map { it.jobId },
    )
    // Humanized cadence rides the row ("0 9 * * *" → "Every day at 9 AM").
    assertEquals("Every day at 9 AM", rows.first().cadence)
  }

  @Test
  fun `upcoming keeps absent next-run rows but never fabricates a time`() {
    val rows = ActivitySections.upcoming(mapOf("c1" to listOf(job("j1", "Blank"))))
    assertEquals(1, rows.size)
    assertNull(rows.single().nextRunAtMs)
    assertNull(ActivitySections.nextRunLabel(rows.single().nextRunAtMs, nowMs = 10_000L))
  }

  @Test
  fun `recent caps at limit newest first`() {
    val history = (1L..12L).map { NotificationEntry("bot", "p$it", "s$it", atMs = it * 1_000L) }
    val rows = ActivitySections.recent(history)
    assertEquals(ActivitySections.RECENT_LIMIT, rows.size)
    assertEquals(12_000L, rows.first().atMs)
    assertEquals(5_000L, rows.last().atMs)
    assertEquals(listOf("p12", "p11", "p10"), rows.take(3).map { it.preview })
  }

  @Test
  fun `age and next-run labels stay humane`() {
    val now = 10_000_000L
    assertEquals("just now", ActivitySections.age(now - 30_000L, now))
    assertEquals("5m ago", ActivitySections.age(now - 5 * 60_000L, now))
    assertEquals("2h ago", ActivitySections.age(now - 2 * 3_600_000L, now))
    assertEquals("3d ago", ActivitySections.age(now - 3 * 86_400_000L, now))
    assertNull(ActivitySections.nextRunLabel(null, now))
    assertEquals("due now", ActivitySections.nextRunLabel(now - 1L, now))
    assertEquals("in under a minute", ActivitySections.nextRunLabel(now + 30_000L, now))
    assertEquals("in 5m", ActivitySections.nextRunLabel(now + 5 * 60_000L, now))
    assertEquals("in 2h", ActivitySections.nextRunLabel(now + 2 * 3_600_000L, now))
    assertEquals("in 3d", ActivitySections.nextRunLabel(now + 3 * 86_400_000L, now))
  }

  // -- CronRepository run-stamp parsing feeds the Upcoming sort; verified here. --

  private val parser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

  private fun parseJson(json: String): CronJob? =
    CronRepository.parseJob("c1", parser.parseToJsonElement(json))

  @Test
  fun `cron run stamps parse offset-aware iso strings`() {
    val next = java.time.Instant.parse("2026-09-12T22:04:05.123456Z").toEpochMilli()
    val parsed = parseJson(
      """{"id":"j1","name":"n","enabled":true,"schedule":"0 9 * * *","prompt":"",
         "next_run_at":"2026-09-12T15:04:05.123456-07:00","last_run_at":"2026-09-11T15:04:05+02:00"}""",
    )!!
    assertEquals(next, parsed.nextRunAtMs)
    assertEquals(
      java.time.Instant.parse("2026-09-11T13:04:05Z").toEpochMilli(),
      parsed.lastRunAtMs,
    )
  }

  @Test
  fun `cron run stamps tolerate absent null blank and garbage`() {
    val absent = parseJson("""{"id":"j1","schedule":"0 9 * * *"}""")!!
    assertNull(absent.nextRunAtMs)
    assertNull(absent.lastRunAtMs)
    val broken = parseJson(
      """{"id":"j1","schedule":"0 9 * * *","next_run_at":"","last_run_at":null}""",
    )!!
    assertNull(broken.nextRunAtMs)
    assertNull(broken.lastRunAtMs)
    val garbage = parseJson(
      """{"id":"j1","schedule":"0 9 * * *","next_run_at":"tomorrow-ish"}""",
    )!!
    assertNull(garbage.nextRunAtMs)
  }
}
