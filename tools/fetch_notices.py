"""Puts the third-party notices of the native libraries the app ships into
app/src/main/assets/licences, for Settings -> About -> Credits and licences: ONNX Runtime's
ThirdPartyNotices.txt (the code compiled into its native library: protobuf, Eigen, ...; it is in
its repository, not its AAR) and the NOTICE.txt in Qualcomm's QNN runtime AAR (not packaged
into the APK by Gradle), each for the version gradle/libs.versions.toml names, the file names
carrying it. Notices of other versions are removed. Run it after changing either version: the
build refuses to run until the notices match (app/build.gradle.kts, checkNotices).

Any Python 3; reads the QNN AAR from Gradle's cache when it is there, else from Maven Central.
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
    ort, qnn = version('onnxruntime'), version('qnn-runtime')
    wanted = {
        f'onnxruntime-{ort}-ThirdPartyNotices.txt':
            lambda: text(fetch(f'https://raw.githubusercontent.com/microsoft/onnxruntime/v{ort}/ThirdPartyNotices.txt')),
        f'qnn-runtime-{qnn}-NOTICE.txt':
            lambda: text(zipfile.ZipFile(io.BytesIO(qnn_aar(qnn))).read('NOTICE.txt')),
    }
    for name, get in wanted.items():
        path = OUT / name
        if not path.exists():
            body = get().replace('\r\n', '\n')
            path.write_text(body, encoding='utf-8', newline='\n')
        print(f'{name}: {path.stat().st_size} bytes')
    for old in OUT.glob('*.txt'):
        if old.name not in wanted:
            old.unlink()
            print(f'removed {old.name}')


if __name__ == '__main__':
    main()
