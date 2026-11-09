# NLSGallery: design

An Android gallery for every photo and video on the phone, sorted into tabs by where they came
from, laid out without cropping, and searchable by what they show: the same AI descriptions,
OCR and search as thumbsup2 (`..\thumbsup2`), run on the phone.

## Modules

| Module | What | Why separate |
|---|---|---|
| `core/` | Plain Kotlin (JVM): tokenizer, image operations, BLIP / EasyOCR / MiniLM pipelines, MiniSearch port, hybrid ranking, thumbnail layouts | Runs on the desktop JVM, so its tests compare it with thumbsup2's own output using the same graphs and weights the phone runs |
| `app/` | Android app (Compose, Room, WorkManager, Coil, Media3, ONNX Runtime for Android) | |
| `tools/` | Python and Node scripts: model export, reference output, test data, weight fetching | Run in thumbsup2's Python environment (see `tools/README.md`) |

## The AI pipeline (what "the same as thumbsup2" means)

thumbsup2's worker (`scripts/ai_describe.py`) gives each image a BLIP caption, EasyOCR text,
and a MiniLM embedding of `" ".join(filter(None, [caption, ocr]))`; its search page blends
MiniSearch keyword ranking with cosine similarity by Reciprocal Rank Fusion. The app does each
step identically:

| Step | thumbsup2 | app (`core/`) | Verified by |
|---|---|---|---|
| Caption | `Salesforce/blip-image-captioning-base`, transformers 5.5.4 processor, `generate(max_new_tokens=40)` (greedy), `decode(skip_special_tokens=True).strip()` | `BlipCaptioner`: torchvision's 8-bit antialiased bicubic (`Resample.torch`), CLIP normalization, greedy decoding over two graphs, `BertTokenizer.decode` | `BlipCaptionerTest`: word-for-word equal on all 42 test images |
| OCR | EasyOCR 1.7.2 `Reader(['en']).readtext(np.array(rgb))`, joined by spaces | `EasyOcr`: CRAFT score maps → `CraftBoxes` (connected components, dilation, OpenCV's hull and rotating calipers, `boxPoints`) → `TextGrouping` → `Crops` (OpenCV bilinear, perspective warp) → `Recognizer` (Pillow bicubic, CTC greedy, contrast retry) | `EasyOcrTest`: same boxes and same text on all 42 images, at the default canvas and shrunk to 448 |
| Embedding | `sentence-transformers/all-MiniLM-L6-v2`, `encode(normalize_embeddings=True)` | `MiniLmEmbedder` + `BertTokenizer.encode` (max 256 tokens) | `MiniLmEmbedderTest`: cosine > 0.99999; `BertTokenizerTest` |
| Keyword search | MiniSearch 7.2: fields aiCaption, aiOcr, caption, filename, path; `prefix: true, fuzzy: 0.2, combineWith: 'AND'` | `MiniSearch` (port: BM25+, prefix weight 0.375, fuzzy 0.45, JS rounding; a field's average length is the exact mean rather than MiniSearch's running average, so scores do not depend on the order documents were added in: they agree to ~1e-15) | `MiniSearchTest`: same ids, same scores, 164 queries; the same scores, exactly, whatever the order documents came in |
| Hybrid | BM25 top 200 + cosine top 200, RRF k=60, best 500; semantic only if description or OCR field searched | `HybridSearch` (plus narrowing, below) | by construction (small); `HybridSearchTest`: narrowed, the best are the best among the files allowed |

Reference output comes from thumbsup2 itself (`tools/make_reference.py` runs its unmodified
`ai_describe.py`), HuggingFace's tokenizers, thumbsup2's MiniSearch build, and torchvision /
Pillow / OpenCV for the image operations (`tools/make_*_reference.*`). The image operations are
bit-exact, including OpenCV quirks EasyOCR depends on (its "LANCZOS" flag is INTER_LINEAR; its
grey conversion of thumbsup2's RGB array weights the channels as BGR).

The geometry (`Geometry`: hull, `minAreaRect`, `boxPoints`) follows **OpenCV 4.13**, the
version in thumbsup2's environment, to the float32 bit (`GeometryTest`, 520 masks against
`tools/reference/geometry.json`). It has to: box corners are scaled then truncated to
integers, so one ulp can move a box edge a pixel. Older OpenCV describes the same rectangle
with width and height swapped and another angle, which rounds the corners differently; when
thumbsup2's OpenCV changes, regenerate the reference (`tools/make_geometry_reference.py`) and
`ocr*.json`, and port what changed.

The app's own decoding gives the models what PIL gives thumbsup2 (`MediaDecodingDeviceTest`,
on the device): all 42 test images to the byte through Android's decoders, including an Adobe
RGB and a camera-profile JPEG; an image's stored values, not values converted to sRGB (PIL
ignores colour profiles; phones shoot Display P3), so the bitmap is relabelled sRGB before it
is read; transparent pixels with their stored colour (decoded unpremultiplied: premultiplied,
a fully transparent white pixel reads back black).

Deliberate differences from thumbsup2:

- **EXIF orientation is applied** before describing. thumbsup2 opens images with PIL without
  `exif_transpose`, so it captions and OCRs portrait phone photos sideways (a thumbsup2 bug).
