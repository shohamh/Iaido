# Pinned bilingual wordfreq source

NinjaKeys bundles the letter-only CSV assets generated from the tagged
`rspeer/wordfreq` v3.0.2 `small_en` and `small_he` cBpack files. The source
files are kept here so dictionary generation is reproducible offline:

- `wordfreq-v3.0.2-small-en.msgpack.gz` — 28,917 source entries
- `wordfreq-v3.0.2-small-he.msgpack.gz` — 58,370 source entries

Run `python tools/import_wordfreq_dictionaries.py` from the repository root to
regenerate `app/src/main/assets/dictionary/en.csv` and `he.csv`. The converter
keeps ASCII letters for English and Hebrew letters for Hebrew, applies the
source-compatible NFC/NFKC normalization, removes Hebrew combining marks, and
preserves the ranked frequency values.

The pinned source is commit `372f6dbb3bd2ad4b675a3b18b94d44d0dd5fea8b`:
https://github.com/rspeer/wordfreq/tree/v3.0.2. The data is distributed under
the source project's documented Creative Commons terms; retain the upstream
attribution when redistributing these assets.

Source SHA-256:

- English: `F94A80CBA6A3857B260D0666B5432BB7EA9B85315574DEE9C306E87F61298247`
- Hebrew: `B68A4D94DBDA037255C3992D4C3AE7250AC6A67AEDCF90246AFC020681A462E5`

The prior `wordfreq-en-25000-log.json` export is retained for historical
comparison only; it is not used by the Android dictionary build.

## Offline context model

`tools/generate_ngrams.py` builds `app/src/main/assets/context-ngrams/` from
the pinned large `wordfreq` vocabularies and the Leipzig 2020 1M English and
Hebrew news sentence corpora. It keeps the top 10,000 dictionary words as
possible successors, limits preceding-word contexts to the top 2,000, keeps
up to 32 successors per context, and stores delta-varint IDs with UInt16
log-count scores in the NKG1 binary format. The generated manifest records
source/output hashes and normalization details.

The generated context assets are approximately 6.2 MiB (English) and 3.8 MiB
(Hebrew). The Leipzig corpora are build-time inputs only and are not bundled.
The wordfreq data is CC BY-SA 4.0; Leipzig corpus downloads are CC BY. Keep
the corresponding upstream attribution when redistributing the APK.
