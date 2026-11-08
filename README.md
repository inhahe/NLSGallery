# NLSGallery

NLSGallery (Natural Language Search Gallery) is a gallery for Android that shows every photo and video on the phone (not only what arrived
after it was installed), sorted into tabs, laid out without cropping, and searchable by what
the pictures show. The descriptions are made on the phone by the same AI models
[thumbsup2](../thumbsup2) uses, and searched the same way.

## What it needs

- **Android 10 or newer, on a 64-bit phone** (every phone of the last several years).
- **For the AI** (describing pictures, and searching them by what they show): **at least 6 GB
  of memory**. Describing holds about 2 GB at once; with less, Android closes the app, or the
  apps around it, while it works. On a phone with less, NLSGallery says so the first time it
  opens and leaves the AI off; browsing and searching by date, place, file name and folder still
  work. Settings → AI → *Use the AI anyway* turns it on regardless.
- **About 1.5 GB of free storage** for the AI models.
- **Time**: describing a whole library takes hours (a few seconds a picture for the caption, up
  to a minute for the text in it), so the first run is best left going on the charger.
  Snapdragon phones read text about five times as fast (see *Read text on the GPU*).

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

**Long-press a thumbnail** for its file name and folder, and to open, share, delete (to the
trash, where Android keeps it 30 days), edit or open it in another app, or copy its name; below
are its date, where it was taken, its size and what the AI made of it.

Tap a thumbnail to view it full screen: swipe between pictures, pinch or double-tap to zoom,
tap a video to play it. The top bar has details (date, place, file, size, the AI description and
the text found in the picture), share, move to trash, and Edit / Open with another app.

## AI descriptions and search

Type in the search field to find pictures by what is in them ("dog on a beach", "receipt",
"birthday cake"), by text visible in them (signs, screenshots, documents), by their own
caption, file name or folder, and by where they were taken (see *Places*). Search finds matching words, also with typos and word starts, and
pictures whose description *means* what you typed even with different words; the two are
blended as in thumbsup2. A dot on a thumbnail marks a result found by meaning alone. Chips under
the search field choose where to look, and can limit results to the current tab.

**Dates.** Say when, in plain English: "beach last summer", "dog 2023", "christmas", "flowers in
may", "two weeks ago", "yesterday", "march 5", "the 90s", "since 2020", "between 2019 and 2021",
even "show me photos of dogs from last summer". "Videos" asks for videos: "videos from last
summer", "videos of the dog", "dog videos". Holidays: Christmas, New Year, Easter, Thanksgiving,
Halloween, Valentine's Day, Mother's and Father's Day, St Patrick's Day, the Fourth of July,
Diwali, Holi, the Lunar New Year, Tết, the Mid-Autumn Festival, Ramadan, Eid, Hanukkah, Passover,
Rosh Hashanah and Yom Kippur, each on its own calendar's days ("diwali 2023", "last eid").
The search then looks only among the pictures taken then; a date on its own lists them all,
newest first (which works before anything is described). A chip under the search field shows
how the date was read ("summer 2026: Jun 1, 2026 – Aug 31, 2026"); tap it to search for those
words instead. Seasons follow the phone's region (turned round in the southern hemisphere), weeks
start on its first day of the week, and 3/5/2024 is read in its order. Words that are also
ordinary words are dates only when something says so: "christmas tree" looks for Christmas
trees, "last christmas" for pictures from last Christmas, and "may" alone is never a date.

