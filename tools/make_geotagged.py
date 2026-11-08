"""Makes tools/testdata/geo: test pictures and a video that say where they were taken, for trying
place search (tools/seed-emulator.sh pushes them). Three COCO photos from tools/testdata with
EXIF GPS (Waikiki, the Eiffel Tower, Tokyo Tower), and a short video tagged in Paris the way
phones tag theirs: a moov/udta/©xyz atom (ISO 6709), which Android's MediaMetadataRetriever
reads; ffmpeg's own "location" tag goes elsewhere.

Needs Pillow and ffmpeg on the PATH. Any Python 3.
"""
import pathlib
import struct
import subprocess

from PIL import Image

HERE = pathlib.Path(__file__).resolve().parent
SRC = HERE / 'testdata'
OUT = SRC / 'geo'

PHOTOS = [
    ('coco_000000000139.jpg', 'geo_waikiki.jpg', 21.2766, -157.8253),
    ('coco_000000000285.jpg', 'geo_eiffel.jpg', 48.8584, 2.2945),
    ('coco_000000000632.jpg', 'geo_tokyo.jpg', 35.6586, 139.7454),
]
VIDEO = ('geo_paris_video.mp4', '+48.8584+002.2945/')


def dms(x):
    x = abs(x)
    d = int(x)
    m = int((x - d) * 60)
    return float(d), float(m), round(((x - d) * 60 - m) * 60, 4)


def photo(src, dst, lat, lon):
    im = Image.open(SRC / src)
    exif = im.getexif()
    gps = exif.get_ifd(0x8825)
    gps[1] = 'N' if lat >= 0 else 'S'
    gps[2] = dms(lat)
    gps[3] = 'E' if lon >= 0 else 'W'
    gps[4] = dms(lon)
    im.save(OUT / dst, exif=exif, quality=92)


def boxes(b, start, end):
    i = start
    while i < end:
        size, kind = struct.unpack('>I4s', b[i:i + 8])
        yield i, size, kind
        i += size


def video(name, iso6709):
    path = OUT / name
    subprocess.run(['ffmpeg', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc=duration=3:size=640x360:rate=25',
                    '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-metadata', 'title=place test', str(path)], check=True)
    b = bytearray(path.read_bytes())
    moov = next((i, s) for i, s, k in boxes(b, 0, len(b)) if k == b'moov')
    assert moov[0] + moov[1] == len(b), 'moov is not last: chunk offsets would move'
    udta = next((i, s) for i, s, k in boxes(b, moov[0] + 8, moov[0] + moov[1]) if k == b'udta')
    text = iso6709.encode('ascii')
    atom = struct.pack('>I4sHH', 12 + len(text), b'\xa9xyz', len(text), 0x15c7) + text
    at = udta[0] + udta[1]
    b[at:at] = atom
    struct.pack_into('>I', b, udta[0], udta[1] + len(atom))
    struct.pack_into('>I', b, moov[0], moov[1] + len(atom))
    path.write_bytes(bytes(b))


def main():
    OUT.mkdir(exist_ok=True)
    for p in PHOTOS:
        photo(*p)
    video(*VIDEO)
    print('geotagged:', ', '.join(sorted(p.name for p in OUT.iterdir())))


if __name__ == '__main__':
    main()