- **Videos are described**: BLIP captions frames at 10%, 50% and 90% (distinct captions joined
  with "; "), EasyOCR reads the middle frame. thumbsup2 describes images only.
- **Photos over ~16.8 MP are decoded subsampled** (by powers of two) to bound memory.
- **Search by place**: a filter, and a place named in passing ranks the files taken there
  higher (`HybridSearch.search`'s `favoured`: one more list fused by RRF among those found, in
  their fused order; none added). thumbsup2 has neither. The index is thumbsup2's, and a query
  that names no place the library has pictures from is ranked as thumbsup2 ranks it. Places are
  not a keyword field: MiniSearch's prefix and fuzzy matching would make "car" find Carolina
  and "whales" Wales.
- **Text detail** (`TextDetail`): EasyOCR's detector works at up to 2560 px (its default, so
  thumbsup2's). On the phone (Snapdragon 8 Gen 1) CRAFT takes 8.5 s at 1280×960, 22 s at
  1920×1440 and 52 s at 2560×1920, so the default "Balanced" keeps 2560 for screenshots,
  downloads, messages and other pictures (all within it at their own size anyway) and uses 1280
  for camera photos and videos. "Full" is thumbsup2 throughout; "Fast" is 1280 throughout.
- **Tiled text detection** (an implementation detail, not a difference): CRAFT's activations
  take ~1.3 KB per input pixel (6.2 GB at 2560×1920, which got the process killed). An input
  above `maxTilePixels` (2.7 MP on phones with 7 GB+, 1.2 MP below) is cut into tiles, each a
  core plus a 256 px margin of context on every side that is not the image's edge; each output
  pixel comes from the tile whose core holds it. `EasyOcr.tilePlan` picks, among layouts of up
  to 16×16 tiles that fit the budget, the one computing the fewest pixels (a 2560×1920 canvas:
  3 strips, 1.4× the work). `CraftTilingTest`: with a 256 px margin the score maps are
  identical to running whole (difference 0.0) on all 42 test images cut once or twice, so the
  text is too; with 192 px two images read differently, with 128 px three.
- **On a Snapdragon's GPU, text detection runs in float16** ("Read text on the GPU", on by
  default where there is one; see "The GPU" below). Its scores differ from float32's by about
  one part in a thousand, which can move a box edge by a pixel where a score sits at a
  threshold. On an S22 Ultra it reads 41 of the 42 test images exactly as EasyOCR does; the
  42nd, a bus sign read as garble anyway, loses one stray "M". Off, or on other phones, OCR is
  thumbsup2's to the bit.
- **Searches can be narrowed to dates and to a tab before ranking** (`HybridSearch`'s
  `accept`): "beach last summer" ranks among the files taken last summer, so the best 200 are
  the best of those, not the best of the whole library cut afterwards. Keyword scores still
  weigh words by the whole index. thumbsup2 has neither dates in queries nor tabs.
- **A request a query opens with is not searched for** (`QueryRequest`): "show me photos of
  dogs" searches for "dogs", where thumbsup2 would ask every document for all five words (its
  keyword search wants them all); "videos of dogs" and "dog videos" search for dogs among
  videos. A query that makes no request ("photo booth", "videos", "find my") is searched as
  typed, as thumbsup2 does. Asking for videos narrows a search, so it is read only from words
  that cannot mean anything else ("videos of", "from", "taken", "showing", or "videos" ending
  the query): "video on youtube" and "clip on earrings" are searched as typed.
- **Keyword scores use a field's exact mean length**, not MiniSearch's running average, whose
  last bits depend on the order documents were added and removed in: the app's index changes
  file by file, and must score the same as one built afresh. Scores agree with thumbsup2's to
  about 1e-15 (`MiniSearchTest`); only scores tied to that precision could order differently.
- **Meaning-only results need cosine similarity ≥ 0.2** (`SearchService.MIN_SIMILARITY`).
  thumbsup2 always lists its 200 nearest embeddings, so every query would "match" a whole small
  library; MiniLM scores related captions 0.3-0.6, unrelated ones ~0.1. Order is unchanged.
- **A part switched on later is filled in**: a description stores caption and OCR separately
  (null = not made), so turning OCR on later reads text in already-captioned photos. thumbsup2
  keeps whatever its first run made.
- **Manual caption**: EXIF ImageDescription, then XMP dc:description / dc:title / xmp:Label, or
  a video's title (thumbsup2 also reads IPTC, which phones do not write). Text is read as UTF-8,
  as exiftool reads it for thumbsup2 (Latin-1 when it is not valid UTF-8).

### Model files

`tools/export_models.py` exports ONNX graphs with **no weights inside**: every initializer is an
ONNX external-data reference (file, offset, length) into the official weight file, found by
matching tensor bytes. So the app ships ~600 KB of graphs (`app/src/main/assets/models/`) and
downloads the publishers' own files (`models.json`: URL, size, SHA-256):

| Key | File | From | Size |
|---|---|---|---|
| `blip` | `model.safetensors` | HuggingFace `Salesforce/blip-image-captioning-base` @ 4c26dfe (the safetensors conversion transformers 5 loads) | 990 MB |
| `minilm` | `model.safetensors` | HuggingFace `sentence-transformers/all-MiniLM-L6-v2` @ c9745ed | 91 MB |
| `craft` | `craft_mlt_25k.pth` (in a zip) | EasyOCR GitHub release pre-v1.1.6 | 83 MB |
| `english_g2` | `english_g2.pth` (in a zip) | EasyOCR GitHub release v1.3 | 15 MB |

