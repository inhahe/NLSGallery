"""Records the image operations thumbsup2's models see their input through,
and EasyOCR's intermediate results, for the app's Kotlin ports:

  ops/   for synthetic PNGs (lossless, so Kotlin reads exactly these pixels):
           blip_<name>.u8      torchvision antialiased bicubic to 384x384 (uint8,
                               CHW), as transformers' BLIP processor resizes
           pilL_<name>_<w>x<h>.u8   PIL bicubic resize of the greyscale image
           cvlin_<name>_<w>x<h>.u8  cv2.resize INTER_LINEAR of the RGB image (HWC)
           gray_<name>.u8      cv2.cvtColor(RGB array, COLOR_BGR2GRAY), which is
                               what EasyOCR computes from the array thumbsup2 gives it
           warp_<name>.u8      cv2.warpPerspective of the grey image
  ocr.json  for every test image: EasyOCR's boxes after grouping (detect) and
            its final (box, text, confidence) list, run in float32 on the CPU
            without quantization, i.e. the arithmetic the app's ONNX graphs do.

Run in thumbsup2's environment:
    ~/gallery-tools/.venv/bin/python tools/make_image_reference.py
"""

import json
import os
from pathlib import Path

import cv2
import numpy as np
import torch
from PIL import Image

os.environ.setdefault("HF_HUB_OFFLINE", "1")

HERE = Path(__file__).resolve().parent
DATA = HERE / "testdata"
OPS = DATA / "ops"


def synth_images():
    rng = np.random.default_rng(7)
    out = {}
    noise = rng.integers(0, 256, (389, 517, 3), dtype=np.uint8)
    out["noise517x389"] = noise
    y, x = np.mgrid[0:700, 0:1000]
    grad = np.stack([(x * 255 // 999), (y * 255 // 699), ((x + y) * 255 // 1698)], -1).astype(np.uint8)
    out["grad1000x700"] = grad
    small = rng.integers(0, 256, (150, 200, 3), dtype=np.uint8)
    out["small200x150"] = small                       # upscaled to 384
    strip = rng.integers(0, 256, (100, 3000, 3), dtype=np.uint8)
    out["strip3000x100"] = strip                      # extreme aspect ratio
    checker = ((x // 7 + y // 5) % 2 * 255).astype(np.uint8)
    out["checker1000x700"] = np.stack([checker, 255 - checker, checker // 2], -1)
    return out


def write_ops():
    from transformers import BlipProcessor
    import torchvision.transforms.v2.functional as tvF

    OPS.mkdir(parents=True, exist_ok=True)
    proc = BlipProcessor.from_pretrained("Salesforce/blip-image-captioning-base")
    for name, arr in synth_images().items():
        Image.fromarray(arr).save(OPS / f"{name}.png")
        # The resize BLIP's processor does (TorchvisionBackend.resize), checked
        # against the processor's own normalized output so the reference is
        # the real thing, not our reading of it.
        t = tvF.pil_to_tensor(Image.fromarray(arr))
        r = tvF.resize(t, [384, 384], interpolation=tvF.InterpolationMode.BICUBIC, antialias=True)
        pv = proc(Image.fromarray(arr), return_tensors="pt")["pixel_values"][0]
        mean = torch.tensor(proc.image_processor.image_mean).view(3, 1, 1) * 255
        std = torch.tensor(proc.image_processor.image_std).view(3, 1, 1) * 255
        assert torch.allclose((r.float() - mean) / std, pv, atol=1e-5), name
        (OPS / f"blip_{name}.u8").write_bytes(r.numpy().tobytes())

        grey_pil = Image.fromarray(arr).convert("L")
        for w, h in ((64, 64), (37, 64), (300, 64), (64, 211)):
            (OPS / f"pilL_{name}_{w}x{h}.u8").write_bytes(
                np.array(grey_pil.resize((w, h), Image.BICUBIC)).tobytes())
        for w, h in ((257, 193), (1000, 700), (2560, 1920), (64, 64), (91, 64)):
            (OPS / f"cvlin_{name}_{w}x{h}.u8").write_bytes(
                cv2.resize(arr, (w, h), interpolation=cv2.INTER_LINEAR).tobytes())
        grey = cv2.cvtColor(arr, cv2.COLOR_BGR2GRAY)
        (OPS / f"gray_{name}.u8").write_bytes(grey.tobytes())
        hh, ww = grey.shape
        rect = np.array([[ww * 0.1, hh * 0.2], [ww * 0.8, hh * 0.1], [ww * 0.9, hh * 0.7],
                         [ww * 0.2, hh * 0.9]], dtype="float32")
        from easyocr.utils import four_point_transform
        warped = four_point_transform(grey, rect)
        (OPS / f"warp_{name}.u8").write_bytes(warped.tobytes())
        (OPS / f"warp_{name}.shape").write_text(f"{warped.shape[1]} {warped.shape[0]}")


def write_ocr():
    import easyocr
    reader = easyocr.Reader(["en"], gpu=False, quantize=False, verbose=False)
    manifest = json.loads((DATA / "manifest.json").read_text(encoding="utf-8"))
    out = {}
    for name in sorted(manifest):
        with Image.open(DATA / name) as im:
            arr = np.array(im.convert("RGB"))  # what thumbsup2's worker passes
        hor, free = reader.detect(arr)
        result = reader.readtext(arr)
        out[name] = {
            "horizontal": [[int(v) for v in b] for b in hor[0]],
            "free": [[[float(c) for c in p] for p in b] for b in free[0]],
            "result": [{"box": [[float(c) for c in p] for p in box], "text": text, "conf": float(conf)}
                       for box, text, conf in result],
            "text": " ".join(text for _, text, _ in result).strip(),
        }
        print(name, repr(out[name]["text"][:80]))
    (HERE / "reference" / "ocr.json").write_text(json.dumps(out, indent=0), encoding="utf-8")


def write_raw():
    """Each test image's pixels as PIL decodes them for thumbsup2 (RGB, no EXIF rotation):
    raw/<name>.rgb with a 'width height' line in raw/<name>.size. Java's JPEG decoder differs
    from libjpeg-turbo, so tests feed these to the models to compare like with like."""
    raw = DATA / "raw"
    raw.mkdir(exist_ok=True)
    manifest = json.loads((DATA / "manifest.json").read_text(encoding="utf-8"))
    for name in sorted(manifest):
        with Image.open(DATA / name) as im:
            rgb = im.convert("RGB")
        (raw / f"{name}.rgb").write_bytes(np.array(rgb).tobytes())
        (raw / f"{name}.size").write_text(f"{rgb.width} {rgb.height}")


if __name__ == "__main__":
    import sys
    torch.set_num_threads(8)
    parts = sys.argv[1:] or ["raw", "ops", "ocr"]
    if "raw" in parts:
        write_raw()
    if "ops" in parts:
        write_ops()
    if "ocr" in parts:
        write_ocr()
