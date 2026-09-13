package ai.hermes.bots.protocol

/**
 * Every wire string the app may send, plus timings/limits, from PROTOCOL.md.
 * Do not invent variants; add strings only with a PROTOCOL.md citation.
 */
object Catalog {
    // --- JSON-RPC method names (PROTOCOL.md §5) ---
    const val METHOD_EVENT = "event"
    const val METHOD_GATEWAY_PING = "gateway.ping"
    const val METHOD_PING = "ping"
    const val METHOD_PROMPT_SUBMIT = "prompt.submit"
    const val METHOD_PROMPT_BACKGROUND = "prompt.background"
    const val METHOD_PROMPT_BTW = "prompt.btw"
    const val METHOD_SESSION_INTERRUPT = "session.interrupt"
    const val METHOD_SESSION_STEER = "session.steer"
    const val METHOD_SESSION_REDIRECT = "session.redirect"
    const val METHOD_SESSION_CREATE = "session.create"
    const val METHOD_SESSION_LIST = "session.list"
    const val METHOD_SESSION_RESUME = "session.resume"
    const val METHOD_SESSION_CLOSE = "session.close"
    const val METHOD_SESSION_COMPRESS = "session.compress"
    const val METHOD_SESSION_TITLE = "session.title"
    const val METHOD_SESSION_DELETE = "session.delete"
    const val METHOD_SESSION_SET_HIDDEN = "session.set_hidden"
    const val METHOD_SESSION_ACTIVE_LIST = "session.active_list"
    const val METHOD_SESSION_MOST_RECENT = "session.most_recent"
    const val METHOD_SESSION_EVENTS_SINCE = "session.events.since"
    const val METHOD_PROFILES_LIST = "profiles.list"
    const val METHOD_PROFILES_CREATE = "profiles.create"
    const val METHOD_PROFILES_DESCRIBE = "profiles.describe"
    const val METHOD_PROFILES_CONFIGURE = "profiles.configure"
    const val METHOD_PROFILES_SET_ASSET = "profiles.set_asset"
    const val METHOD_PROFILES_GET_ASSET = "profiles.get_asset"
    const val METHOD_CONFIG_GET = "config.get"
    const val METHOD_CONFIG_SET = "config.set"
    const val METHOD_MODEL_OPTIONS = "model.options"
    const val METHOD_MODEL_SAVE_KEY = "model.save_key"
    const val METHOD_MODEL_DISCONNECT = "model.disconnect"
    const val METHOD_GATEWAY_CAPABILITIES = "gateway.capabilities"
    const val METHOD_APPROVAL_RESPOND = "approval.respond"
    const val METHOD_APPROVAL_PENDING = "approval.pending"
    const val METHOD_CLARIFY_RESPOND = "clarify.respond"
    const val METHOD_SECRET_RESPOND = "secret.respond"
    const val METHOD_GROUPS_CAPABILITIES = "groups.capabilities"
    const val METHOD_GROUPS_LIST = "groups.list"
    const val METHOD_GROUPS_CREATE = "groups.create"
    const val METHOD_GROUPS_STATE = "groups.state"
    const val METHOD_GROUPS_LOG = "groups.log"
    const val METHOD_GROUPS_SEND = "groups.send"
    const val METHOD_GROUPS_DISBAND = "groups.disband"
    const val METHOD_GROUPS_STOP = "groups.stop"
    const val METHOD_GROUPS_APPROVE = "groups.approve"
    const val METHOD_GROUPS_RETRY = "groups.retry"
    const val METHOD_BOT_RELAY_ROSTER_SYNC = "bot_relay.roster.sync"
    const val METHOD_BOT_RELAY_OUTBOX_DRAIN = "bot_relay.outbox.drain"
    const val METHOD_BOT_RELAY_DELIVER = "bot_relay.deliver"
    const val METHOD_BOT_RELAY_REPLY = "bot_relay.reply"
    const val METHOD_IMAGE_ATTACH = "image.attach"
    const val METHOD_IMAGE_ATTACH_BYTES = "image.attach_bytes"
    const val METHOD_IMAGE_GENERATE = "image.generate"
    const val METHOD_FILE_ATTACH = "file.attach"
    const val METHOD_CLI_EXEC = "cli.exec"
    const val METHOD_SHELL_EXEC = "shell.exec"
    const val METHOD_VOICE_RECORD = "voice.record"

