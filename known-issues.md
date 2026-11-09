# Known issues

Open bugs and technical debt, each with where it lives, how to reproduce it, and the proper fix.

- **Redescribing a file during a long run leaves it undescribed until that run ends.**
  `GalleryViewModel.redescribe` forgets the description, then asks for a user run; a run's lists
  are made as it starts, so the file waits for the run queued after the one going, which behind
  a library's first run is hours, the file unsearchable all that time. Reproduce: start
  describing a large library, open a described photo, Describe again: its description stays
  empty. Fix: describe that one file at once, beside the run going (the models are shared and
  `AiEngine` takes one file at a time), and keep the old description until the new one is saved.

## Limits

What the app does not do, by design or for want of something it would need.

- **Date phrases are English only** (`core/search/DatePhrases`), as the models are. Holidays are
  those listed in `design.md`; others (Navratri, Vesak, Onam, Nowruz...) would each need their
  calendar's rule. Where a lunar holiday's day depends on sighting the moon or on local time, the
  rule is one country's (Umm al-Qura for Islamic months, India's time for Diwali and Holi,
  China's for the Lunar New Year, Vietnam's for Tết), and the span reaches a day either side for
  the rest. Islamic months before December 1945 and after 2077, outside Umm al-Qura's table, are
  computed and can be a day out. The Chinese calendar is checked against published tables for
  1900-2099 only; in it, 1978's Mid-Autumn Festival is the 17th here (its month's new moon came 9
  minutes after midnight) and was kept on the 16th, which its span holds. Lakshmi Puja and
  Holika Dahan are reckoned from the moon's phases alone, not the tithi's start or karana, which
  panchangs also weigh: an evening when the new or full moon comes within minutes of sunset can
  be a day from theirs (Lakshmi Puja 2037), and the span holds both evenings.
- **Days are the phone's time zone's now**: a picture taken abroad near midnight counts for the
  neighbouring day. MediaStore keeps the moment taken, not the zone it was taken in.
- **Places are GeoNames' names in English**, of places of 1000 people or more: a beach, a park
  or a landmark is found by the town, city, region or country around it ("waikiki" is part of
  Honolulu's), not its own name, and "hawaii" in another language is not read. Places whose name
  is also an ordinary word need "in", "at", "near"... (`PlacePhrases`).
- **The GPU was measured on one chip**, the Snapdragon 8 Gen 1 (Adreno 730). Others get the
  same thresholds and memory budgets (`EasyOcr` defaults, `AiEngine.gpuPixels`); a newer Adreno
  is likely faster, an older one could be slower than its CPU at some sizes. A GPU that
  computes the detector wrongly is caught by `acceleratorAgrees` and left unused.