**Places.** Say where: "beach in hawaii", "paris", "photos from japan", "my trip to rome",
"videos in tokyo last summer", "sunset in honolulu". Photos and videos keep where they were
taken (their GPS), which NLSGallery turns into the town, the big city near it, the state or
province and the country, so any of those names finds them, and countries also by everyday
names ("usa", "uk", "holland"). The search then looks only among the pictures taken there; a
place on its own lists them all. A chip shows the place it was taken to be ("Hawaii, United
States"); tap it to search for those words instead. Only places the library has pictures from
count, and a name that is also an ordinary word ("nice", "reading") is a place only after "in",
"at", "near", "from", "to" or "around", or with nothing else but words like "photos", "my" or
"trip": "nice beach" and "dinner in a nice restaurant" look for nice things. A place named
without those words ("beach hawaii") puts the pictures taken there first among what the other
words find; a chip says so ("Hawaii, United States first"), and tapping it ranks without it. Android hides where
pictures were taken unless the app is allowed to read it, which it asks once (Settings → Search
→ *Places* asks again, and says how many pictures say where they were taken); taking the
permission away in Android's settings makes NLSGallery forget what it read. Pictures with no
location (screenshots, downloads, a camera with location off) are not found by place. The
long-press details show each picture's place.

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

Each one is searchable as soon as it is done. While it works, a line under the tabs says which
round it is in, how far through, and about how long is left ("Describing pictures · 1,234 of
5,000 · about 2 h left"); tap it for more. A notification shows the same and has a Stop
button. **Only while charging** makes the automatic runs wait for the charger.

Describing goes in two rounds: first a caption for every picture (a couple of seconds each), so
everything becomes searchable soon; then the text in pictures, which is slower (10 seconds to a
minute each), screenshots and downloads first. Videos are described from three frames
(captions) and the middle frame (text). Captions, text reading and videos can each be switched
off.

When something goes wrong:

- A picture that cannot be read (a damaged file) is listed as failed in the AI button's dialog,
  and is not tried again until it changes. **Retry failed** there tries them all again.
- When the AI itself fails on a picture, it is tried again in later runs, after the others. If
  it keeps failing while the AI works for other pictures (three runs), it is set aside as
  failed too. If the AI is failing on everything (the phone short of memory, say), nothing is
  set aside: describing stops and starts again later.
- What search by meaning needs is made with each description. If that part alone fails, the
  description is kept and searchable by its words, and the rest is made later.

**Text reading detail** (Settings → AI): *Balanced* (default) reads screenshots, downloads and
messages in full detail and camera photos and videos at half size, about 10 seconds each instead
of a minute; *Full* reads everything as thumbsup2 does; *Fast* reads everything at half size.

**Read text on the GPU** (Settings → AI, on by default on Snapdragon phones): the text detector
runs on the phone's graphics processor, about five times as fast (finding the text in a camera
photo takes about 2 seconds instead of 10, at full detail about 10 instead of 50). It computes in half
precision, which now and then moves a text box by a pixel, so a word can read differently from
thumbsup2's; switched off, text is read exactly as thumbsup2 reads it.

## Installing

With the phone connected by USB (USB debugging on in Developer options, and this computer
allowed when the phone asks), `install-on-phone.bat` builds the release version and installs
it. Run it again to update: descriptions, settings and models are kept.

**Back up the release key** (`C:\Users\Computer\.android\lithic-gallery-release.jks`) **and
`keystore.properties`**, which holds its password. Android installs an update only if it is
signed with the same key. A build signed with another key has to be uninstalled first, which
deletes the descriptions and the downloaded models.

## Building

Gradle (JDK 17+): `gradlew :app:assembleDebug` makes one APK per processor type in
`app/build/outputs/apk/debug/` (`app-arm64-v8a-debug.apk` for phones, package
`com.lithic.gallery.debug`, installed beside the release one). `build-release.bat` makes the
signed release APKs in `app/build/outputs/apk/release/`. It needs `keystore.properties` (not
in git), which names the key:

```
storeFile=C:/path/to/lithic-gallery-release.jks
storePassword=...
keyAlias=lithic-gallery
keyPassword=...
```

To make a new key: `keytool -genkeypair -keystore lithic-gallery-release.jks -storetype PKCS12
-alias lithic-gallery -keyalg RSA -keysize 4096 -validity 36500`.

`gradlew :core:test` compares the on-device pipeline with thumbsup2's output (see
`tools/README.md` for the test data and weights it needs). `design.md` describes how it works.

## Credits

Place names are from [GeoNames](https://www.geonames.org/) (`cities1000`, `admin1CodesASCII`,
`countryInfo`), licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). The
Umm al-Qura calendar's months (for Ramadan and Eid) are from
[hijridate](https://github.com/dralshehri/hijridate)'s table (MIT licence). Settings → About →
*Credits and licences* shows both notices in the app.
