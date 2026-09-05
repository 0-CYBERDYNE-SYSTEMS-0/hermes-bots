# Hermes Bots for Android

Native Android client (Kotlin + Jetpack Compose) for **[hermes-agent](https://github.com/NousResearch/hermes-agent)**
gateways — full **Bots mode parity** in your pocket, over Tailscale or LAN.

Connect to any number of hermes gateways and: chat with every bot (canonical forever-chats,
streaming, approvals), create/edit bots (SOUL.md, model pin, skills/toolsets/MCP, avatars),
manage per-bot routines (cron), run bot group chats, and relay bot-to-bot messages across
gateways — the app plays the same relay role the desktop does.

**Status**: mission kickoff — docs/specs complete, toolchain bootstrapped, Phase 1 (scaffold +
protocol core) next. See [PLAN.md](PLAN.md).

| Doc | Contents |
|---|---|
| [AGENTS.md](AGENTS.md) | mission orientation, machine map, current state |
| [DECISIONS.md](DECISIONS.md) | locked decisions |
| [PROTOCOL.md](PROTOCOL.md) | hermes gateway wire protocol (JSON-RPC/WS) — the client bible |
| [BOTS-MODE-PARITY.md](BOTS-MODE-PARITY.md) | desktop bots-mode analysis + v1 parity matrix |
| [PLAN.md](PLAN.md) | phases, gates, lanes, testing strategy |

## Quick start (dev)
```bash
git clone <this-repo> hermes-bots && cd hermes-bots   # if not already here
scripts/bootstrap-toolchain.sh                        # idempotent toolchain setup
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Gateway prerequisites (README will grow in Phase 5)
On the machine running hermes (e.g. your Mac):
```bash
# token mode (simplest for the app)
export HERMES_DASHBOARD_SESSION_TOKEN=<your-token>
hermes serve --host 0.0.0.0 --port 9119
```
On the phone: install Tailscale, join the tailnet, then add a gateway in the app:
`https://<your-mac>.ts.net:9119` + the token (or username/password if you configured the
basic-auth provider).
