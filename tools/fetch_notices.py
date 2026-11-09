"""Puts the third-party notices of the native libraries the app ships into
app/src/main/assets/licences, for Settings -> About -> Credits and licences, each for the version
gradle/libs.versions.toml names (the file name carries it):

- ONNX Runtime's ThirdPartyNotices.txt, from its repository (the code compiled into its native
  library: protobuf, Eigen, ...; its AAR has none);
- the QNN execution provider's ThirdPartyNotices.txt, from its repository (onnxruntime-qnn);
- the NOTICE.txt in Qualcomm's QNN runtime AAR, which Gradle does not package into the APK.

A file already there is kept when it reads as that notice (its opening words), else fetched again
(an interrupted download, an error page). Anything else in the folder is removed. Run it after
changing one of those versions: the build refuses to run until the notices match
(app/build.gradle.kts, checkNotices).

Any Python 3; reads the QNN runtime's AAR from Gradle's cache when it is there, else from Maven
Central.
"""
import io
import pathlib
import re
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / 'app' / 'src' / 'main' / 'assets' / 'licences'
CATALOG = ROOT / 'gradle' / 'libs.versions.toml'


def version(key):
    m = re.search(rf'^{re.escape(key)}\s*=\s*"([^"]+)"', CATALOG.read_text(encoding='utf-8'), re.M)
    if not m:
        raise SystemExit(f'no {key} version in {CATALOG}')
    return m.group(1)


def fetch(url):
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read()


def text(data):
    """Bytes as text: UTF-8, else Windows-1252 (Qualcomm's notice has its non-breaking spaces)."""
    try:
        return data.decode('utf-8')
    except UnicodeDecodeError:
        return data.decode('cp1252')


def qnn_aar(v):
    cache = pathlib.Path.home() / '.gradle' / 'caches' / 'modules-2' / 'files-2.1' / 'com.qualcomm.qti' / 'qnn-runtime' / v
    for aar in cache.glob(f'*/qnn-runtime-{v}.aar'):
        return aar.read_bytes()
    return fetch(f'https://repo1.maven.org/maven2/com/qualcomm/qti/qnn-runtime/{v}/qnn-runtime-{v}.aar')


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    ort, ep, qnn = version('onnxruntime'), version('qnn-ep'), version('qnn-runtime')
    # File name: (how its text begins, how to get it).
    wanted = {
        f'onnxruntime-{ort}-ThirdPartyNotices.txt': (
            'THIRD PARTY SOFTWARE NOTICES AND INFORMATION',
            lambda: text(fetch(f'https://raw.githubusercontent.com/microsoft/onnxruntime/v{ort}/ThirdPartyNotices.txt'))),
        f'onnxruntime-qnn-{ep}-ThirdPartyNotices.txt': (
            'THIRD PARTY SOFTWARE NOTICES AND INFORMATION',
            lambda: text(fetch(f'https://raw.githubusercontent.com/onnxruntime/onnxruntime-qnn/v{ep}/ThirdPartyNotices.txt'))),
        f'qnn-runtime-{qnn}-NOTICE.txt': (
            'This Notice.txt file contains',
            lambda: text(zipfile.ZipFile(io.BytesIO(qnn_aar(qnn))).read('NOTICE.txt'))),
    }
    for name, (start, get) in wanted.items():
        path = OUT / name
        if path.exists() and path.read_text(encoding='utf-8').lstrip('﻿').startswith(start):
            print(f'{name}: {path.stat().st_size} bytes (kept)')
            continue
        body = get().replace('\r\n', '\n').lstrip('﻿')
        if not body.startswith(start):
            raise SystemExit(f'{name}: what came does not read as the notice: {body[:80]!r}')
        part = path.with_name(path.name + '.part')
        part.write_text(body, encoding='utf-8', newline='\n')
        part.replace(path)
        print(f'{name}: {path.stat().st_size} bytes')
    for other in OUT.iterdir():
        if other.name not in wanted:
            other.unlink()
            print(f'removed {other.name}')


if __name__ == '__main__':
    main()
