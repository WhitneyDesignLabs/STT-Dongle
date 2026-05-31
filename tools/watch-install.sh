#!/usr/bin/env bash
# Background watcher: wait for the phone to become an AUTHORIZED adb device, then
# install + launch the STT Keyboard demo APK automatically. Logs to ~/stt-install-watch.log.
# Intended to run detached overnight so the demo is on the phone the moment USB
# debugging is authorized. Watches for up to ~12 hours, polling every 20s.
ADB=/mnt/c/platform-tools/adb.exe
# Derive the APK path (repo root = parent of this tools/ dir); no hardcoded username/path.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
APK_WIN="$(wslpath -w "$REPO_ROOT/STT-Keyboard-debug.apk" 2>/dev/null || echo "$REPO_ROOT/STT-Keyboard-debug.apk")"
PKG=com.whitneydesignlabs.sttkeyboard
LOG="$HOME/stt-install-watch.log"

echo "=== watcher started $(date) ===" >> "$LOG"
"$ADB" start-server >/dev/null 2>&1
deadline=$(( $(date +%s) + 12*3600 ))
warned_unauth=0

while [ "$(date +%s)" -lt "$deadline" ]; do
  # An authorized device shows up as a line ending in <TAB>device
  if "$ADB" devices 2>/dev/null | grep -qE "[[:space:]]device$"; then
    echo "$(date) authorized device present; installing" >> "$LOG"
    if "$ADB" install -r "$APK_WIN" >> "$LOG" 2>&1; then
      "$ADB" shell am start -n "$PKG/.MainActivity" >> "$LOG" 2>&1
      echo "$(date) SUCCESS: installed + launched $PKG" >> "$LOG"
      exit 0
    else
      echo "$(date) install failed; will retry" >> "$LOG"
    fi
  elif "$ADB" devices 2>/dev/null | grep -qE "unauthorized"; then
    if [ "$warned_unauth" -eq 0 ]; then
      echo "$(date) phone present but UNAUTHORIZED -- tap 'Allow USB debugging' on the phone" >> "$LOG"
      warned_unauth=1
    fi
  fi
  sleep 20
done
echo "$(date) TIMED OUT after 12h; phone never became authorized" >> "$LOG"