    // --- Event types (params.type) (PROTOCOL.md §3, §5.9, §6) ---
    const val EVENT_GATEWAY_READY = "gateway.ready"
    const val EVENT_MESSAGE_START = "message.start"
    const val EVENT_MESSAGE_DELTA = "message.delta"
    const val EVENT_MESSAGE_INTERIM = "message.interim"
    const val EVENT_MESSAGE_COMPLETE = "message.complete"
    const val EVENT_REASONING_AVAILABLE = "reasoning.available"
    const val EVENT_THINKING_DELTA = "thinking.delta"
    const val EVENT_TOOL_START = "tool.start"
    const val EVENT_TOOL_COMPLETE = "tool.complete"
    const val EVENT_TOOL_GENERATING = "tool.generating"
    const val EVENT_TOOL_OUTPUT_RISK = "tool.output_risk"
    const val EVENT_TODO_UPDATED = "todo.updated"
    const val EVENT_STATUS_UPDATE = "status.update"
    const val EVENT_APPROVAL_REQUEST = "approval.request"
    const val EVENT_CLARIFY_REQUEST = "clarify.request"
    const val EVENT_SUDO_REQUEST = "sudo.request"
    const val EVENT_SECRET_REQUEST = "secret.request"
    // PROTOCOL.md §5.5: blocking-prompt expiry mirrors — `*.expire {request_id}`.
    const val EVENT_APPROVAL_EXPIRE = "approval.expire"
    const val EVENT_CLARIFY_EXPIRE = "clarify.expire"
    const val EVENT_SUDO_EXPIRE = "sudo.expire"
    const val EVENT_SECRET_EXPIRE = "secret.expire"
    const val EVENT_MCP_SETUP_REQUEST = "mcp.setup.request"
    const val EVENT_SESSION_TITLE = "session.title"
    const val EVENT_SESSION_INFO = "session.info"
    const val EVENT_SESSION_USAGE = "session.usage"
    const val EVENT_SESSION_RESUME_PROGRESS = "session.resume_progress"
    const val EVENT_SESSION_RECLAIMED = "session.reclaimed"
    const val EVENT_ERROR = "error"
    const val EVENT_NOTICE = "notice"
    const val EVENT_NOTIFICATION_SHOW = "notification.show"
    const val EVENT_NOTIFICATION_CLEAR = "notification.clear"
    const val EVENT_REACTION = "reaction"
    const val EVENT_MOA_PROGRESS = "moa.progress"
    const val EVENT_MOA_PHASE = "moa.phase"
    const val EVENT_MOA_AGGREGATING = "moa.aggregating"
    const val EVENT_SESSIONS_CHANGED = "sessions.changed"
    const val EVENT_CRON_CHANGED = "cron.changed"
    const val EVENT_PLATFORMS_CHANGED = "platforms.changed"
    const val EVENT_PAIRING_CHANGED = "pairing.changed"
    const val EVENT_PET_CHANGED = "pet.changed"
    const val EVENT_BOT_RELAY_OUTBOX_PENDING = "bot_relay.outbox.pending"

    // --- REST (PROTOCOL.md §7) ---
    const val REST_STATUS = "/api/status"
    const val REST_WS_TICKET = "/api/auth/ws-ticket"
    // Gated-mode session bootstrap (server: dashboard_auth/routes.py) — the ticket route is
    // cookie-gated, so BasicAuth connections log in first and ride the session cookie.
    const val REST_AUTH_PROVIDERS = "/api/auth/providers"
    const val REST_PASSWORD_LOGIN = "/auth/password-login"
    const val REST_CRON_JOBS = "/api/cron/jobs"
    const val REST_CRON_JOB = "/api/cron/jobs/%s"
    const val REST_MODEL_OPTIONS = "/api/model/options"
    const val REST_FILES_DOWNLOAD = "/api/files/download"
    const val HEADER_SESSION_TOKEN = "X-Hermes-Session-Token"

    // --- WS path + close codes (PROTOCOL.md §2) ---
    const val WS_PATH = "/api/ws"
    const val WS_CLOSE_BAD_CREDENTIAL = 4401
    const val WS_CLOSE_HOST_DISALLOWED = 4403
    const val WS_CLOSE_CHAT_DISABLED = 4404
    const val WS_CLOSE_NOT_LOOPBACK = 4408

    // --- Error codes (PROTOCOL.md §4, §5) ---
    const val ERR_PARSE = -32700
    const val ERR_INVALID_REQUEST = -32600
    const val ERR_INVALID_PARAMS = -32602
    const val ERR_METHOD_NOT_FOUND = -32601
    const val ERR_INTERNAL = -32603
    const val ERR_HANDLER = -32000
    const val ERR_SESSION_BUSY = 4091
    const val ERR_SESSION_CAP = 4090
    const val ERR_PROFILE_NAME_REQUIRED = 4061
    const val ERR_PROFILE_CREATE_FAILED = 4062
    const val ERR_PROFILE_ASSET_INVALID = 4066
    const val ERR_PROFILE_ASSET_TOO_LARGE = 4069
    const val ERR_PROFILE_ASSET_UNSUPPORTED = 4070
    const val ERR_PROFILE_CONFIGURE = 5064
    const val ERR_RELAY_1 = 5092
    const val ERR_RELAY_2 = 4092
    const val ERR_RELAY_TIMEOUT = 5093

    // --- Timings & limits (PROTOCOL.md §2/§3/§5; BOTS-MODE-PARITY.md) ---
    const val HEARTBEAT_INTERVAL_MS = 15_000L
    const val HEARTBEAT_TIMEOUT_MS = 45_000L
    const val BACKOFF_MIN_MS = 1_000L
    const val BACKOFF_MAX_MS = 30_000L
    const val WS_CONNECT_TIMEOUT_S = 10L
    const val TEST_GRACE_MS = 750L
    const val ROSTER_POLL_MS = 5_000L
    const val ACTIVE_NOW_WINDOW_MS = 90_000L
    const val RELAY_ROSTER_LOOP_MS = 60_000L
    const val RELAY_DRAIN_LOOP_MS = 30_000L
    // Server budget is ~1320 s (120 s lock-wait + 600 s turn × 2); PROTOCOL.md §5.7 requires the
    // client timeout to EXCEED it, so keep headroom above the server's worst case.
    const val RELAY_DELIVER_TIMEOUT_MS = 1_400_000L
    const val TICKET_TTL_S = 30
    const val UI_META_MAX_BYTES = 64 * 1024
    const val MAX_AVATAR_BYTES = 2 * 1024 * 1024
    const val AVATAR_MAX_EDGE_PX = 512

    // --- Bots-mode conventions (PROTOCOL.md §5.3; BOTS-MODE-PARITY.md §2) ---
    const val CANONICAL_CHAT_TITLE = "Bot Chat"
    const val UI_META_KEY = "hermes-bots"
    const val ASSET_AVATAR = "avatar"
}
