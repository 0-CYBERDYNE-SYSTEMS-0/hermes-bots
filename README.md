# Hermes Bots for Android

Native Android client (Kotlin + Jetpack Compose, package `ai.hermes.bots`) for
**[hermes-agent](https://github.com/NousResearch/hermes-agent)** gateways. It connects to any
number of `hermes serve` gateways (loopback, LAN, or Tailscale) and gives full **bots-mode
parity** from a phone — the same bots experience the desktop app has, as a pure client.

## Features (v1 scope)

- **Bot roster** — union roster across all connected gateways: avatars, sections, search,
  hidden bots, active-now strip, unread watermarks.
- **Canonical bot chats** — per-bot forever-chats with streaming markdown, tool-run chips,
  lettered approval/clarify cards, interrupt/steer, `/new` compression, reconnect catch-up
  with no lost messages.
- **Bot editor** — create/clone bots, pin model (`model.options`), edit SOUL.md, toggle
  skills, set section/hidden, upload an avatar (photo picker, downscaled JPEG data-URL).
- **Routines** — per-bot cron jobs via the gateway REST API (`/api/cron/jobs` CRUD,
  pause/resume, `cron.changed` wake).
- **Group chats** — multi-bot rooms with round-log discussion, member picker, stop/disband.
- **Multi-gateway** — add any number of gateways; one starred as primary.
- **Cross-connection A2A relay** — the app itself runs the `bot_relay` loops so bots on
  different gateways can message each other (`@handle` via the `message_agent` tool).
- **Local notifications** — post notifications on the device for bot activity.

**Deferred to v2** (per [DECISIONS.md](DECISIONS.md) #3): voice dictation, image generation,
PTY terminal views, SSH-tunnel/cloud connections, OAuth-portal auth, themes.

## Building

Prerequisites: **JDK 17** and **Android SDK 35** (platform `platforms;android-35`,
build-tools 35.0.0, platform-tools).

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17      # macOS/homebrew path; adjust as needed
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Runs on **Android 10 (API 29) or newer** (`minSdk 29`, target/compile SDK 35). The Gradle
wrapper (Gradle 8.9, AGP 8.7.3, Kotlin 2.0.21, Compose BOM) is canonical — use `./gradlew`,
not a system Gradle.

## Gateway provisioning

The app talks to a `hermes serve` HTTP+WS server (default port **9119**). Pick the recipe
that matches where the gateway runs. All recipes were verified end-to-end against a real
`hermes serve` v0.21.0.

### 1. Start the server with a fixed session token

```bash
export HERMES_DASHBOARD_SESSION_TOKEN=<your-token>
hermes serve --host 0.0.0.0 --port 9119
```

The session token comes from the `HERMES_DASHBOARD_SESSION_TOKEN` env var; if it is unset,
the server generates a fresh random token per start (`secrets.token_urlsafe(32)`), which you
would then have to re-enter in the app after every server restart — so set the env var for a
stable setup.

### 2. Auth mode: token vs basic

- **Loopback binds** (`127.0.0.1`, default) run in plain **token mode** — no auth provider
  needed, the `?token=` query parameter on the WS upgrade is enough.
- **Non-loopback binds** (`--host 0.0.0.0`, required for LAN/Tailscale access) **require an
  auth provider**. Use **basic auth (username & password)** — the server's interactive setup
  offers basic or OAuth; the app supports basic (OAuth is a v2 deferral).

The app supports **both** credential kinds (session token, or user+password) and detects the
gateway's mode itself via the public `GET /api/status` → `auth_required` field:

- Token mode: WS upgrade with `?token=<SESSION_TOKEN>`; REST calls send
  `X-Hermes-Session-Token: <token>`.
- Gated (basic) mode: `?token=` is rejected — the app mints a single-use WS ticket via
  `POST /api/auth/ws-ticket` with `Authorization: Basic ...` (30 s TTL) and upgrades with
  `?ticket=<ticket>`, re-minting on every reconnect.

### 3. Add the gateway in the app

1. **Gateways screen → “+”**.
2. Enter the base URL (`http(s)://host:port`; scheme-less `host:port` is accepted and
   defaults to `http://`, port 9119).
3. Choose **Token** or **User + password** and enter the credential.
4. Tap **Test** — this probes the HTTP leg (`/api/status`) and the WS leg
   (`gateway.ready` frame or socket open, 10 s timeout).
5. **Save**. Star (★) one gateway as **primary** (used for creating bots, groups, etc.).

### Recipe A — USB phone or emulator (development)

Run a **loopback-bound** serve on your dev machine, then reverse the port per device:

```bash
hermes serve --host 127.0.0.1 --port 9119
adb reverse tcp:9119 tcp:9119     # once per connected device/emulator
```

In the app add a gateway with base URL `http://127.0.0.1:9119` (token mode).

**Why `adb reverse`?** A loopback-bound `hermes serve` rejects upgrades whose HTTP `Host`
header is not loopback (WS close **4403 `host_mismatch`**). The emulator's `10.0.2.2` alias
therefore does **not** work; `adb reverse` makes `127.0.0.1:9119` on the device tunnel to the
Mac's loopback, so the Host header stays `127.0.0.1`. The same recipe works identically for a
USB phone. The token must match `HERMES_DASHBOARD_SESSION_TOKEN` of that serve process.

### Recipe B — LAN

```bash
export HERMES_DASHBOARD_SESSION_TOKEN=<your-token>
hermes serve --host 0.0.0.0 --port 9119     # choose basic auth (username + password) when prompted
```

In the app: base URL `http://<mac-ip>:9119`, **User + password** mode. The Mac's firewall may
prompt to allow inbound connections on 9119.

### Recipe C — Tailscale

1. Install Tailscale on the Mac and the phone; join both to the same tailnet.
2. Start the server as in Recipe B (`--host 0.0.0.0`, basic auth, or set
   `HERMES_DASHBOARD_SESSION_TOKEN` for token mode).
3. In the app use `https://<mac>.ts.net:9119` (or the Mac's tailnet IP,
   `http://100.x.y.z:9119`).

Auth notes: with basic auth configured, use **User + password** — the gateway rejects
`?token=` in gated mode. If the server runs token-only (no auth provider), use the token;
both work over Tailscale, but gated/basic is the setup the remote-bind server expects.

## Architecture notes

- The app is a **pure client + relay** — it never runs the agent itself; all LLM turns,
  tools, and cron executions happen on the gateway.
- One **WebSocket** per connection (`/api/ws`, JSON-RPC, one object per text frame). Client
  heartbeat `gateway.ping` every **15 s**; no ack within **45 s** → dead socket, force
  reconnect with exponential backoff **1 s → 30 s cap**. On reconnect, per-session catch-up
  via `session.events.since`; a changed `replay_epoch` or truncated replay falls back to a
  full `session.resume`. Canonical bot chats use `close_on_disconnect:false` so they survive
  mobile disconnects.
- **Union roster**: bots from all gateways are merged into one roster (name-qualified per
  connection); polled every 5 s with `sessions.changed` pushes as a demotion trigger.
- **Relay loops** (the app plays the role the desktop's relay plays): `bot_relay.roster.sync`
  every **60 s**; `bot_relay.outbox.drain` every **30 s** plus immediately on the
  `bot_relay.outbox.pending` push (debounced); `bot_relay.deliver` is blocking with a client
  timeout **> 1320 s** (120 s lock-wait + 600 s turn × 2 attempts); unknown-method
  (`-32601`) marks the gateway `relay_unsupported`.
- **Secrets** (tokens, passwords) live only in app-private **DataStore**; never logged,
  never committed.

## Troubleshooting

- **WS close `4403 host_mismatch`** — the server is loopback-bound and saw a non-loopback
  `Host` header. Either use the `adb reverse` recipe (Recipe A) with `http://127.0.0.1:9119`,
  or bind the server to `0.0.0.0` (Recipes B/C). Do not rewrite the Host header.
- **WS close `4401` (bad credential)** — wrong/expired token, or the server is in gated
  (basic-auth) mode where `?token=` is rejected. Re-check the credential in the Gateways
  screen; the **Test** probe re-runs `GET /api/status` mode detection.
- **Bot has no `message_agent` tool** (can't participate in relay) — a profile only becomes a
  relay-capable bot when it carries `ui_meta["hermes-bots"]`. Fix: edit the bot once in the
  app (change its section or color) and save; that writes the `ui_meta` key.
- **No notifications** — Android 13+ requires the runtime `POST_NOTIFICATIONS` permission;
  grant it when the app asks (or in system settings → Apps → Hermes Bots → Notifications).

## Repo layout

Single-module Gradle project: `app/src/main/java/ai/hermes/bots/` is split into `protocol/`
(WS/JSON-RPC/auth core), `data/` (repositories, DataStore), `ui/` (Compose screens:
connections, roster, chat, editor, routines, groups, theme), `notify/` (local notifications);
`scripts/` holds dev helpers (`env.sh`, `bootstrap-toolchain.sh`, `dev-gateways.sh`);
mission/planning docs live in the repo root.

- **[PROTOCOL.md](PROTOCOL.md)** is the wire contract — every RPC string the app speaks, with
  citations into the hermes-agent source. Do not invent variants.
- Screenshots: `docs/`.
- More docs: [AGENTS.md](AGENTS.md) (mission + machine map), [DECISIONS.md](DECISIONS.md)
  (locked choices), [BOTS-MODE-PARITY.md](BOTS-MODE-PARITY.md) (parity matrix),
  [UI-SPEC.md](UI-SPEC.md) (UI acceptance bar), [PLAN.md](PLAN.md) (phases/gates),
  [HANDOFF.md](HANDOFF.md) (live session state).