`craft_fp16.onnx` is CRAFT computing in float16, for a GPU (`export_craft_half`, with
onnxconverter-common): the same references into the official float32 file, each weight cast to
float16 inside the graph (ONNX Runtime folds the casts when it opens the session), the image in
and the scores out float32. 67 KB; no weights are duplicated.

BLIP is two graphs: `blip_vision` computes the image's cross-attention keys and values for all
12 decoder layers once; `blip_decoder` is one greedy step (one token, the self-attention cache,
those cross keys/values), with an empty cache at the first step. Published BLIP ONNX exports
recompute the image's cross-attention at every token, about 3× the work.

ONNX Runtime opens each graph from memory with `session.model_external_initializers_file_folder_path`
pointing at the model's folder (`Onnx`). The vocabulary (`vocab.txt`, shared by BLIP and MiniLM)
ships in assets.

## Android app

### Data (`app/.../data`)

- `MediaScanner` reads every image and video MediaStore has on all external volumes (the whole
  library, not just what arrives after install). Displayed size swaps width/height for 90°/270°
  orientation. Each file gets a `Folder` (`Categories.folderOf`, rules on the relative path and
  name: screenshots, messaging apps, downloads, camera, other) and an `aiKey` =
  sha1(name, size, mtime), so moved files keep their descriptions (thumbsup2 keys its cache the
  same way).
- Room (`Database.kt`, version 5; migrations kept and tested from every version
  (`MigrationTest` on the device, the schemas in `app/schemas` its assets)): `media` (the
  library copy), `display_aspect` (learned aspect ratios), `ai_descriptions` (keyed by `aiKey`;
  caption/ocr nullable per part; `error` marks a file that opened but could not be decoded, ran
  out of memory being read, or that the models failed on in three runs (`modelFailures`), not
  tried again until it changes or "Retry failed"; `lastModelFailure`, when the models last
  failed on it, puts it last in every pass; `describedAt` is later at every write, which the
  search index relies on), `ai_embeddings` (`aiKey`, model, vector, hash of the text embedded).
  A description and its embedding are written in one transaction (`saveDescribed`), reset in
  one (`forget`, and "Retry failed", `clearFailures`), and an embedding made later is saved
  only over the text it was made from (`saveEmbeddingIfCurrent`). `locations` (by `aiKey`:
  where the file was taken, latitude and longitude, both null when it says nowhere; no row
  until it is read; version 5).
- `MediaRepository.sync()` diffs MediaStore against the table (upsert changed, delete gone);
  run at start, on MediaStore change notifications (debounced) while open, and by the workers.
- `SettingsStore`: DataStore preferences, one `Settings` value.

### Tabs

Camera, Video, Screenshots, Messages, Downloads, Other (hideable), All (off by default). Video
is a type, every video; the others are folders, and show their videos too unless "Videos in
folder tabs" is off.

### Thumbnails

`Layouts.justified` (core): rows break where dynamic programming over the whole list minimizes
the summed squared relative difference of row heights from the target; every row but the last
fills the width exactly at its images' own aspect ratios, so nothing is cropped or letterboxed.
The last row keeps the target height, left-aligned. A row that would exceed 2.5× the target is
capped and centred. `Layouts.grid`: N square cells per row, images fitted inside. MediaStore
reports a video's stored size, wrong for non-square pixels; when a decoded thumbnail's aspect
differs from the cell's by over 4% it is recorded (`display_aspect`, per id and modification
time) and the layout uses it. Pinch changes
row height or column count. Thumbnails are MediaStore's own (`ContentResolver.loadThumbnail`,
upright, video frames included) through a Coil fetcher (`MediaThumbFetcher`), requested in
size buckets so the memory cache is shared across zoom levels. The header (search field, AI
button, tabs) slides away on scroll down. A fast scroller shows the month.

### Screens

`AppRoot` (`MainActivity`) shows the gallery, and over it the viewer, settings or models
screen. The gallery stays composed underneath (undrawn, out of the semantics tree, touches
blocked by the covering screen), so closing one returns to the same scroll position, tab and
search results. Settings ends with About: the version, and *Credits and licences*
(`CreditsScreen`): the list AboutLibraries' Gradle plugin gathers at build time from every
dependency's published licence, with entries of our own (`app/aboutlibraries`, by tag): the data
and models (GeoNames, the Umm al-Qura table, BLIP, CRAFT, EasyOCR, MiniLM, BERT's vocabulary),
then the code `core/` reproduces from other projects (MiniSearch, Pillow's and PyTorch's resizing,
OpenCV's imgproc, Hugging Face's tokenizers and transformers), each with its licence's text and
copyright line, and the MIT and BSD libraries' own licences in place of SPDX's templates (ONNX
Runtime, its QNN execution provider, protobuf); a licence opens in the app, not on the web; then
the third-party notices of the native libraries' own parts (`assets/licences`,
`tools/fetch_notices.py`: ONNX Runtime's and its QNN execution provider's ThirdPartyNotices, which
are in their repositories and not their AARs, and the NOTICE.txt in Qualcomm's QNN runtime AAR,
which Gradle does not package), reflowed to the screen. The list keeps its place while a notice
is read, and Settings its scroll while Credits is shown (`AppRoot` holds it).

