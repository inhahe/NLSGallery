"""Runs thumbsup2's own AI worker (scripts/ai_describe.py, unmodified) over
tools/testdata and stores what it says, as the gold standard the app's
on-device pipeline is tested against.

Run in thumbsup2's Python environment (WSL), e.g.
    ~/gallery-tools/.venv/bin/python tools/make_reference.py --device cpu
    ~/gallery-tools/.venv/bin/python tools/make_reference.py --device cuda

Writes tools/reference/thumbsup2_<device>.json:
    { "<file name>": {"sha256", "caption", "ocr", "embedding"}, ... }

thumbsup2 opens each image with PIL and never applies EXIF orientation, so
for exif_orientation6.jpg this records what it says about the sideways image.
"""

import argparse
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
THUMBSUP2 = HERE.parent.parent / "thumbsup2"
WORKER = THUMBSUP2 / "scripts" / "ai_describe.py"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", choices=["cpu", "cuda"], required=True)
    args = ap.parse_args()

    data = HERE / "testdata"
    manifest = json.loads((data / "manifest.json").read_text(encoding="utf-8"))
    names = sorted(manifest)

    env = dict(os.environ, HF_HUB_OFFLINE="1", PYTHONUNBUFFERED="1")
    if args.device == "cpu":
        env["CUDA_VISIBLE_DEVICES"] = ""
    jobs = "".join(json.dumps({"id": n, "path": str(data / n)}) + "\n" for n in names)
    proc = subprocess.run(
        [sys.executable, str(WORKER), "--embed"],
        input=jobs, capture_output=True, text=True, encoding="utf-8", env=env,
    )
    if proc.returncode != 0:
        sys.stderr.write(proc.stderr)
        raise SystemExit(f"ai_describe.py exited with {proc.returncode}")

    out = {}
    ready = None
    for line in proc.stdout.splitlines():
        msg = json.loads(line)
        if msg.get("ready"):
            ready = msg
            continue
        if msg.get("error"):
            raise SystemExit(f"{msg['id']}: {msg['error']}")
        name = msg["id"]
        sha = hashlib.sha256((data / name).read_bytes()).hexdigest()
        if sha != manifest[name]:
            raise SystemExit(f"{name} differs from manifest.json: rerun fetch_test_images.py")
        out[name] = {
            "sha256": sha,
            "caption": msg["caption"],
            "ocr": msg["ocr"],
            "embedding": msg["embedding"],
        }
    if ready["device"] != args.device:
        raise SystemExit(f"asked for {args.device}, worker ran on {ready['device']}")
    dest = HERE / "reference" / f"thumbsup2_{args.device}.json"
    dest.write_text(json.dumps(out, indent=1), encoding="utf-8")
    print(f"{len(out)} images -> {dest}")
    for n in names:
        print(f"{n}: {out[n]['caption']!r} | OCR {out[n]['ocr'][:80]!r}")


if __name__ == "__main__":
    main()
