# Known issues

Open bugs and technical debt, each with where it lives, how to reproduce it, and the proper fix.

None open.

## Limits

What the app does not do, by design or for want of something it would need.

- **Date phrases are English only** (`core/search/DatePhrases`), as the models are. Holidays are
  those listed in `design.md` (Christmas, New Year, Halloween, Valentine's Day, the Fourth of
  July, Easter, Thanksgiving); others (Diwali, Lunar New Year, Eid) would need their calendars.
- **Days are the phone's time zone's now**: a picture taken abroad near midnight counts for the
  neighbouring day. MediaStore keeps the moment taken, not the zone it was taken in.
- **The GPU was measured on one chip**, the Snapdragon 8 Gen 1 (Adreno 730). Others get the
  same thresholds and memory budgets (`EasyOcr` defaults, `AiEngine.gpuPixels`); a newer Adreno
  is likely faster, an older one could be slower than its CPU at some sizes. A GPU that
  computes the detector wrongly is caught by `acceleratorAgrees` and left unused.
