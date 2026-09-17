package ai.hermes.bots.ui.util

/**
 * Display-only copy helpers (audits A2/A28). Pure Kotlin, no protocol/data-layer
 * coupling: raw values stay untouched in the data layers; these only shape what
 * the user reads.
 */
object Humanize {

  private val known = mapOf(
    "gpt" to "GPT",
    "glm" to "GLM",
    "llama" to "Llama",
    "claude" to "Claude",
    "gemini" to "Gemini",
    "deepseek" to "DeepSeek",
    "qwen" to "Qwen",
    "mistral" to "Mistral",
    "kimi" to "Kimi",
    "minimax" to "MiniMax",
    "grok" to "Grok",
    "haiku" to "Haiku",
    "sonnet" to "Sonnet",
    "opus" to "Opus",
    "flash" to "Flash",
    "pro" to "Pro",
    "mini" to "Mini",
    "nano" to "Nano",
    "lite" to "Lite",
    "pass" to "Pass",
  )

  /** "deepseek-v4-flash" → "DeepSeek V4 Flash"; "provider/model" humanizes the model part. */
  fun model(slug: String?): String? {
    if (slug.isNullOrBlank()) return null
    val modelPart = slug.trim().substringAfterLast('/')
    val parts = modelPart.split('-', '_').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    return parts.joinToString(" ") { token ->
      known[token.lowercase()] ?: capToken(token)
    }
  }

  private fun capToken(token: String): String {
    val first = token.first()
    if (!first.isLetter()) return token
    return token.replaceFirstChar { c -> c.uppercaseChar() }
  }

  /**
   * Q8 (QA 2026-09-14): snake_case tool names → humane chip labels. Map covers the tools
   * this fleet actually runs (the schema "name" fields in hermes-agent's tools modules plus
   * the injected message_agent, tools/bot_mode_dm.py:38); unknown slugs fall back to
   * space-separated Title Case. The RAW name stays in the chip's expandable detail — this
   * only shapes the title/"Running …" line.
   */
  fun toolLabel(raw: String?): String {
    val slug = raw?.trim().orEmpty()
    if (slug.isEmpty()) return "Tool"
    toolNames[slug.lowercase()]?.let { return it }
    return slug.split('_', '-', '.')
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .joinToString(" ") { token -> known[token.lowercase()] ?: capToken(token) }
      .ifEmpty { "Tool" }
  }

  private val toolNames = mapOf(
    "terminal" to "Terminal",
    "read_terminal" to "Read terminal",
    "close_terminal" to "Close terminal",
    "read_file" to "Read file",
    "write_file" to "Write file",
    "patch" to "Patch",
    "search_files" to "Search files",
    "web_search" to "Web search",
    "web_extract" to "Web extract",
    "x_search" to "X search",
    "message_agent" to "Message agent",
    "send_message" to "Send message",
    "react_to_message" to "React to message",
    "delegate_task" to "Delegate task",
    "image_generate" to "Generate image",
    "vision_analyze" to "Analyze image",
    "video_analyze" to "Analyze video",
    "video_generate" to "Generate video",
    "text_to_speech" to "Text to speech",
    "memory" to "Memory",
    "todo_list" to "To-do list",
    "session_search" to "Session search",
    "execute_code" to "Run code",
    "clarify" to "Clarify",
    "cronjob_manage" to "Manage routines",
    "skills_list" to "List skills",
    "skill_view" to "View skill",
    "skill_manage" to "Manage skills",
    "browser_navigate" to "Open page",
    "browser_click" to "Click page",
    "browser_type" to "Type on page",
    "browser_snapshot" to "Read page",
    "ha_call_service" to "Smart home command",
    "ha_get_state" to "Smart home status",
    "ha_list_entities" to "List smart home devices",
    "ha_list_services" to "List smart home actions",
  )