### Viewer

`HorizontalPager` over the list the thumbnail was opened from (tab or search results); photos
in Telephoto's `ZoomableAsyncImage` (sub-sampled tiles for huge images), videos in Media3
ExoPlayer started on tap. Share, edit/open with (intents), move to trash
(`MediaStore.createTrashRequest`, API 30+; delete with the RecoverableSecurityException flow on
29), details sheet (dates, file, AI description, OCR text, own caption, why a search hit matched),
"Describe again". A long press on a thumbnail opens `MediaSheet`: the file's name and folder
(selectable), open, share, delete, edit, open with, copy name, and the same details. The viewer
and the sheet share `MediaActions` (the intents; the trash flow, after which the library is
synced) and `MediaDetails`.

### AI in the app

- `ModelManager`: models.json from assets; weights in `Android/data/<app>/files/models/<key>/`
  (removed with the app; reachable over USB, so `push-models.bat` can install them); resumable
  HTTP download (Range), zip member extraction, SHA-256 check, `.verified` marker; files copied
  in without a marker are hashed by `verifyCopied` (app start and resume, and each worker run).
  The marker records the hash, length and modification time, so a file copied over a verified
  one is checked again, and so is one copied over a file that failed its check (a marker from
  before, the hash alone, is trusted once and rewritten; an unreadable one counts as none). A download
  checks a complete file in place before fetching (unless it failed already). A lock per model
  keeps checking, downloading and deleting off the same file; `verifyCopied` waits for a check
  in progress (its caller gets the verdict) and skips a model being downloaded. Any download
  failure marks the model failed (the worker retries only network errors). The app
  creates its `models` folder itself: a folder adb creates there belongs to the shell user and
  the app cannot read what is copied into it, so the copy scripts open the app first.
- `AiEngine`: opens only the sessions a pass needs (BLIP + MiniLM for captions; CRAFT,
  recognizer + MiniLM for text), describes one file at a time (serialized), saves description
  and embedding together (re-embedded when OCR text arrives: the embedding records a hash of
  its text), `release()` closes the models. `describe` reports what it did (`Outcome`). Reading
  the file failing (or running out of memory reading it) is the file's fault: the error is
  saved, unless the file cannot be opened at all just now (deleted, card removed), which leaves
  it for the next run. Opening or running the models failing (running out of memory included,
  the image being bounded by then; one `modelWork` wrapper) makes nothing and notes the time,
  keeping the file's own caption so it is searched meanwhile (`noteModelFailure`). Whose fault
  it is shows over each pass of the run (`DescribePass`, tested in `DescribePassTest`): a file
  the models failed on is a suspect. Once the models are seen to work on that pass's input, each
  suspect is tried once more: described now, its failure was the moment's (a passing shortage of
  memory); failing again, it is blamed (counted, `recordModelFailure`, at most once a run; the
  third sets it aside). The pass describing another file shows the models work; so does a probe
  (`modelsStillWork`), run when six failures come with none described between or a pass ends
  with suspects: the same models (through the same code as `describe`) on real files they
  handled before, up to three, newest first, passing over any that cannot be read now. For
  captions any captioned file will do (BLIP sees every picture at 384x384); for text, one read
  at the suspect's detail or more (more needs more memory); for MiniLM, text of the most it
  reads. If the probe fails, or the models cannot even be opened (`ModelsUnavailable`: removed
  during the run, say; no file is noted for it), the models are failing just now: the run
  stops, blaming none, and is retried later. If there is nothing to probe on, the pass stops
  undecided, blaming none; the run goes on and is retried later, the suspects coming last, so
  other files describe first and become something to probe on. A count applies only while the failure is current (no
  part made, nor the description reset, since it was noted); any part made resets it. Files the
  models failed on come last in every pass, so files that always fail cannot hold one up. The
  models are closed after each failure, giving back their memory. MiniLM failing is never the
  file's and not counted (its input is text, and retrying an embedding takes milliseconds): the
  description is saved without an embedding (any older one, of other text, removed with it),
  and an embedding pass before and after the describing passes (`AiDao.withoutEmbedding`,
  `embedMissing`) makes it: the second pass takes those saved without one during the run. A
  failed embedding also closes the models, so a shortage of memory does not carry over to the
  next file. MiniLM failing even on its probe holds up no captions or text: the run goes on and
  is retried later for the embeddings. A video read only for its text is decoded at its middle
  frame alone (or, if that one cannot be extracted, the others). A file on record as failed is skipped, so the text pass does not
  retry what the caption pass just failed on (`DescribeFailureTest`).
- `MediaDecoding`: `ImageDecoder` (EXIF orientation applied, software bitmap, unpremultiplied,
  subsampled above 16.8 MP) to `RgbImage` in strips, stored values (see above); video frames by
  `MediaMetadataRetriever.getScaledFrameAtTime`.
