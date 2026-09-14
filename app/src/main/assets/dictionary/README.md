# Bilingual dictionary assets

`en.csv` and `he.csv` are generated from the pinned `rspeer/wordfreq` v3.0.2
small English and Hebrew cBpack files using
`tools/import_wordfreq_dictionaries.py`. Source URLs, hashes, normalization,
and license attribution are documented in `tools/data/README.md`.

Only lowercase ASCII alphabetic entries are bundled for English, and Hebrew
letter-only entries are bundled for Hebrew, because the current keyboard
layouts and candidate generator operate on letter keys.
