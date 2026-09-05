#!/usr/bin/env bash
# Idempotent toolchain bootstrap for the Hermes Bots Android mission.
# Safe to re-run; each step checks before acting. macOS (Apple Silicon).
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
JDK17="/opt/homebrew/opt/openjdk@17"
CT_URL="https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip"

log() { printf '\n[bootstrap] %s\n' "$*"; }

export JAVA_HOME="$JDK17"

# 1. JDK 17
if [ -x "$JDK17/bin/java" ]; then
  log "JDK 17 present: $($JDK17/bin/java -version 2>&1 | head -1)"
else
  log "Installing openjdk@17 via Homebrew…"
  brew install openjdk@17
fi

# 2. cmdline-tools
if [ -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  log "cmdline-tools present."
else
  log "Downloading Android cmdline-tools…"
  mkdir -p "$SDK/cmdline-tools" /tmp/ct-dl
  curl -sL -o /tmp/ct-dl/ct.zip "$CT_URL"
  unzip -q -o /tmp/ct-dl/ct.zip -d /tmp/ct-dl
  rm -rf "$SDK/cmdline-tools/latest"
  mkdir -p "$SDK/cmdline-tools/latest"
  mv /tmp/ct-dl/cmdline-tools/* "$SDK/cmdline-tools/latest/"
fi

# 3. Licenses + SDK packages
NEED_PKGS=""
for p in "platform-tools" "platforms;android-35" "build-tools;35.0.0" "emulator" "system-images;android-35;default;arm64-v8a"; do
  case "$p" in
    platform-tools) [ -x "$SDK/platform-tools/adb" ] || NEED_PKGS="$NEED_PKGS \"$p\"" ;;
    platforms*)      [ -d "$SDK/platforms/android-35" ] || NEED_PKGS="$NEED_PKGS \"$p\"" ;;
    build-tools*)    [ -d "$SDK/build-tools/35.0.0" ] || NEED_PKGS="$NEED_PKGS \"$p\"" ;;
    emulator)        [ -x "$SDK/emulator/emulator" ] || NEED_PKGS="$NEED_PKGS \"$p\"" ;;
    system-images*)  [ -d "$SDK/system-images/android-35" ] || NEED_PKGS="$NEED_PKGS \"$p\"" ;;
  esac
done
if [ -n "$NEED_PKGS" ]; then
  log "Accepting licenses…"
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
  log "Installing missing SDK packages:$NEED_PKGS"
  eval "\"$SDK/cmdline-tools/latest/bin/sdkmanager\" --sdk_root=\"$SDK\" $NEED_PKGS"
else
  log "SDK packages present."
fi

# 4. AVD
if [ -f "$HOME/.android/avd/hermes-pixel.ini" ]; then
  log "AVD hermes-pixel present."
else
  log "Creating AVD hermes-pixel (Pixel 7, API 35, arm64)…"
  echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd \
    -n hermes-pixel -k "system-images;android-35;default;arm64-v8a" -d pixel_7
fi

# 5. Gradle (for wrapper bootstrap; project ships the wrapper afterward)
if ! command -v gradle >/dev/null 2>&1; then
  log "Installing gradle via Homebrew (one-time, to generate the wrapper)…"
  brew install gradle
fi

# 6. Device check
log "adb devices:"
"$SDK/platform-tools/adb" devices -l || true

log "DONE. For your shell:"
cat <<EOF
  export JAVA_HOME=$JDK17
  export ANDROID_HOME=$SDK
  export PATH="\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/emulator:\$PATH"
EOF