- `SearchService`: a `MiniSearch` over all files (fields as thumbsup2's) plus the embeddings,
  brought up to date by the first search after files or descriptions change (an open search
  reruns at most every 5 s while describing changes them every couple of seconds). A refresh
  reads every file's `SearchSignature` (key, name, folder, when its description was written,
  which text its embedding is of: a few dozen bytes), compares a 64-bit hash of it with what
  it indexed, and reads text and embeddings only for the files that changed. A write during
  the reads changes the signature again, so the next refresh mends what this one read in
  between. Refreshes run one at a time in the service's own scope (a search cancelled by the
  next keystroke leaves the refresh it waited for running) and change the index only under its
  lock. Equal scores break on date, then media id, so a long-kept index orders exactly as a
  fresh one. `SearchServiceTest` (Robolectric, Room in memory): through 25 rounds of random
  changes, with tied dates and tied meanings, it answers exactly as a fresh index over the same
  database. The view model invalidates on file changes in the same flow that reruns the search,
  before it. Queries are embedded with a MiniLM session kept for search, freed (about 90 MB)
  when the app leaves the screen (`ProcessLifecycleOwner` stop, so not on rotation) or MiniLM
  is removed. `search` takes an `accept` (by media id) that narrows the candidates before
  ranking (`HybridSearch`): the view model passes the set of files the search may find (taken
  on those days, in this tab, videos when asked for, and only files it knows of, so files the
  index has and its list has not yet cannot take places among the best), worked out once per
  search, off the main thread like the whole search flow. "Only in this tab" narrows nothing
  in the All tab.
