#!/usr/bin/env bash
# Types a search into the gallery running on the emulator and saves a screenshot:
#   tools/emu-search.sh "query words" out.png
# Refuses unless the gallery is the app in front, so keystrokes never land elsewhere.
set -euo pipefail
export MSYS2_ARG_CONV_EXCL='*'
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
e() { "$ADB" -e "$@"; }
front() { e shell dumpsys activity activities | grep -a -m1 topResumedActivity; }
front | grep -q "com.lithic.gallery" || { echo "gallery is not in front: $(front)"; exit 1; }
# Clear any previous query (the field's clear button), focus the field, type.
e shell input tap 450 216
sleep 1
e shell input keyevent KEYCODE_MOVE_END
for _ in $(seq 1 40); do e shell input keyevent KEYCODE_DEL; done
front | grep -q "com.lithic.gallery" || { echo "focus moved away: $(front)"; exit 1; }
e shell input text "${1// /%s}"
sleep 4
e shell input keyevent KEYCODE_BACK   # hide the keyboard
sleep 1
e exec-out screencap -p > "$2"