  /**
   * Maps known gateway/session error strings to humane first lines (A28); returns
   * null when there is no known mapping — the caller may then show the raw text.
   *
   * R11 (MODEL-UX-PUNCHLIST.md rev 2): profile-name / duplicate / provider / session-cap
   * mappings for the bot editor come FIRST so the generic branches below never swallow
   * them (e.g. "4064" contains no "400", but an "internal error" payload could contain
   * anything).
   *
   * Q9, rev 2 (QA red-team 2026-09-14): the humane-banner pass-through matches ONLY the
   * app-authored banners below (commit 2d8c5df) — a bare startsWith("couldn't") let
   * server error strings like "couldn't reach upstream: …" bypass every mapping and
   * render raw at non-ErrorLine sites.
   */
  fun friendlyError(raw: String?, botName: String): String? {
    if (raw.isNullOrBlank()) return null
    val lower = raw.lowercase()
    if (appBanners.any { lower == it || lower.startsWith(it) }) return raw
    return when {
      "invalid profile name" in lower ->
        "Bot names can use lowercase letters, numbers, dashes and underscores."
      "is reserved" in lower ->
        "That name is reserved by the gateway — pick another."
      "already exists" in lower ->
        "A bot with that name already exists — open it from the roster."
      "not found" in lower && "profile" in lower ->
        "That bot is gone — refresh the roster."
      "unknown provider" in lower ->
        "This gateway doesn't have that provider."
      "active session" in lower || "4090" in lower ->
        "The gateway is at its live-session cap — try again shortly."
      "internal error" in lower ->
        "The gateway hit an internal error — try again."
      "valid model" in lower || "invalid model" in lower || "model_id" in lower ->
        "That model ID was rejected — check $botName's model in Edit bot."
      "host_mismatch" in lower || "4403" in lower ->
        "Wrong address — this gateway only accepts connections to its own host name."
      "401" in lower || "unauthorized" in lower || "auth_required" in lower || "forbidden" in lower ->
        "Sign-in failed — check the session token in Gateways."
      "timeout" in lower || "timed out" in lower ->
        "The gateway took too long to answer — try again."
      "connection" in lower && ("refus" in lower || "reset" in lower || "dropped" in lower || "closed" in lower) ->
        "The gateway dropped the connection — retrying."
      // Q3 (QA 2026-09-14): prompt-send rejections lead humane; the raw code+message stays
      // available behind the error's "Details" affordance. Must precede the greedy "400" match.
      "submit failed" in lower ->
        "Couldn't send that to $botName — the gateway rejected the request."
      "http 400" in lower || "400" in lower ->
        "The gateway rejected that request."
      "failed to open chat" in lower ->
        "Couldn't reach the gateway — check Gateways and try again."
      else -> null
    }
  }

  /** App-authored banner strings (commit 2d8c5df) that pass through friendlyError untouched. */
  private val appBanners = listOf(
    "couldn't load the conversation.",
    "couldn't start a fresh chat — try again.",
    "couldn't send that response.",
    // Incident 2026-09-16 client-authored lines (ChatViewModel) — already humane, and the
    // stall notice must show verbatim next to its Interrupt action, never as a generic error.
    "turn seems stuck",
    "steer didn't reach ",
    // Zombie-session self-heal (live dogfood 2026-09-16, rpc 4001): the VM re-opened the
    // session; the line asks for an honest resend, not a generic "something went wrong".
    "chat reconnected",
  )

  /**
   * Cron → human cadence for the common shapes (audit A21); unrecognized
   * expressions (and the server's "every 30m" style) pass through untouched.
   */
  fun cron(expr: String): String {
    val clean = expr.trim()
    val parts = clean.split(Regex("\\s+"))
    if (parts.size == 5) {
      val min = parts[0]
      val hour = parts[1]
      val dom = parts[2]
      val mon = parts[3]
      val dow = parts[4]
      val minI = min.toIntOrNull()
      val hourI = hour.toIntOrNull()
      if (dom == "*" && mon == "*" && dow == "*") {
        if (minI != null && hourI != null) return "Every day at ${timeLabel(hourI, minI)}"
        if (minI != null && hour == "*") return "Hourly at :${min.padStart(2, '0')}"
        if (hour == "*" && min.startsWith("*/") && min.drop(2).toIntOrNull() != null) {
          return "Every ${min.drop(2)} min"
        }
      }
      if (dom == "*" && mon == "*" && dow == "1-5" && minI != null && hourI != null) {
        return "Weekdays at ${timeLabel(hourI, minI)}"
      }
      if (dom == "*" && mon == "*" && minI != null && hourI != null && dow.toIntOrNull() != null) {
        dayName(dow.toInt())?.let { d -> return "Every $d at ${timeLabel(hourI, minI)}" }
      }
    }
    if (clean.startsWith("every", ignoreCase = true)) return clean
    return clean
  }

  private fun timeLabel(hour: Int, minute: Int): String {
    if (hour !in 0..23 || minute !in 0..59) return "$hour:$minute"
    val ampm = if (hour < 12) "AM" else "PM"
    val h12 = if (hour % 12 == 0) 12 else hour % 12
    return if (minute == 0) "$h12 $ampm" else "$h12:${minute.toString().padStart(2, '0')} $ampm"
  }

  private fun dayName(dow: Int): String? = when (dow) {
    0, 7 -> "Sunday"
    1 -> "Monday"
    2 -> "Tuesday"
    3 -> "Wednesday"
    4 -> "Thursday"
    5 -> "Friday"
    6 -> "Saturday"
    else -> null
  }
}
