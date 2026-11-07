"""Fills tools/testdata with the images the model tests run on.

Two kinds:
  * photos from the COCO 2017 validation set (Flickr photos under CC licenses,
    https://cocodataset.org/#termsofuse), downloaded once;
  * synthetic text images (screenshots, a document, a sign, rotated text),
    drawn with Pillow, for OCR.

testdata/ is not in git: run this script to recreate it. manifest.json records
each file's SHA-256, and make_reference.py stores the hashes it ran on, so a
test can tell when its reference no longer matches the file on disk.

Run with any Python that has Pillow (the Windows one is fine):
    python tools/fetch_test_images.py
"""

import hashlib
import json
import sys
import urllib.request
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
OUT = HERE / "testdata"

# COCO val2017 ids: animals, people, rooms, food, vehicles, and several with
# text (signs, buses, a clock) so OCR has real-world material too.
COCO_IDS = [
    39769, 139, 285, 632, 724, 776, 785, 802, 872, 885, 1000, 1268, 1296,
    1353, 1425, 1490, 1503, 1532, 1584, 1675, 1761, 1818, 2006, 2153, 2299,
    2473, 2532, 2587, 2592, 2685, 3156, 4495, 5001, 6818, 7386,
]


def fetch_coco():
    for cid in COCO_IDS:
        name = f"coco_{cid:012d}.jpg"
        dest = OUT / name
        if dest.exists():
            continue
        url = f"http://images.cocodataset.org/val2017/{cid:012d}.jpg"
        print("downloading", url)
        with urllib.request.urlopen(url, timeout=60) as r:
            dest.write_bytes(r.read())


def font(size, bold=False):
    candidates = (["arialbd.ttf", "DejaVuSans-Bold.ttf"] if bold
                  else ["arial.ttf", "DejaVuSans.ttf"])
    for c in candidates:
        for base in ("C:/Windows/Fonts/", "/usr/share/fonts/truetype/dejavu/", ""):
            try:
                return ImageFont.truetype(base + c, size)
            except OSError:
                pass
    raise SystemExit("no TrueType font found (looked for Arial and DejaVu Sans)")


def synth_chat_screenshot():
    """A phone-shaped chat screenshot: the Screenshots category's typical case."""
    im = Image.new("RGB", (1080, 2340), (245, 245, 245))
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 1080, 220], fill=(18, 140, 126))
    d.text((60, 110), "Grandma", font=font(56, bold=True), fill="white")
    bubbles = [
        (False, "Are you coming to dinner on Sunday?"),
        (True, "Yes! What time should we be there?"),
        (False, "Around 5 pm. Bring the potato salad"),
        (True, "Will do. See you then"),
    ]
    y = 320
    f = font(46)
    for mine, text in bubbles:
        w = d.textlength(text, font=f) + 80
        x0 = 1080 - 40 - w if mine else 40
        d.rounded_rectangle([x0, y, x0 + w, y + 120], 30,
                            fill=(220, 248, 198) if mine else "white")
        d.text((x0 + 40, y + 32), text, font=f, fill=(20, 20, 20))
        y += 200
    return im


def synth_settings_screenshot():
    im = Image.new("RGB", (1080, 1920), "white")
    d = ImageDraw.Draw(im)
    d.text((60, 120), "Settings", font=font(72, bold=True), fill="black")
    rows = ["Wi-Fi", "Bluetooth", "Battery saver", "Display brightness",
            "Notifications", "Storage 64 GB used"]
    y = 320
    for r in rows:
        d.text((60, y), r, font=font(50), fill=(30, 30, 30))
        d.line([60, y + 110, 1020, y + 110], fill=(220, 220, 220), width=3)
        y += 160
    return im


def synth_document():
    im = Image.new("RGB", (1240, 1754), (252, 252, 250))
    d = ImageDraw.Draw(im)
    d.text((100, 120), "INVOICE 2047", font=font(64, bold=True), fill="black")
    lines = [
        "Bill to: Northwind Traders",
        "Date: March 14, 2026",
        "Item                     Qty     Price",
        "Blue ceramic mugs          4    $36.00",
        "Walnut cutting board       1    $48.50",
        "Total due                       $84.50",
    ]
    y = 300
    for l in lines:
        d.text((100, y), l, font=font(40), fill=(25, 25, 25))
        y += 90
    return im


def synth_sign():
    """Large white text on a coloured board, like a photographed sign."""
    im = Image.new("RGB", (1600, 1000), (90, 130, 170))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([200, 250, 1400, 750], 40, fill=(0, 100, 50))
    d.text((300, 380), "EXIT 42", font=font(150, bold=True), fill="white")
    d.text((320, 580), "Springfield", font=font(90), fill="white")
    return im


def synth_rotated_text():
    """Text at an angle: exercises EasyOCR's rotated-box path."""
    im = Image.new("RGB", (1200, 900), "white")
    layer = Image.new("RGBA", (1000, 200), (255, 255, 255, 0))
    ImageDraw.Draw(layer).text((20, 40), "Fresh bread daily", font=font(96, bold=True), fill="black")
    layer = layer.rotate(25, expand=True, resample=Image.BICUBIC)
    im.paste(layer, (60, 200), layer)
    return im


def synth_meme_on_photo():
    """Impact-style caption over a real photo (needs the COCO cats image)."""
    base = Image.open(OUT / "coco_000000039769.jpg").convert("RGB")
    d = ImageDraw.Draw(base)
    f = font(56, bold=True)
    for text, y in (("WHEN THE COUCH", 20), ("IS ALL YOURS", 400)):
        w = d.textlength(text, font=f)
        x = (base.width - w) / 2
        d.text((x, y), text, font=f, fill="white", stroke_width=4, stroke_fill="black")
    return base


def make_synthetic():
    makers = {
        "synth_chat_screenshot.png": synth_chat_screenshot,
        "synth_settings_screenshot.png": synth_settings_screenshot,
        "synth_document.png": synth_document,
        "synth_sign.jpg": synth_sign,
        "synth_rotated_text.png": synth_rotated_text,
        "synth_meme.jpg": synth_meme_on_photo,
    }
    for name, make in makers.items():
        dest = OUT / name
        if dest.exists():
            continue
        im = make()
        if name.endswith(".jpg"):
            im.save(dest, quality=92)
        else:
            im.save(dest)
        print("made", name)


def make_exif_rotated():
    """A portrait photo stored sideways with EXIF orientation 6, as phones
    save them. The app must caption it upright."""
    dest = OUT / "exif_orientation6.jpg"
    if dest.exists():
        return
    upright = Image.open(OUT / "coco_000000000785.jpg").convert("RGB")
    stored = upright.transpose(Image.Transpose.ROTATE_90)  # what the sensor wrote
    exif = Image.Exif()
    exif[0x0112] = 6  # rotate 90 CW to display
    stored.save(dest, quality=92, exif=exif)
    print("made", dest.name)


def main():
    OUT.mkdir(exist_ok=True)
    fetch_coco()
    make_synthetic()
    make_exif_rotated()
    manifest = {}
    for p in sorted(OUT.iterdir()):
        if p.suffix.lower() in (".jpg", ".jpeg", ".png", ".webp"):
            manifest[p.name] = hashlib.sha256(p.read_bytes()).hexdigest()
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=1), encoding="utf-8")
    print(len(manifest), "images in", OUT)


if __name__ == "__main__":
    sys.exit(main())