- **Dates in a query** (`core/search/DatePhrases`, `DatePhrasesTest` at a fixed today): the
  first date phrase is taken out of the query and becomes a filter on the day each file was
  taken (`sortDate` in the phone's time zone); the rest is searched as words. Rules are tried in
  order, most specific first: year ranges and bounds ("2019-2021", "before 2010"; "since 2020"
  and "after 2020" are open-ended, so a year still to come is an empty date, not that year), days (ISO, 3/5/2024 in the locale's order, "march 5th 2023", "march 5": the latest
  one not after today), today/yesterday/last night, counting back ("the past 3 months", "two
  weeks ago", "a few days ago": 2 to 4), the calendar ("last week" by the locale's first day,
  "this weekend", "last year"), the latest of something yearly ("last summer": the latest over;
  "this march": the one under way, else this year's), with a year ("summer 2023": the season's
  months in that year; "christmas 2022"), decades, a year (not one still to come: "2048" is
  likelier the game), then every year ("in july", "at christmas"). Seasons are whole months,
  turned round in the southern hemisphere (by the locale's country). Holidays: Christmas (24-26
  December), its Eve and Day, New Year (31 December and 1 January), its Eve and Day, Halloween,
  Valentine's Day, the Fourth of July, Easter (Good Friday to Easter Monday, Gregorian computus),
  Thanksgiving (the United States' Thursday to Sunday, Canada's long weekend), Mother's Day
  (Britain's and Ireland's Mothering Sunday), Father's Day (Australia's in September), St
  Patrick's Day; and from other calendars (`Calendars.kt`, `CalendarsTest` against published
  dates): Diwali, Holi, the Lunar New Year, the Mid-Autumn Festival, Ramadan, Eid al-Fitr and
  Eid al-Adha ("eid" is both), Hanukkah, Passover, Rosh Hashanah, Yom Kippur. Those that follow
  the moon use the true moon and sun (`Calendars.kt`; Meeus, Astronomical Algorithms: new and
  full moons, ch. 49, within a minute of the almanacs; the sun's longitude, ch. 25, to 0.01°;
  dynamical time to the clock's by ΔT), not dates that stand for them, since a new moon falls
  between such a date and the true moment once every decade or two and moves a festival a
  month. The Lunar New Year and the Mid-Autumn Festival (the 15th day of the 8th month) come from
  the Chinese calendar's own rules (`LunisolarCalendar`: months begin on the day of a new moon;
  the month of the winter solstice is the 11th; a year of 13 months leaps its first month without
  a major solar term, so 2034's comes after a leap 11th month; months 1 to 10 of a year, worked
  out once each), in China's time (`ChineseCalendar`: UTC+8 from 1929, Beijing's mean time
  before): every year 1900-2099 as the Hong Kong Observatory's tables have it but 1978's
  Mid-Autumn Festival, whose month's new moon came 9 minutes after midnight and was kept a day
  earlier (its span still holds that day). Tết is Vietnam's New Year, the same rules in its time
  (`VietnameseCalendar`: UTC+7 from 1968), a day or (1985) a month from China's. Lakshmi Puja,
  Diwali's night, is on the new moon's tithi ending Ashvin, the lunar month in which the sun
  enters sidereal Libra, and Holika Dahan (Holi the day after) on the full moon's in Phalguna,
  in which it enters sidereal Pisces: the true ingress, the sun's longitude from the mean
  equinox less the Lahiri ayanamsa (measured from it too). Of the evenings a tithi spans, Lakshmi
  Puja is the later when the new moon outlasts that sunset in New Delhi by a ghati (24 minutes);
  Holika Dahan is after Bhadra, the full moon tithi's first half (taken as ending 12 hours before
  the full moon): that night if it ends by about 3 a.m., else the next evening. Against Drik
  Panchang: Lakshmi Puja 2024-2036 and 2039, Holika Dahan 2019-2026, 1983 and 2002 exactly; 2037's
  new moon came a minute after sunset, and Drik keeps the later evening by rules about the
  tithi's start this does not reckon (the span holds both).
  Islamic months are Saudi Arabia's Umm al-Qura calendar, an official table and not a rule a
  computation reproduces: its month lengths from 1 Muharram 1365 (5 December 1945) to 1500
  (2077) are 1632 bits in `UMM_AL_QURA` (`tools/make_umm_al_qura.py`, from hijridate), and
  outside them the month begins the day after a new moon before 18:30 in Mecca, else the day
  after that (that rule missed 333 of the table's 1632 months). The Hebrew calendar is its arithmetic
  (Dershowitz and Reingold): Rosh Hashanah, Passover and Hanukkah agree with convertdate for every
  year 1900-2100. Each takes its festival's days, with a day's leeway where countries keep it on
  different days, and the evening before for those that begin at sunset. A Gregorian year can
  hold two of an Islamic holiday. A word that is
  also an ordinary word (a month, season, holiday, weekday, a bare "90s") is a date only with
  something that makes it one ("last", "in", a year) or when it is the whole query: "christmas
  tree" searches for Christmas trees; "may" is never a date alone. Words leading into the phrase
  ("taken in", "from") go with it, and the request the query opens with is not searched for
  (`QueryRequest`, read across the phrase, so "videos from last summer" lists last summer's
  videos): "show me photos of dogs from last summer" searches for "dogs" ("show me", "find", or a
  word for pictures or videos followed by "of", "from", "on"...; "photo booth" stays). The
  rules' patterns are compiled once (the companion's `RULES`); a query is read on the clock's
  day and the locale, and read again when the day moves on (at midnight, and when the app comes
  back: a sleeping phone's timers run late). With no words left to search for, every file
  allowed is listed, newest first (`SearchHit` without a result), which works before anything is
  described.
  The filter's label ("summer 2026: Jun 1, 2026 – Aug 31, 2026") shows as a chip above the
  results; its × searches the query as words instead (`GalleryViewModel.dismissDate`, until the
  query changes).
- **Places.** Where each file was taken is read once (`Places.scan`, in the background after
  every sync, newest first, 100 files per write; `MediaLocations`: a photo's EXIF GPS through
  `ExifInterface`, a video's ISO 6709 location tag; both need ACCESS_MEDIA_LOCATION and
  `MediaStore.setRequireOriginal`, without which Android blanks them, so nothing is read
  without it, and a scan without it forgets the locations read before: the user has withheld
  them; 0,0 is a GPS without a fix; a file that could not be read gets no row and is read
  again when the app next starts, not at every scan, since most stay unreadable). `core/places/Gazetteer` turns a point into words: the nearest of GeoNames'
  places of 1000 people or more within 60 km (`places.bin`, 171,749 places, 5.4 MB,
  `tools/make_places.py`; a 1° grid, the cells a degree of latitude either side and as many of
  longitude as 60 km spans there, so a look-up reads a few hundred and finds Longyearbyen's
  places two cells away at 78° north), its admin1 region and country, and the most populous
  place of the same country within 30 km when it has 100,000 people or more (a suburb's
  pictures are its city's: Boulogne-Billancourt's are Paris's; El Paso's are not Ciudad
  Juárez's), labelled with the city only when it is in the same region. The gazetteer is read
  (about 15 MB) only once a file says where it was taken. `Places.snapshot` holds each located
  file's place (files of the library only: `locations` keeps the rows of files gone, as
  descriptions are kept), how many files say where (some nowhere near a place, which Settings
  counts), and every name (normalized: lower case, accents and punctuation gone, apostrophes
  dropped as in typing: "st johns")
  to the files taken there and to a label (`PlacePhrases.Known`, which also says whether it
  names a country): the place, the city, the region, the country, and the country's other
  names ("usa", "uk", "holland") where the library has pictures from it; two places of one
  name read as the name as GeoNames writes it (Holland, Michigan and the Netherlands' "holland"
  read "Holland"). It is brought up to date after every scan (every 10 s while a large
  library is read), each file's place looked up once, and sent only when it changed.

  A query is read (`core/search/SearchReading`, `SearchReadingTest`) for its date, then its
  place in what is left (`PlacePhrases`), among the names the library has: the longest name
  (up to five words) that follows "in", "at", "near", "from", "around", "to" or "visiting"
  ("the" between only for a country: "in the uk"), or that has nothing with it but a request
  and filler ("hawaii photos", "my trip to tokyo"); so "nice beach", "dinner in a nice
  restaurant" and "in the nice weather" are not Nice. The words around it are read for a request
  as a date's are ("videos in paris" asks for videos). A date phrase takes the "in" after it
  ("beach last summer in hawaii" leaves "beach hawaii"), so with a date and no place the place
  is read first and kept when the date still reads in what is left; a date that is one only on
  its own ("christmas in paris") is read again once the place is out; and a month stays a month
  over a town of its name ("in march"). The place becomes a filter on the files taken there,
  with a chip and a × (`dismissPlace`) as the date's. A place named in passing ("beach hawaii",
  `SearchReading.named`) ranks the files taken there higher among what the words find, with a
  chip of its own ("Hawaii, United States first") whose × ranks without it: the name may be a
  word ("nice beach"), and no list of words tells which. Words left beside a date or a place
  that are nothing to search for ("my trip", "photos of me") are one list,
  `QueryRequest.FILLER`.
  Settings → Search → *Places* shows whether the permission is given and how many files say
  where; the app asks for it with the media permissions, once by itself after an update
  (`Settings.locationAsked`), and again from there (the app's system settings once Android no
  longer asks).

