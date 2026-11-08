#!/usr/bin/env bash
# Fills a running emulator with the test images and videos, in the folders a phone keeps them
# in, and (with --models) copies the AI models' weights into the debug app's folder, so the
# whole app can be tried end to end without anyone's own photos.
#
#   tools/seed-emulator.sh [--models]
#
# Needs tools/testdata (tools/fetch_test_images.py; videos are made here with ffmpeg) and, for
# --models, tools/weights (tools/fetch_weights.py) and the debug app installed and opened once.
set -euo pipefail
export MSYS2_ARG_CONV_EXCL='*'
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
# Windows programs (adb, ffmpeg) want Windows paths, not Git Bash's /d/...; Git Bash takes both.
win() { if command -v cygpath >/dev/null; then cygpath -m "$1"; else echo "$1"; fi; }
here="$(win "$(cd "$(dirname "$0")" && pwd)")"
data="$here/testdata"
e() { "$ADB" -e "$@"; }

mkdir -p "$data/video"
v="$data/video"
[ -f "$data/video/landscape.mp4" ] || ffmpeg -loglevel error -y -f lavfi -i "testsrc=duration=6:size=1280x720:rate=25" -c:v libx264 -pix_fmt yuv420p "$v/landscape.mp4"
[ -f "$data/video/rotated90.mp4" ] || ffmpeg -loglevel error -y -display_rotation 90 -i "$v/landscape.mp4" -c copy "$v/rotated90.mp4"
# Stored 720x1080 but shown 3:2: non-square pixels.
[ -f "$data/video/anamorphic.mp4" ] || ffmpeg -loglevel error -y -loop 1 -i "$data/coco_000000000785.jpg" -t 4 -vf "scale=720:1080,format=yuv420p" -c:v libx264 "$v/anamorphic.mp4"

wa="/sdcard/Android/media/com.whatsapp/WhatsApp/Media"
e shell "mkdir -p /sdcard/DCIM/Camera /sdcard/Pictures/Screenshots /sdcard/Download /sdcard/Pictures/Messages '$wa/WhatsApp Images' '$wa/WhatsApp Video'"
for f in "$data"/coco_*.jpg "$data/exif_orientation6.jpg" "$data/video/landscape.mp4" "$data/video/rotated90.mp4"; do
    e push "$f" /sdcard/DCIM/Camera/ >/dev/null
done
e push "$data/synth_chat_screenshot.png" "$data/synth_settings_screenshot.png" /sdcard/Pictures/Screenshots/ >/dev/null
e push "$data/synth_document.png" "$data/synth_rotated_text.png" /sdcard/Download/ >/dev/null
e push "$data/synth_meme.jpg" "$wa/WhatsApp Images/" >/dev/null
e push "$data/video/anamorphic.mp4" "$wa/WhatsApp Video/" >/dev/null
e push "$data/synth_sign.jpg" /sdcard/Pictures/Messages/ >/dev/null
# Pictures that say where they were taken, for place search ("in paris", "hawaii").
[ -f "$data/geo/geo_paris_video.mp4" ] || python "$here/make_geotagged.py"
for f in "$data"/geo/*; do
    e push "$f" /sdcard/DCIM/Camera/ >/dev/null
done
# Spread the camera photos over the year so dates and ordering show.
e shell 'i=0; for f in /sdcard/DCIM/Camera/*; do i=$((i+1)); touch -d "2026-0$(( (i % 9) + 1 ))-1$(( i % 9 )) 1$(( i % 9 )):00:00" "$f"; done'
e shell content call --uri content://media/ --method scan_volume --arg external_primary >/dev/null
echo "media seeded"

if [ "${1:-}" = "--models" ]; then
    dest=/sdcard/Android/data/com.lithic.gallery.debug/files/models
    # The app must create its folder: one adb creates belongs to the shell, and the app cannot read it.
    e shell am start -n com.lithic.gallery.debug/com.lithic.gallery.MainActivity >/dev/null
    for _ in $(seq 1 30); do e shell "[ -d $dest ]" && break; sleep 1; done
    e shell "[ -d $dest ]" || { echo "the app did not create $dest: is it installed?"; exit 1; }
    for m in blip minilm craft english_g2; do
        e shell mkdir -p "$dest/$m"
        for f in "$here/weights/$m"/*; do
            e push "$f" "$dest/$m/" >/dev/null
        done
    done
    echo "models copied"
fi
