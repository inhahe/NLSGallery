"""Prints the Umm al-Qura calendar's month lengths, 1 Muharram 1365 (6 December 1945) to the end
of 1500 (2077), as the hex string `UMM_AL_QURA` in core/.../search/Calendars.kt: bit i (most
significant first in each hex digit) is 1 when month i has 30 days, 0 when 29.

Saudi Arabia's official calendar is a table, not a rule a computation reproduces (its early
years follow criteria since replaced); this is the table hijridate carries (MIT licence:
pip install hijridate). Before 1365 that table has months of 28 and 31 days, the early
calendar's quirks, so it is taken from 1365. Any Python 3.
"""
from hijridate import Hijri
from hijridate import ummalqura

FIRST_YEAR = 1365


def main():
    starts = ummalqura.MONTH_STARTS
    skip = (FIRST_YEAR - 1343) * 12
    # MONTH_STARTS ends with 1 Muharram 1501, so the last month's length is there too.
    lengths = [b - a for a, b in zip(starts[skip:], starts[skip + 1:])]
    assert set(lengths) <= {29, 30}, set(lengths)
    assert len(lengths) == (1500 - FIRST_YEAR + 1) * 12, len(lengths)
    first = Hijri(FIRST_YEAR, 1, 1).to_gregorian()
    bits = ''.join('1' if n == 30 else '0' for n in lengths)
    bits += '0' * (-len(bits) % 4)
    hexes = ''.join('%x' % int(bits[i:i + 4], 2) for i in range(0, len(bits), 4))
    print(f'// {len(lengths)} months from {first.year}-{first.month:02}-{first.day:02}')
    for i in range(0, len(hexes), 96):
        print(f'            "{hexes[i:i + 96]}" +')


if __name__ == '__main__':
    main()
