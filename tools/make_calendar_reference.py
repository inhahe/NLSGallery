"""Prints CalendarsTest's reference strings (core/src/test/.../search/CalendarsTest.kt, its
companion object): CHINESE, every Lunar New Year and Mid-Autumn Festival 1900-2099 as the Hong Kong
Observatory's tables have them (through lunardate, GPL: used here only, its output is dates), and
RAMADAN, 1 Ramadan 1365-1500 AH as Umm al-Qura's table has it (through hijridate, MIT).

pip install lunardate hijridate. Any Python 3.
"""
import warnings

from hijridate import Hijri
from lunardate import LunarDate

warnings.filterwarnings('ignore', category=DeprecationWarning)


def kotlin_string(text, width):
    chunks = [text[i:i + width] for i in range(0, len(text), width)]
    return '\n'.join(f'            "{c}"' + (' +' if i < len(chunks) - 1 else '') for i, c in enumerate(chunks))


def main():
    chinese = ''.join(
        LunarDate(y, 1, 1).to_solar_date().strftime('%m%d') + LunarDate(y, 8, 15).to_solar_date().strftime('%m%d')
        for y in range(1900, 2100))
    ramadan = ' '.join(str(Hijri(h, 9, 1).to_gregorian()).replace('-', '') for h in range(1365, 1501))
    print('        /** Lunar New Year and Mid-Autumn Festival, 1900-2099: MMDDMMDD a year. */')
    print('        const val CHINESE =')
    print(kotlin_string(chinese, 96))
    print()
    print('        /** 1 Ramadan, 1365-1500 AH, as yyyymmdd. */')
    print('        const val RAMADAN =')
    # Ten dates a line, each with its space, so the pieces join with one space between dates.
    print(kotlin_string(ramadan, 90))


if __name__ == '__main__':
    main()
