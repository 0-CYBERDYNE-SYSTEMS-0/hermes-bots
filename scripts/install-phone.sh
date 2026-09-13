#!/usr/bin/env bash
# install-phone.sh — install the Hermes Bots app on the USB phone AND copy the gateway
# configuration from the emulator (connections incl. tailnet gateway + credentials).
#
# Prerequisites: phone plugged in with USB debugging authorized (adb devices shows it),
# emulator running with the connections you want, both attached to the same adb server.
#
# Usage:  bash scripts/install-phone.sh [PHONE_SERIAL]   (default YOUR_PHONE_SERIAL)
set -euo pipefail

SERIAL="${1:-YOUR_PHONE_SERIAL}"
PKG="ai.hermes.bots"
APK="app/build/outputs/apk/debug/app-debug.apk"   # debug build: config can be injected
EMU="emulator-5554"

command -v adb >/dev/null 2>&1 || { echo "✗ adb not on PATH (source scripts/env.sh)"; exit 1; }

adb devices | grep -q "^$SERIAL"'[[:space:]]' || { echo "✗ phone $SERIAL not visible in adb devices"; exit 1; }

echo "▸ 1/5 install $APK → $SERIAL"
# A previously-installed RELEASE build has a different signature: replace it.
if ! adb -s "$SERIAL" install -r "$APK" 2>&1 | tail -1 | grep -q Success; then
  echo "  signature mismatch or error — uninstalling and reinstalling"
  adb -s "$SERIAL" uninstall "$PKG" >/dev/null 2>&1 || true
  adb -s "$SERIAL" install "$APK" | tail -1
fi

echo "▸ 2/5 grant POST_NOTIFICATIONS"
adb -s "$SERIAL" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

echo "▸ 3/5 adb reverse for loopback dev gateways (9119 dev + 9120 relay)"
adb -s "$SERIAL" reverse tcp:9119 tcp:9119
adb -s "$SERIAL" reverse tcp:9120 tcp:9120

echo "▸ 4/5 copy gateway configuration from emulator"
# Launch once so the app creates its storage, then stop it before overwriting.
adb -s "$SERIAL" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
sleep 4
adb -s "$SERIAL" shell am force-stop "$PKG"
adb -s "$EMU" shell am force-stop "$PKG" >/dev/null 2>&1 || true
adb -s "$EMU" exec-out run-as "$PKG" cat files/datastore/connections.preferences_pb > /tmp/phone-conn.pb
adb -s "$SERIAL" shell run-as "$PKG" mkdir -p files/datastore
adb -s "$SERIAL" push /tmp/phone-conn.pb /data/local/tmp/phone-conn.pb >/dev/null
adb -s "$SERIAL" shell run-as "$PKG" sh -c "'cp /data/local/tmp/phone-conn.pb files/datastore/connections.preferences_pb'"
adb -s "$SERIAL" shell run-as "$PKG" sh -c "'chmod 600 files/datastore/connections.preferences_pb'"
# Settings (theme/notifications) if the emulator has them:
if adb -s "$EMU" shell run-as "$PKG" test -f files/datastore/settings.preferences_pb 2>/dev/null; then
  adb -s "$EMU" exec-out run-as "$PKG" cat files/datastore/settings.preferences_pb > /tmp/phone-settings.pb
  adb -s "$SERIAL" push /tmp/phone-settings.pb /data/local/tmp/phone-settings.pb >/dev/null
  adb -s "$SERIAL" shell run-as "$PKG" sh -c "'cp /data/local/tmp/phone-settings.pb files/datastore/settings.preferences_pb'"
fi

echo "▸ 5/5 launch + verify"
adb -s "$SERIAL" shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 10
adb -s "$SERIAL" exec-out screencap -p > phone-after-install.png
echo
echo "✅ Done. Roster screenshot: phone-after-install.png"
echo "   Connections copied: Local gateway (9119 dev), Relay GW (9120 dev),"
echo "   tailnet-gateway (100.64.0.10:9300 — needs Tailscale ON on the phone)."
