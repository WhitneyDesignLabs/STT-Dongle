#!/usr/bin/env bash
# One-shot install of the STT Keyboard demo APK to a USB-connected, AUTHORIZED phone.
# Run from WSL:  bash install.sh
# Uses the Windows adb.exe (the phone is on the Windows USB bus, not WSL's).
set -uo pipefail

ADB=/mnt/c/platform-tools/adb.exe
APK_WIN='C:/Users/homet/Documents/STT-Dongle/STT-Keyboard-debug.apk'
PKG=com.whitneydesignlabs.sttkeyboard

echo "Devices:"; "$ADB" devices
echo "Installing $APK_WIN ..."
if "$ADB" install -r "$APK_WIN"; then
  "$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1
  echo "OK: installed and launched $PKG"
else
  echo "INSTALL FAILED. Most likely the phone is not authorized for USB debugging."
  echo "On the phone: Settings > Developer options > USB debugging = ON,"
  echo "set USB mode to 'File transfer', then accept the 'Allow USB debugging?' prompt."
  echo "Then re-run: bash install.sh"
  exit 1
fi
