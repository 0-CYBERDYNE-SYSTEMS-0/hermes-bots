#!/usr/bin/env bash
# provision-tailnet-gateway.sh — run this ON any Mac that has hermes-agent + Tailscale
# (or pipe it over SSH: `ssh user@mac 'bash -s' < scripts/provision-tailnet-gateway.sh`).
#
# It configures a remote-accessible hermes gateway (gated mode: basic auth) on 0.0.0.0:9300
# and installs a LaunchAgent so it survives reboots. Prints the URL + credentials to add to
# the Hermes Bots Android app (Gateways → + → "User + pass").
#
# Idempotent: re-running keeps the existing password (it cannot be recovered from the hash —
# delete the `dashboard.basic_auth` block in ~/.hermes/config.yaml first to mint a new one).
#
# Usage:  bash provision-tailnet-gateway.sh [--port 9300] [--username user]
set -euo pipefail

PORT="9300"
USERNAME="user"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --port) PORT="$2"; shift 2;;
    --username) USERNAME="$2"; shift 2;;
    *) echo "unknown arg: $1"; exit 1;;
  esac
done

HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"

# Locate the hermes CLI + the hermes-agent repo root (for the auth-provider python modules).
HERMES_BIN="$(command -v hermes || true)"
[[ -z "$HERMES_BIN" && -x "$HOME/.local/bin/hermes" ]] && HERMES_BIN="$HOME/.local/bin/hermes"
[[ -z "$HERMES_BIN" && -x "$HERMES_HOME/hermes-agent/venv/bin/hermes" ]] && HERMES_BIN="$HERMES_HOME/hermes-agent/venv/bin/hermes"
[[ -n "$HERMES_BIN" ]] || { echo "✗ hermes CLI not found"; exit 1; }

HERMES_REAL="$(readlink -f "$HERMES_BIN" 2>/dev/null || echo "$HERMES_BIN")"
# venv inside the clone: <clone>/venv/bin/hermes → root is two levels up.
AGENT_ROOT="$(dirname "$(dirname "$(dirname "$HERMES_REAL")")")"
[[ -f "$AGENT_ROOT/plugins/dashboard_auth/basic/__init__.py" ]] || AGENT_ROOT="$HERMES_HOME/hermes-agent"
[[ -f "$AGENT_ROOT/plugins/dashboard_auth/basic/__init__.py" ]] || { echo "✗ hermes-agent repo root not found (needed for the auth modules)"; exit 1; }
# Prefer the venv python (auth modules need httpx etc.); the CLI may be a wrapper script.
VENV_PY="$(dirname "$HERMES_REAL")/python"
[[ -x "$VENV_PY" ]] || VENV_PY="$HERMES_HOME/hermes-agent/venv/bin/python"
[[ -x "$VENV_PY" ]] || VENV_PY="$(command -v python3)"

echo "▸ hermes:  $HERMES_BIN"
echo "▸ agent:   $AGENT_ROOT"
echo "▸ home:    $HERMES_HOME"

# 1. Basic auth in config.yaml — reuse stored hash if present, else mint a password.
CONFIG_OUT="$("$VENV_PY" - "$AGENT_ROOT" "$USERNAME" <<'PYEOF'
import secrets, sys
sys.path.insert(0, sys.argv[1])
from plugins.dashboard_auth.basic import hash_password
from hermes_cli.config import load_config, save_config
from hermes_cli.plugins_cmd import ensure_basic_auth_plugin_enabled_in_config

username = sys.argv[2]
cfg = load_config()
basic = cfg.setdefault("dashboard", {}).setdefault("basic_auth", {})
if not str(basic.get("password_hash", "") or "").strip():
    pw = secrets.token_urlsafe(12)
    basic["username"] = username
    basic["password_hash"] = hash_password(pw)
    basic["password"] = ""
    ensure_basic_auth_plugin_enabled_in_config(cfg)
    save_config(cfg)
    print("PASSWORD:" + pw)
else:
    print("PASSWORD:EXISTING")
if not str(basic.get("secret", "") or "").strip():
    basic["secret"] = secrets.token_urlsafe(32)
    save_config(cfg)
PYEOF
)"
PASSWORD_LINE="$(echo "$CONFIG_OUT" | grep '^PASSWORD:' | head -1)"
[[ -n "$PASSWORD_LINE" ]] || { echo "✗ config step failed: $CONFIG_OUT"; exit 1; }
PASSWORD="${PASSWORD_LINE#PASSWORD:}"

# 2. LaunchAgent — hermes serve --host 0.0.0.0 --port $PORT, keep-alive + boot persistence.
PLIST="$HOME/Library/LaunchAgents/ai.hermes.tailnet-gateway.plist"
mkdir -p "$HOME/Library/LaunchAgents"
cat > "$PLIST" <<PLISTEOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key><string>ai.hermes.tailnet-gateway</string>
    <key>ProgramArguments</key>
    <array>
        <string>$HERMES_BIN</string>
        <string>serve</string>
        <string>--host</string>
        <string>0.0.0.0</string>
        <string>--port</string>
        <string>$PORT</string>
    </array>
    <key>EnvironmentVariables</key>
    <dict>
        <key>HERMES_DASHBOARD_SESSION_TOKEN</key>
        <string>hermes-tailnet-$PORT</string>
        <key>HOME</key>
        <string>$HOME</string>
    </dict>
    <key>RunAtLoad</key><true/>
    <key>KeepAlive</key><true/>
    <key>StandardOutPath</key><string>/tmp/hermes-$PORT-launchd.log</string>
    <key>StandardErrorPath</key><string>/tmp/hermes-$PORT-launchd.log</string>
</dict>
</plist>
PLISTEOF
launchctl unload "$PLIST" 2>/dev/null || true
pkill -f "serve --host 0.0.0.0 --port $PORT" 2>/dev/null || true
sleep 2
launchctl load "$PLIST"
sleep 8

# 3. Verify + print the app recipe.
TS_IP="$(/opt/homebrew/bin/tailscale ip -4 2>/dev/null | head -1 || echo '<tailnet-ip>')"
STATUS="$(curl -s -m 8 "http://127.0.0.1:$PORT/api/status" || echo '{"auth_required":"?"}')"
echo
echo "════════════════════════════════════════════════════════════"
echo " Tailnet gateway UP on port $PORT (LaunchAgent: keep-alive + boot)"
echo " status: $(echo "$STATUS" | head -c 160)"
echo
echo " Add to Hermes Bots app → Gateways → + :"
echo "   Base URL : http://$TS_IP:$PORT"
echo "   Auth     : User + pass"
echo "   Username : $USERNAME"
echo "   Password : $PASSWORD"
echo
echo " Reachable from ANY network while this Mac runs Tailscale."
echo "════════════════════════════════════════════════════════════"
