"""Prints the Umm al-Qura table's declarations for LunarHolidays in
core/.../search/Calendars.kt (from its "as tools/make_umm_al_qura.py prints it" comment to the end
of UMM_AL_QURA): the month lengths from 1 Muharram 1365 (5 December 1945) to the end of 1500
(2077), and where they begin. A month's bit in UMM_AL_QURA, most significant first in each hex
digit, is 1 when it has 30 days, 0 when 29.

Saudi Arabia's official calendar is a table, not a rule a computation reproduces (its early
years follow criteria since replaced); this is the table hijridate carries (MIT licence:
pip install hijridate). Before 1365 that table has months of 28 and 31 days, the early
calendar's quirks, so it is taken from 1365. Any Python 3.
"""
from hijridate import Hijri
from hijridate import ummalqura

FIRST_YEAR = 1365
LAST_YEAR = 1500


def main():
    starts = ummalqura.MONTH_STARTS
    assert ummalqura.HIJRI_RANGE[0][0] == 1343 and ummalqura.HIJRI_RANGE[1][0] == LAST_YEAR
    skip = (FIRST_YEAR - 1343) * 12
    # MONTH_STARTS ends with 1 Muharram of the year after the last, so the last month's length is there too.
    lengths = [b - a for a, b in zip(starts[skip:], starts[skip + 1:])]
    assert set(lengths) <= {29, 30}, set(lengths)
    assert len(lengths) == (LAST_YEAR - FIRST_YEAR + 1) * 12, len(lengths)
    first = Hijri(FIRST_YEAR, 1, 1).to_gregorian()
    bits = ''.join('1' if n == 30 else '0' for n in lengths)
    bits += '0' * (-len(bits) % 4)
    hexes = ''.join('%x' % int(bits[i:i + 4], 2) for i in range(0, len(bits), 4))
    chunks = [hexes[i:i + 96] for i in range(0, len(hexes), 96)]
    print(f'''    /** The first month of [UMM_AL_QURA] (months since 1 Muharram 1 AH), its first day, and how many it has. */
    private const val TABLE_FIRST_MONTH = ({FIRST_YEAR}L - 1) * 12
    private val TABLE_FIRST_DAY: LocalDate = LocalDate.of({first.year}, {first.month}, {first.day})
    private const val TABLE_MONTHS = {len(lengths)}

    /**
     * The Umm al-Qura calendar's month lengths, 1 Muharram {FIRST_YEAR} to the end of {LAST_YEAR}, from
     * hijridate's table: a month's bit, most significant first in each hex digit, is 1 for 30
     * days, 0 for 29.
     */
    private const val UMM_AL_QURA =''')
    for i, c in enumerate(chunks):
        print(f'        "{c}"' + (' +' if i < len(chunks) - 1 else ''))


if __name__ == '__main__':
    main()
