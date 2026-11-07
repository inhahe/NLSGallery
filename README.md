# Lithic Gallery

A gallery for Android that shows every photo and video on the phone (not only what arrived
after it was installed), sorted into tabs, laid out without cropping, and searchable by what
the pictures show. The descriptions are made on the phone by the same AI models
[thumbsup2](../thumbsup2) uses, and searched the same way.

## Tabs

Across the top:

| Tab | Shows |
|---|---|
| Camera | photos (and videos) from the camera (DCIM/Camera and camera folders) |
| Video | every video, wherever it is |
| Screenshots | screenshots and screen recordings |
| Messages | pictures and videos from messaging apps: WhatsApp, Telegram, Signal, Messages, Messenger, … |
| Downloads | the Download folder |
| Other | everything else (can be hidden) |
| All | everything (off by default; Settings → Tabs) |

Newest first, by the date each was taken.

## Thumbnails

- **Justified rows** (default): each row is filled edge to edge with pictures shown whole, at
  their own shape, so there is no empty space and nothing is cut off. Where rows break is
  worked out over the whole list to keep row heights even.
- **Grid**: the same number of pictures in every row, each fitted inside a square.

Pinch to make thumbnails bigger or smaller. Portrait and landscape pictures and videos keep
their orientation. Drag the bar on the right edge to move through years quickly.

Tap a thumbnail to view it full screen: swipe between pictures, pinch or double-tap to zoom,
tap a video to play it. The top bar has details (date, file, size, the AI description and the
text found in the picture), share, move to trash, and Edit / Open with another app.

## AI descriptions and search

Type in the search field to find pictures by what is in them ("dog on a beach", "receipt",
"birthday cake"), by text visible in them (signs, screenshots, documents), by their own
caption, file name or folder. Search finds matching words, also with typos and word starts, and
pictures whose description *means* what you typed even with different words; the two are
blended as in thumbsup2. A dot on a thumbnail marks a result found by meaning alone. Chips under
the search field choose where to look, and can limit results to the current tab.

### Setting up

The AI needs its models once (about 1.2 GB): the AI button (sparkles) or the menu's
**AI models**. They are the original files from their publishers (Salesforce's BLIP caption
model and the sentence-transformers MiniLM model on HuggingFace, EasyOCR's text detector and
reader from its GitHub), checked after downloading. Nothing is sent anywhere: every picture is
described on the phone.

From a computer, `push-models.bat` copies them over USB instead (after
`python tools\fetch_weights.py`).

### When pictures are described

- **As they arrive** (Settings → AI): new photos and videos are described a minute or so after
  they appear, even with the app closed.
- **When the app opens**: everything not yet described is described in the background, newest
  first.
- **The AI button**: shows how many are waiting; tap it to describe them now, or to stop.

Each one is searchable as soon as it is done. A notification shows progress and has a Stop
button. **Only while charging** makes the automatic runs wait for the charger.

Describing goes in two rounds: first a caption for every picture (a couple of seconds each), so
everything becomes searchable soon; then the text in pictures, which is slower (10 seconds to a
minute each), screenshots and downloads first. Videos are described from three frames
(captions) and the middle frame (text). Captions, text reading and videos can each be switched
off.

**Text reading detail** (Settings → AI): *Balanced* (default) reads screenshots, downloads and
messages in full detail and camera photos and videos at half size, about 10 seconds each instead
of a minute; *Full* reads everything as thumbsup2 does; *Fast* reads everything at half size.

## Building

Gradle (JDK 17+): `gradlew :app:assembleDebug` makes one APK per processor type in
`app/build/outputs/apk/debug/` (`app-arm64-v8a-debug.apk` for phones). `gradlew :core:test`
compares the on-device pipeline with thumbsup2's output (see `tools/README.md` for the test data
and weights it needs). `design.md` describes how it works.
