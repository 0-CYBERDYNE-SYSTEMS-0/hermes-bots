#!/usr/bin/env bash
# Local dev gateways for Hermes Bots integration testing (loopback binds only).
# Tokens are dev-only literals: the servers bind 127.0.0.1, so exposure is the
# local machine only. The emulator reaches the 9119 instance at 10.0.2.2:9119.
#
#   scripts/dev-gateways.sh start   → launch 9119 (+ 9120 with GATEWAY2=1)
#   scripts/dev-gateways.sh stop    → kill both
set -euo pipefail

TOKEN_9119="dev-token-9119"
TOKEN_9120="dev-token-9120"
LOG_9119="${TMPDIR:-/tmp}/hermes-serve-9119.log"
LOG_9120="${TMPDIR:-/tmp}/hermes-serve-9120.log"

case "${1:-start}" in
  start)
    cd "$HOME/.hermes/hermes-agent"
    HERMES_DASHBOARD_SESSION_TOKEN="$TOKEN_9119" nohup hermes serve --host 127.0.0.1 --port 9119 \
      > "$LOG_9119" 2>&1 &
    echo "gateway1 pid=$! log=$LOG_9119 (token $TOKEN_9119)"
    if [[ "${GATEWAY2:-0}" == "1" ]]; then
      HERMES_DASHBOARD_SESSION_TOKEN="$TOKEN_9120" nohup hermes serve --host 127.0.0.1 --port 9120 \
        > "$LOG_9120" 2>&1 &
      echo "gateway2 pid=$! log=$LOG_9120 (token $TOKEN_9120)"
    fi
    ;;
  stop)
    pkill -f "hermes serve --host 127.0.0.1 --port 9119" || true
    pkill -f "hermes serve --host 127.0.0.1 --port 9120" || true
    echo "gateways stopped"
    ;;
  *)
    echo "usage: $0 {start|stop}" >&2; exit 2 ;;
esac
