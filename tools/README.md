# tools

Scripts that make and check the app's model graphs and the reference output its tests compare
against. Most run in **thumbsup2's Python environment** (WSL), so "the same as thumbsup2" is
checked against the same library versions thumbsup2 runs (torch 2.11, transformers 5.5.4,
easyocr 1.7.2, sentence-transformers 5.4.0).

## Environment

A venv layered on thumbsup2's (`~/gallery-tools/.venv` in WSL, README.txt beside it) adds only
onnx, onnxruntime, onnxscript and onnxconverter-common:

```bash
mkdir -p ~/gallery-tools && cd ~/gallery-tools
/usr/bin/python3.12 -m venv .venv
echo "/home/inhahe/thumbsup2/.venv/lib/python3.12/site-packages" > .venv/lib/python3.12/site-packages/thumbsup2-venv.pth
.venv/bin/pip install onnx onnxruntime onnxscript onnxconverter-common
```

Without thumbsup2's environment, `requirements.txt` here lists what to install fresh.

## Scripts

| Script | Python | Makes |
|---|---|---|
| `fetch_test_images.py` | any (Pillow) | `testdata/`: 35 COCO val2017 photos, synthetic screenshots / document / sign / rotated text / meme, an EXIF-rotated photo; `manifest.json` with SHA-256 |
| `make_reference.py --device cpu\|cuda` | thumbsup2's | `reference/thumbsup2_<device>.json`: what thumbsup2's unmodified `ai_describe.py --embed` says about each test image |
| `make_image_reference.py [raw] [ops] [ocr] [ocr448]` | thumbsup2's | `testdata/raw/` (pixels as PIL decodes them), `testdata/ops/` (torchvision / Pillow / OpenCV operation outputs), `reference/ocr.json` (EasyOCR's boxes and text, float32 CPU unquantized), `reference/ocr_canvas448.json` (the same with every image shrunk to 448 first) |
| `make_geometry_reference.py` | thumbsup2's | `reference/geometry.json`: OpenCV's `minAreaRect` and `boxPoints` on 520 masks shaped like CRAFT's components |
| `make_tokenizer_reference.py` | thumbsup2's | `reference/tokenizer.json`: MiniLM encodings and BLIP decodings |
| `make_search_reference.mjs` | Node | `reference/search.json`: thumbsup2's MiniSearch results |
| `export_models.py [--craft-half]` | thumbsup2's + onnx | `app/src/main/assets/models/`: the graphs, `models.json`, `vocab.txt`; checks each against PyTorch. `--craft-half` makes only `craft_fp16.onnx` (the GPU's float16 detector) from the `craft.onnx` already there |
| `fetch_weights.py` | any | `weights/`: the official weight files (copied from thumbsup2's caches in WSL, or downloaded), SHA-256 checked |
| `push-test-data.bat [emulator]` | | the test images (files and PIL's pixels) and references onto the phone or the emulator, for the on-device tests |
| `make_places.py` | any | `app/src/main/assets/places.bin`: the gazetteer place search reads (GeoNames' places of 1000 people or more, their regions and countries, a few everyday country names), from `geonames/cities1000.zip`, `admin1CodesASCII.txt` and `countryInfo.txt` downloaded from https://download.geonames.org/export/dump/ (CC BY 4.0; `geonames/` is not in git). Run it again to take in GeoNames' changes |

`testdata/` and `weights/` (~1.2 GB) are not in git; delete them when not testing.

## Typical run

```
python tools/fetch_test_images.py
wsl: ~/gallery-tools/.venv/bin/python tools/make_reference.py --device cuda   (and --device cpu)
wsl: ~/gallery-tools/.venv/bin/python tools/make_image_reference.py
wsl: ~/gallery-tools/.venv/bin/python tools/make_geometry_reference.py
wsl: ~/gallery-tools/.venv/bin/python tools/make_tokenizer_reference.py
node tools/make_search_reference.mjs
wsl: ~/gallery-tools/.venv/bin/python tools/export_models.py
python tools/fetch_weights.py
gradlew :core:test
```

The OCR reference is EasyOCR in float32 on the CPU without its default dynamic int8
quantization: the arithmetic of the ONNX graphs, and of thumbsup2 on a GPU
(`thumbsup2_cuda.json`). On a CPU, EasyOCR quantizes its recognizer and reads a few characters
differently (`thumbsup2_cpu.json`); captions are the same either way.
