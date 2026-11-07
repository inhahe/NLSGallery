"""Puts the official weight files the app downloads into tools/weights/<model>/,
for the desktop tests (core/) and for push-models.bat to copy to a phone.

Each file is checked against the SHA-256 in app/src/main/assets/models/models.json.
It is copied from thumbsup2's caches in WSL when they hold it (--wsl, the
default when \\\\wsl.localhost is reachable), else downloaded from the same URL
the app uses, unzipping EasyOCR's archives as the app does.

    python tools/fetch_weights.py            # Windows Python is fine
    python tools/fetch_weights.py --download # ignore WSL, fetch from the internet

tools/weights is ~1.2 GB and not in git; delete it when no longer testing.
"""

import argparse
import hashlib
import io
import json
import shutil
import sys
import urllib.request
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
MANIFEST = HERE.parent / "app" / "src" / "main" / "assets" / "models" / "models.json"
OUT = HERE / "weights"
WSL_HOME = Path(r"\\wsl.localhost\Ubuntu\home\inhahe")
WSL_COPIES = {
    "blip": ".cache/huggingface/hub/models--Salesforce--blip-image-captioning-base/blobs/"
            "90f4c493dfc6f4c2152c8bfcfd52ee141055feb835eea2dceb8b9231e5dbf17c",
    "minilm": ".cache/huggingface/hub/models--sentence-transformers--all-MiniLM-L6-v2/blobs/"
              "53aa51172d142c89d9012cce15ae4d6cc0ca6895895114379cacb4fab128d9db",
    "craft": ".EasyOCR/model/craft_mlt_25k.pth",
    "english_g2": ".EasyOCR/model/english_g2.pth",
}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def download(entry, dest):
    print("downloading", entry["url"])
    tmp = dest.with_suffix(".part")
    with urllib.request.urlopen(entry["url"], timeout=120) as r, open(tmp, "wb") as f:
        shutil.copyfileobj(r, f, 1 << 20)
    if "member" in entry:
        with zipfile.ZipFile(tmp) as z, z.open(entry["member"]) as src, open(dest, "wb") as f:
            shutil.copyfileobj(src, f, 1 << 20)
        tmp.unlink()
    else:
        tmp.replace(dest)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--download", action="store_true", help="do not copy from WSL")
    args = ap.parse_args()
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    for key, entry in manifest.items():
        dest = OUT / key / entry["file"]
        dest.parent.mkdir(parents=True, exist_ok=True)
        if dest.exists() and dest.stat().st_size == entry["size"] and sha256(dest) == entry["sha256"]:
            print(f"{key}: present")
            continue
        wsl = WSL_HOME / WSL_COPIES[key]
        if not args.download and wsl.exists():
            print(f"{key}: copying from {wsl}")
            shutil.copyfile(wsl, dest)
        else:
            download(entry, dest)
        got = sha256(dest)
        if got != entry["sha256"]:
            dest.unlink()
            raise SystemExit(f"{key}: SHA-256 {got} is not the expected {entry['sha256']}")
        print(f"{key}: ok ({dest.stat().st_size} bytes)")


if __name__ == "__main__":
    sys.exit(main())
