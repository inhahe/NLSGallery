"""Builds app/src/main/assets/places.bin, the gazetteer place search reads: every populated place
of 1000 people or more (GeoNames cities1000), its region (admin1) and country, and a few English
names of countries GeoNames does not use ("usa", "uk", "holland").

Reads tools/geonames/{cities1000.zip, admin1CodesASCII.txt, countryInfo.txt} from
https://download.geonames.org/export/dump/ (CC BY 4.0: credit GeoNames). Any Python 3.

Format, little-endian:
  b"NLSP", u16 version (1)
  u32 strings, each u16 byte length + UTF-8
  u16 countries, each 2 ASCII bytes (ISO code) + u32 name
  u16 regions, each u16 country + u32 name
  u32 aliases, each u32 name + u16 country
  u32 places, each f32 latitude, f32 longitude, u32 name, u16 region (0xFFFF none), u16 country,
      u32 population
"""
import io
import pathlib
import struct
import sys
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
SRC = HERE / 'geonames'
OUT = HERE.parent / 'app' / 'src' / 'main' / 'assets' / 'places.bin'

# Historical, abandoned and destroyed places are not where pictures are taken.
SKIP_CODES = {'PPLH', 'PPLQ', 'PPLW', 'PPLCH'}

ALIASES = {
    'US': ['usa', 'america', 'united states of america'],
    'GB': ['uk', 'britain', 'great britain'],
    'NL': ['holland'],
    'AE': ['uae'],
    'CZ': ['czech republic'],
    'KR': ['korea'],
}


def main():
    strings, index = [], {}

    def s(text):
        if text not in index:
            index[text] = len(strings)
            strings.append(text)
        return index[text]

    countries, country_index = [], {}
    for line in open(SRC / 'countryInfo.txt', encoding='utf-8'):
        if line.startswith('#') or not line.strip():
            continue
        f = line.rstrip('\n').split('\t')
        country_index[f[0]] = len(countries)
        countries.append((f[0], s(f[4])))

    regions, region_index = [], {}
    for line in open(SRC / 'admin1CodesASCII.txt', encoding='utf-8'):
        f = line.rstrip('\n').split('\t')
        cc = f[0].split('.')[0]
        if cc not in country_index:
            continue
        region_index[f[0]] = len(regions)
        regions.append((country_index[cc], s(f[1])))

    aliases = [(s(a), country_index[cc]) for cc, names in ALIASES.items() for a in names]
    # "The Netherlands" is "netherlands" too.
    aliases += [(s(name[4:]), i) for i, (_, n) in enumerate(countries) for name in [strings[n]] if name.startswith('The ')]

    places = []
    with zipfile.ZipFile(SRC / 'cities1000.zip') as z:
        with z.open('cities1000.txt') as raw:
            for line in io.TextIOWrapper(raw, encoding='utf-8'):
                f = line.rstrip('\n').split('\t')
                if f[7] in SKIP_CODES or f[8] not in country_index:
                    continue
                region = region_index.get(f'{f[8]}.{f[10]}', 0xFFFF)
                population = int(f[14] or 0)
                places.append((float(f[4]), float(f[5]), s(f[1]), region, country_index[f[8]], population))

    out = io.BytesIO()
    out.write(b'NLSP' + struct.pack('<H', 1))
    out.write(struct.pack('<I', len(strings)))
    for text in strings:
        b = text.encode('utf-8')
        out.write(struct.pack('<H', len(b)) + b)
    out.write(struct.pack('<H', len(countries)))
    for code, name in countries:
        out.write(code.encode('ascii') + struct.pack('<I', name))
    out.write(struct.pack('<H', len(regions)))
    for country, name in regions:
        out.write(struct.pack('<HI', country, name))
    out.write(struct.pack('<I', len(aliases)))
    for name, country in aliases:
        out.write(struct.pack('<IH', name, country))
    out.write(struct.pack('<I', len(places)))
    for lat, lon, name, region, country, population in places:
        out.write(struct.pack('<ffIHHI', lat, lon, name, region, country, population))
    OUT.write_bytes(out.getvalue())
    print(f'{OUT}: {len(places)} places, {len(regions)} regions, {len(countries)} countries, '
          f'{len(strings)} names, {OUT.stat().st_size} bytes')


if __name__ == '__main__':
    sys.exit(main())
