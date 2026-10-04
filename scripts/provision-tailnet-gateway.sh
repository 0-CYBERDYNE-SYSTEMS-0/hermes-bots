#!/usr/bin/env bash
# Start a loopback Hermes gateway and expose it to this tailnet through Tailscale HTTPS.
set -euo pipefail
umask 077

PORT="9300"
QR=0
NAME_LABEL=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --port) PORT="$2"; shift 2;;
    --qr) QR=1; shift;;
    --name) NAME_LABEL="$2"; shift 2;;
    *) echo "unknown arg: $1"; exit 1;;
  esac
done
if [[ ! "$PORT" =~ ^[0-9]{1,5}$ ]] || ((PORT < 1 || PORT > 65535)); then
  echo "port must be a number from 1 to 65535"
  exit 1
fi

HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
HERMES_BIN="$(command -v hermes || true)"
[[ -z "$HERMES_BIN" && -x "$HOME/.local/bin/hermes" ]] && HERMES_BIN="$HOME/.local/bin/hermes"
[[ -z "$HERMES_BIN" && -x "$HERMES_HOME/hermes-agent/venv/bin/hermes" ]] && HERMES_BIN="$HERMES_HOME/hermes-agent/venv/bin/hermes"
[[ -n "$HERMES_BIN" ]] || { echo "Hermes Agent CLI not found"; exit 1; }

TAILSCALE_BIN="$(command -v tailscale || true)"
[[ -z "$TAILSCALE_BIN" && -x /opt/homebrew/bin/tailscale ]] && TAILSCALE_BIN=/opt/homebrew/bin/tailscale
[[ -n "$TAILSCALE_BIN" ]] || { echo "Tailscale CLI not found"; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "python3 not found"; exit 1; }

TAILNET_DNS="$("$TAILSCALE_BIN" status --json | python3 -c 'import json,sys; print(json.load(sys.stdin).get("Self",{}).get("DNSName","").rstrip("."))')"
[[ -n "$TAILNET_DNS" ]] || { echo "Tailscale is not connected or has no DNS name"; exit 1; }

mkdir -p "$HERMES_HOME"
TOKEN_FILE="$HERMES_HOME/dashboard-session-token"
if [[ ! -s "$TOKEN_FILE" ]]; then
  (umask 077; python3 -c 'import secrets; print(secrets.token_urlsafe(32))' > "$TOKEN_FILE")
fi
chmod 600 "$TOKEN_FILE"
SESSION_TOKEN="$(cat "$TOKEN_FILE")"
[[ -n "$SESSION_TOKEN" ]] || { echo "Session token file is empty"; exit 1; }

# A loopback bind keeps the HTTP listener off the LAN. Tailscale Serve provides HTTPS to the tailnet.
PLIST="$HOME/Library/LaunchAgents/ai.hermes.tailnet-gateway.plist"
LOG_FILE="$HERMES_HOME/hermes-$PORT-launchd.log"
mkdir -p "$HOME/Library/LaunchAgents"
: > "$LOG_FILE"
chmod 600 "$LOG_FILE"
python3 - "$PLIST" "$HERMES_BIN" "$PORT" "$SESSION_TOKEN" "$HERMES_HOME" "$HOME" "$LOG_FILE" <<'PLISTPY'
import os
import plistlib
import sys

path, binary, port, token, hermes_home, home, log_file = sys.argv[1:]
plist = {
    "Label": "ai.hermes.tailnet-gateway",
    "ProgramArguments": [binary, "serve", "--host", "127.0.0.1", "--port", port],
    "EnvironmentVariables": {
        "HERMES_DASHBOARD_SESSION_TOKEN": token,
        "HERMES_HOME": hermes_home,
        "HOME": home,
    },
    "RunAtLoad": True,
    "KeepAlive": True,
    "StandardOutPath": log_file,
    "StandardErrorPath": log_file,
}
with open(path, "wb") as output:
    plistlib.dump(plist, output, sort_keys=False)
os.chmod(path, 0o600)
PLISTPY

launchctl unload "$PLIST" 2>/dev/null || true
pkill -f "serve --host 127.0.0.1 --port $PORT" 2>/dev/null || true
sleep 2
launchctl load "$PLIST"
sleep 8
curl -fsS -m 8 "http://127.0.0.1:$PORT/api/status" >/dev/null || {
  echo "Hermes gateway did not respond on loopback port $PORT"
  exit 1
}

"$TAILSCALE_BIN" serve --bg "$PORT"
APP_URL="https://$TAILNET_DNS"
echo
echo "Tailnet HTTPS gateway is ready: $APP_URL"
echo "Auth mode: Token"
echo "Session token: $SESSION_TOKEN"
echo "Keep the token private. The listener is local; Tailscale Serve exposes its HTTPS endpoint to your tailnet."

if [[ "$QR" == "1" ]]; then
  LABEL="${NAME_LABEL:-${TAILNET_DNS%%.*}}"
  DEEPLINK="$(python3 - "$APP_URL" "$SESSION_TOKEN" "$LABEL" <<'QREOF'
import sys, urllib.parse
url, token, label = sys.argv[1:4]
query = urllib.parse.urlencode(
    {"url": url, "token": token, "name": label},
    quote_via=urllib.parse.quote,
)
print(f"hermesbots://add-gateway?{query}")
QREOF
)"
  echo
  echo "Deep link (contains the session token; keep it private):"
  echo "$DEEPLINK"
  printf '%s\n' "$DEEPLINK" > "$HOME/hermes-gateway-deeplink.txt"
  if python3 -c 'import qrcode' >/dev/null 2>&1; then
    python3 - "$DEEPLINK" <<'QREOF'
import sys, qrcode
qr = qrcode.QRCode(border=1)
qr.add_data(sys.argv[1])
qr.make()
qr.print_ascii(invert=True)
QREOF
    echo "Scan the QR code with Hermes Bots."
  else
    echo "Install Python package 'qrcode' to print a terminal QR code."
  fi
fi
