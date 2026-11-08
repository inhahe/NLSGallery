"""Records OpenCV's minAreaRect and boxPoints, which EasyOCR's CRAFT post-processing makes its
boxes with, for the app's Kotlin port (core Geometry) to match bit for bit: the corners are
truncated to integers after scaling, so one float32 ulp can move a box edge by a pixel.

reference/geometry.json: a list of cases, each a binary mask (w, h, rows as hex of np.packbits)
whose nonzero pixels, in np.where's raster order and as int64 (x, y) pairs, are what EasyOCR
hands minAreaRect, with the float32 rectangle (cx, cy, w, h, angle) and boxPoints' 8 floats.

The shapes are what CRAFT's components look like (filled rotated rectangles and ellipses, at
every angle, thin and fat, dilated) plus the degenerate cases (one or two points, lines).

Run in thumbsup2's environment, whose OpenCV is the one being matched:
    ~/gallery-tools/.venv/bin/python tools/make_geometry_reference.py
"""

import json
from pathlib import Path

import cv2
import numpy as np

HERE = Path(__file__).resolve().parent


def cases(rng):
    for _ in range(300):
        w, h = int(rng.integers(8, 48)), int(rng.integers(8, 48))
        m = np.zeros((h, w), np.uint8)
        cx, cy = rng.uniform(0, w), rng.uniform(0, h)
        rw, rh = rng.uniform(1, w), rng.uniform(1, max(2, h / 3))
        ang = float(rng.uniform(-90, 90))
        box = cv2.boxPoints(((cx, cy), (rw, rh), ang))
        cv2.fillPoly(m, [np.round(box).astype(np.int32)], 1)
        if rng.random() < 0.3:
            m = cv2.dilate(m, np.ones((3, 3), np.uint8))
        yield m
    for _ in range(100):
        w, h = int(rng.integers(8, 48)), int(rng.integers(8, 48))
        m = np.zeros((h, w), np.uint8)
        cv2.ellipse(m, (int(rng.integers(0, w)), int(rng.integers(0, h))),
                    (int(rng.integers(1, w)), int(rng.integers(1, max(2, h // 3)))),
                    float(rng.uniform(0, 180)), 0, 360, 1, -1)
        yield m
    for _ in range(60):
        # Upright rectangles and squares: the -90 degree case, and diamond-like near-squares.
        w, h = int(rng.integers(4, 40)), int(rng.integers(4, 40))
        m = np.zeros((h + 4, w + 4), np.uint8)
        m[2:2 + h, 2:2 + w] = 1
        yield m
    for _ in range(60):
        # Lines (two-point hulls) at every slope, and single points.
        w, h = int(rng.integers(2, 30)), int(rng.integers(2, 30))
        m = np.zeros((h, w), np.uint8)
        if rng.random() < 0.2:
            m[rng.integers(0, h), rng.integers(0, w)] = 1
        else:
            cv2.line(m, (int(rng.integers(0, w)), int(rng.integers(0, h))),
                     (int(rng.integers(0, w)), int(rng.integers(0, h))), 1, 1)
        yield m


def main():
    rng = np.random.default_rng(11)
    out = []
    for m in cases(rng):
        if not m.any():
            continue
        pts = np.roll(np.array(np.where(m != 0)), 1, axis=0).transpose().reshape(-1, 2)
        rect = cv2.minAreaRect(pts)
        box = cv2.boxPoints(rect)
        r32 = np.array([rect[0][0], rect[0][1], rect[1][0], rect[1][1], rect[2]], np.float32)
        out.append({
            "w": int(m.shape[1]), "h": int(m.shape[0]),
            "rows": [np.packbits(row).tobytes().hex() for row in m],
            "rect": [float(v) for v in r32],
            "box": [float(v) for v in box.reshape(-1)],
        })
    dest = HERE / "reference" / "geometry.json"
    dest.write_text(json.dumps({"opencv": cv2.__version__, "cases": out}), encoding="utf-8")
    print(len(out), "cases, OpenCV", cv2.__version__, "->", dest)


if __name__ == "__main__":
    main()