### Hardware

`Hardware.memory`: the AI needs a phone sold with 6 GB: the smallest usual size at least what
Android reports, so one reporting more than 4 GiB (6 GB phones report 5.3-5.7, 4 GB ones about
3.6), which keeps the notice and the decision the same. Describing holds about 2 GB at once,
and below that Android kills the app or its neighbours mid-run. A phone with less is told so
once (`Settings.weakPhoneNoticeShown`, read from the stored settings: `weakPhoneNotice` waits
for them), and `AiEngine.allowed` is false: no describing (`ready`), no model downloads (the
screen offers none; the worker refuses), no search by meaning (`SearchService`'s
`meaningAllowed`). Keyword, date, place and folder search work without the AI. "Use the AI
anyway" (`aiOnWeakPhone`) lifts it; turning it off stops a run under way, and the AI dialog
offers Stop whenever one is. The app ships for 64-bit processors only, so that need is met
wherever it installs.

### The GPU (Snapdragon)

The text detector (CRAFT) runs on a Snapdragon's Adreno GPU when there is one and "Read text on
the GPU" is on (`Settings.gpuText`, default on). Looking for it loads Qualcomm's libraries, so
it is looked for only when wanted: by describing, or by the Settings screen, which shows where it
stands (`AiEngine.Gpu`: none here, found, failed its check, or failed while reading this run)
and greys the toggle out where there is none.

- **How**: Qualcomm's QNN execution provider, a plugin to ONNX Runtime
  (`com.qualcomm.qti:onnxruntime-android-qnn` + `qnn-runtime`), registered once by
  `SnapdragonGpu` (SoC maker "QTI" or hardware "qcom"; elsewhere, and in the x86 build, there is
  none). QNN offers one device for all its backends; `backend_type=gpu` picks the GPU. Its GPU
  backend runs on OpenCL, a vendor library an app may load only when the manifest declares it
  (`<uses-native-library android:name="libOpenCL.so" android:required="false">`; without it,
  `QNN_COMMON_ERROR_PLATFORM_NOT_SUPPORTED`). The Hexagon NPU and DSP backends (about 200 MB)
  are left out of the APK; what remains adds about 21 MB.
- **Float16** (`craft_fp16.onnx`): the plugin has no precision option for the GPU, so the graph
  itself computes in float16. Measured on a Galaxy S22 Ultra (Snapdragon 8 Gen 1, Adreno 730)
  by `GpuProbe`, the phone warm (thermal status 1-2, which describing reaches within a minute):

  | Input | CPU (float32) | GPU (float16) | Compiling a shape | GPU memory |
  |---|---|---|---|---|
  | 0.3 MP | 2.4 s | 0.48 s | 2.7 s | 0.6 GB |
  | 0.6 MP | 4.8 s | 0.95 s | 2.8 s | 1.1 GB |
  | 1.2 MP | 9.7 s | 1.73 s | 2.9 s | 2.1 GB |
  | 1.6 MP | 12.4 s | 2.28 s | 3.0 s | 2.8 GB |
  | 2.6 MP (a whole screenshot) | 20.3 s | 3.6 s | 3.4 s | 4.4 GB |

  Float32 on the same GPU is no use: 2.2× the CPU while the phone is cool, 1.15× once it warms
  (its clock is cut hard at thermal status 1), at 3.5 KB a pixel: 5.5 GB for 1.6 MP, which got
  the process killed. Float16's scores differ from float32's by about one part in a thousand.
- **Fixed shapes**: QNN needs every dimension fixed, so each tile shape gets its own session
  (`setSymbolicDimensionValue`), compiled in about 3 s. A canvas is cut for the GPU to
  `acceleratedMaxTilePixels`, every tile one size (`EasyOcr.windows`: edge tiles reach further
  in, which only adds context), so one session serves a whole canvas; cut the CPU's way, edge and
  middle tiles differ and would each need one. Its layout is the one computing the fewest
  pixels counted that way (a 2560×1920 canvas at 2.7 MP: 2×2 tiles of 1536×1216, 7.5 MP, not
  three strips of 1344×1920, 7.7 MP in sessions 40% larger). The GPU takes the canvas when that tile is 0.6 MP
  or more (where compiling pays for itself on one image), or 0.2 MP once the shape has been
  seen before (a camera's photos, a phone's screenshots share one); otherwise the CPU runs it, cut
  to its own budget. Sessions of recent shapes stay open while their pixels add up to
  `acceleratedCachePixels`, the least recently used closed before another compiles, since each
  holds its activations while open. Both budgets are `AiEngine.gpuPixels`: 2.7 MP on 12 GB phones
  (a screenshot whole, as on the CPU), 1.6 MP on 8 GB ones, 0.8 MP below. The CPU then sees only
  small canvases, so its session stays small too. `CraftTilingTest.acceleratorTilesComputeTheSameScores`:
  the GPU's way, run with the float32 graph, gives the whole canvas's score maps exactly, one
  session compiled per canvas.
