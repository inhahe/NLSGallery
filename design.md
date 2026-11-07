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
| OCR | EasyOCR 1.7.2 `Reader(['en']).readtext(np.array(rgb))`, joined by spaces | `EasyOcr`: CRAFT score maps → `CraftBoxes` (connected components, dilation, OpenCV's hull and rotating calipers, `boxPoints`) → `TextGrouping` → `Crops` (OpenCV bilinear, perspective warp) → `Recognizer` (Pillow bicubic, CTC greedy, contrast retry) | `EasyOcrTest`: same boxes and same text on all 42 images |
| Embedding | `sentence-transformers/all-MiniLM-L6-v2`, `encode(normalize_embeddings=True)` | `MiniLmEmbedder` + `BertTokenizer.encode` (max 256 tokens) | `MiniLmEmbedderTest`: cosine > 0.99999; `BertTokenizerTest` |
| Keyword search | MiniSearch 7.2: fields aiCaption, aiOcr, caption, filename, path; `prefix: true, fuzzy: 0.2, combineWith: 'AND'` | `MiniSearch` (port: BM25+, prefix weight 0.375, fuzzy 0.45, JS rounding) | `MiniSearchTest`: same ids, same scores, 164 queries |
| Hybrid | BM25 top 200 + cosine top 200, RRF k=60, best 500; semantic only if description or OCR field searched | `HybridSearch` | by construction (small) |

Reference output comes from thumbsup2 itself (`tools/make_reference.py` runs its unmodified
`ai_describe.py`), HuggingFace's tokenizers, thumbsup2's MiniSearch build, and torchvision /
Pillow / OpenCV for the image operations (`tools/make_*_reference.*`). The image operations are
bit-exact, including OpenCV quirks EasyOCR depends on (its "LANCZOS" flag is INTER_LINEAR; its
grey conversion of thumbsup2's RGB array weights the channels as BGR).

Deliberate differences from thumbsup2:

- **EXIF orientation is applied** before describing. thumbsup2 opens images with PIL without
  `exif_transpose`, so it captions and OCRs portrait phone photos sideways (a thumbsup2 bug).
- **Videos are described**: BLIP captions frames at 10%, 50% and 90% (distinct captions joined
  with "; "), EasyOCR reads the middle frame. thumbsup2 describes images only.
- **Photos over ~16.8 MP are decoded subsampled** (by powers of two) to bound memory.
- **A part switched on later is filled in**: a description stores caption and OCR separately
  (null = not made), so turning OCR on later reads text in already-captioned photos. thumbsup2
  keeps whatever its first run made.
- **Manual caption**: EXIF ImageDescription, then XMP dc:description / dc:title / xmp:Label, or
  a video's title (thumbsup2 also reads IPTC, which phones do not write).

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
- Room (`Database.kt`): `media` (the library copy), `ai_descriptions` (keyed by `aiKey`;
  caption/ocr nullable per part; `error` marks a file that could not be read, not retried until
  it changes), `ai_embeddings` (`aiKey`, model, vector, hash of the text embedded).
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
capped and centred. `Layouts.grid`: N square cells per row, images fitted inside. Pinch changes
row height or column count. Thumbnails are MediaStore's own (`ContentResolver.loadThumbnail`,
upright, video frames included) through a Coil fetcher (`MediaThumbFetcher`), requested in
size buckets so the memory cache is shared across zoom levels. The header (search field, AI
button, tabs) slides away on scroll down. A fast scroller shows the month.

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
  in without a marker are hashed at the next start (`verifyCopied`).
- `AiEngine`: opens the sessions for the current settings, describes one file at a time
  (serialized), saves description then embedding, `release()` closes the models (~1.5 GB).
- `MediaDecoding`: `ImageDecoder` (EXIF orientation applied, software bitmap, subsampled above
  16.8 MP) to `RgbImage` in strips; video frames by `MediaMetadataRetriever.getScaledFrameAtTime`.
- `SearchService`: rebuilds a `MiniSearch` over all files (fields as thumbsup2's) plus the
  embeddings from the database whenever files or descriptions change (debounced); queries are
  embedded with a MiniLM session kept for search; ties break newest first.

### Background work (`work/Workers.kt`)

| Work | When | Constraints |
|---|---|---|
| `DescribeWorker` (unique "describe") | AI button; app start if "Describe the rest when the app opens"; after a model download; after new media | user: none; automatic: battery not low, charging if set |
| `NewMediaWorker` (unique "media-trigger") | MediaStore content-URI trigger (images + video, 5 s update delay, 1 min max), re-armed after each run; armed while "Describe new photos as they arrive" is on | none |
| `ModelDownloadWorker` (unique "download-models") | Models screen | network (unmetered if "Only over Wi-Fi") |

Describing runs newest first and saves per file, so stopping loses at most one. With more than
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
