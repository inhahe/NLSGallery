# Lithic Gallery: design

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
| Hybrid | BM25 top 200 + cosine top 200, RRF k=60, best 500; semantic only if description or OCR field searched | `HybridSearch` | by construction (small) |

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
- Room (`Database.kt`, version 4; migrations kept and tested from every version
  (`MigrationTest` on the device, the schemas in `app/schemas` its assets)): `media` (the
  library copy), `display_aspect` (learned aspect ratios), `ai_descriptions` (keyed by `aiKey`;
  caption/ocr nullable per part; `error` marks a file that opened but could not be decoded, ran
  out of memory being read, or that the models failed on in three runs (`modelFailures`), not
  tried again until it changes or "Retry failed"; `lastModelFailure`, when the models last
  failed on it, puts it last in every pass; `describedAt` is later at every write, which the
  search index relies on), `ai_embeddings` (`aiKey`, model, vector, hash of the text embedded).
  A description and its embedding are written in one transaction (`saveDescribed`), reset in
  one (`forget`, and "Retry failed", `clearFailures`), and an embedding made later is saved
  only over the text it was made from (`saveEmbeddingIfCurrent`).
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
search results.

### Viewer

`HorizontalPager` over the list the thumbnail was opened from (tab or search results); photos
in Telephoto's `ZoomableAsyncImage` (sub-sampled tiles for huge images), videos in Media3
ExoPlayer started on tap. Share, edit/open with (intents), move to trash
(`MediaStore.createTrashRequest`, API 30+; delete with the RecoverableSecurityException flow on
29), details sheet (dates, file, AI description, OCR text, own caption, why a search hit matched),
"Describe again".

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
  the models failed on is a suspect, blamed (counted, `recordModelFailure`, at most once a run;
  the third sets it aside) once the models are seen to work on that pass's input. The pass
  describing another file shows it; so does a probe (`modelsStillWork`), run when six failures
  come with none described between or a pass ends with suspects: the same models on a real file
  they handled before, of the same kind (photo or video, and for text the same folder, so read
  at the same detail), or, for MiniLM, text of the most it reads. If the probe fails too, or
  there is nothing to probe on, the models are failing just now (memory, say): the run stops,
  blaming none, and is retried later. A count applies only while the failure is current (no
  part made, nor the description reset, since it was noted); any part made resets it. Files the
  models failed on come last in every pass, so files that always fail cannot hold one up. The
  models are closed after each failure, giving back their memory. MiniLM failing is never the
  file's (its input is text): the description is saved without an embedding (any older one, of
  other text, removed with it), and an embedding pass before and after the describing passes
  (`AiDao.withoutEmbedding`, `embedMissing`) makes it; MiniLM failures are counted like the
  others, but only stop the description being tried (after three), never set it aside. A file on
  record as failed is skipped, so the text pass does not retry what the caption pass just failed
  on (`DescribeFailureTest`).
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
  is removed.

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
most once a second. With more than
three files (or user-started) it asks to run in the foreground with a progress notification and
a Stop action (`mediaProcessing` on Android 15+, `dataSync` before); Android may refuse that from
the background, and then it runs as a normal job (10-minute limit, resumed by the next trigger).

### Permissions

READ_MEDIA_IMAGES/VIDEO (READ_EXTERNAL_STORAGE ≤ 32); READ_MEDIA_VISUAL_USER_SELECTED declared
so a "selected photos" grant is detected and the app asks for full access; POST_NOTIFICATIONS;
INTERNET (model download); FOREGROUND_SERVICE_DATA_SYNC / MEDIA_PROCESSING.

## Build

AGP 9.4.1, Kotlin 2.4.21, Gradle 9.8.2 (as the user's other Android apps), compileSdk 37,
minSdk 29, targetSdk 36. One APK per ABI (arm64-v8a, x86_64): ONNX Runtime's native library is
~35 MB each. Version in `gradle.properties` (`appVersion`, `appVersionCode` checked to agree).