- **Checked, and dropped on failure**: once a process, `acceleratorAgrees` runs a made-up input
  on both, within 0.01 (scores run from 0 to 1, EasyOCR's thresholds are 0.4 and 0.7; float16
  moves them by about 0.001, a driver computing a graph wrongly, as Adreno does BLIP's encoder,
  by the order of the scores); one computing differently is not used for the rest of the
  process (`AiEngine.Gpu.DIFFERS`). The check is noted only once answered: one that cannot run
  (short of memory a moment, say) leaves the CPU reading text and is tried again at the models'
  next opening, three such failures giving the GPU up for the run. An exception on the GPU while
  reading drops it for the rest of the process and the CPU takes over the same input.
- **Not on the GPU**: BLIP's vision encoder computes wrongly there (largest difference 10 on
  outputs up to 8) and slower (3.8 s against 1.2 s). The recognizer and MiniLM have inputs of
  varying length, so a session per length, for models that take milliseconds. QNN's EP context
  cache (a compiled shape saved) is 84 MB per shape and loads in 1 s instead of 3: not worth the
  storage.
- **Tests**: `EasyOcrTest.fixedShapeSessionsReadTheSameText` runs the float16 graph through
  fixed-shape sessions on the desktop CPU (EasyOCR's text on all 42 images);
  `OnDeviceModelTest.gpuReadsAsEasyOcrDoes` runs it on the phone's GPU (at most a tenth of the
  images read differently, each by a character or two; over all 42 the GPU took 98 s against
  the CPU's 161 s, compiling included, the large images 2.2-2.5× faster whole).

### Background work (`work/Workers.kt`)

| Work | When | Constraints |
|---|---|---|
| `DescribeWorker` (unique "describe") | AI button; app start if "Describe the rest when the app opens"; after a model download; after new media | user: none; automatic: battery not low, charging if set |
| `NewMediaWorker` (unique "media-trigger") | MediaStore content-URI trigger (images + video, 5 s update delay, 1 min max), re-armed after each run; armed while "Describe new photos as they arrive" is on | none |
| `ModelDownloadWorker` (unique "download-models") | Models screen | network (unmetered if "Only over Wi-Fi") |

Describing runs in two passes: captions (and embeddings) for every file newest first, ~2 s
each, so the whole library is soon searchable; then the text in pictures, 10-60 s each, ordered
screenshots, downloads, messages, other, camera (`AiDao.withoutText`); files the models failed
on last in each. Before and after them, the embeddings MiniLM failed to make are made
(milliseconds each). It saves per file, so stopping loses at most one; progress is reported at
most once a second: files done and in all, and the pass with its own count (`KEY_PHASE_DONE`,
`KEY_PHASE_TOTAL`, reported as each pass begins, since a text pass's first file can take a
minute). The gallery shows the pass, its count and the time it has left
(`GalleryViewModel.passSecondsLeft`: the files left at the rate of the pass's last five
minutes, since passes go at very different speeds and the phone slows as it warms). With more than
three files (or user-started) it asks to run in the foreground with a progress notification and
a Stop action (`mediaProcessing` on Android 15+, `dataSync` before); Android may refuse that from
the background, and then it runs as a normal job (10-minute limit, resumed by the next trigger).

### Permissions

READ_MEDIA_IMAGES/VIDEO (READ_EXTERNAL_STORAGE ≤ 32); READ_MEDIA_VISUAL_USER_SELECTED declared
so a "selected photos" grant is detected and the app asks for full access; ACCESS_MEDIA_LOCATION
(where pictures were taken, for search by place); POST_NOTIFICATIONS;
INTERNET (model download); FOREGROUND_SERVICE_DATA_SYNC / MEDIA_PROCESSING.

## Build

AGP 9.4.1, Kotlin 2.4.21, Gradle 9.8.2 (as the user's other Android apps), compileSdk 37,
minSdk 29, targetSdk 36. One APK per ABI (arm64-v8a, x86_64): ONNX Runtime's native library is
~35 MB each. Version in `gradle.properties` (`appVersion`, `appVersionCode` checked to agree).
Licences: AboutLibraries' strict mode fails the build for a dependency under a licence not
allowed in `app/build.gradle.kts` (Apache 2.0, MIT, BSD-3-Clause, the data's, models' and ported
code's, the MIT and BSD libraries' own with their copyright lines, and Qualcomm's AI Hub Model
License for its QNN runtime alone), or without a licence (`requireLicense`); BOMs, which ship no
code, are left out. An entry in `app/aboutlibraries` adds a library's real licence to its POM's
(the plugin merges), and the screen shows only licences with their own text (`creditsList`), so
an MIT library shows its copyright line, not SPDX's template; `CreditsTest` fails for a shown
licence that is a template. `checkNotices` (before every build) fails it when the native
libraries' notices are not those of the versions built with. The generated list is found by its
R id, so resource shrinking keeps it (renamed) without a keep rule.
