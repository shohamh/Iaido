# Pinned bilingual wordfreq source

Iaido bundles the letter-only CSV assets generated from the tagged
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
the dictionary vocabularies and Leipzig 2020 1M English/Hebrew news corpora.
By default it also adds the English–Hebrew OPUS Moses sentence pairs from
[OpenSubtitles v2018](https://opus.nlpl.eu/opusapi?corpus=OpenSubtitles&source=en&target=he&preprocessing=moses&version=v2018)
and [QED v2.0a](https://opus.nlpl.eu/opusapi?corpus=QED&source=en&target=he&preprocessing=moses&version=latest).
The generation manifest records each source URL, downloaded byte count,
observed SHA-256, and the output hashes. OPUS's API supplies archive sizes but
not an expected SHA-256; the generator checks the byte count and records the
observed hash. Use `--exclude-conversation` for the news-only build, or repeat
`--conversation-corpus <local-moses-zip>` to use local archives instead of
downloading the defaults.

The conversational archives are build-time inputs and are not bundled. To
measure them without phrase leakage, generate a tuning asset with
`--conversation-train-parity 0`, then sample tuning and held-out phrases from
the disjoint conversation line groups with `tools/sample_phrases.py` using
`--first-sentence 0 --modulus 4 --parity 1` and
`--first-sentence 0 --modulus 4 --parity 3`, respectively. Pass each source
archive with a repeated `--conversation-corpus` option. For weight tuning, point the benchmark at the
sampled files with `IAIDO_SEG_TUNE_FILE` and `IAIDO_SEG_HELDOUT_FILE`, and set
`IAIDO_SEG=seq-grid`; this tunes word choice on separated synthetic swipes and
reports the held-out set separately. The parity split follows aligned sentence
lines, so both languages reserve the same examples.

The NKG2 assets store exact n-gram counts, context totals, and smoothed scores.
They keep up to 128 successors per bigram context, 32 per trigram context, and
up to 50,000 trigram contexts. The assets generated on 2026-10-18 use Leipzig
news, the Tatoeba linked-sentence exports, and the training half of a filtered
Hugging Face OpenSubtitles English-Hebrew shard. The shard is an alternate
hosting of OPUS-derived subtitle data, not an independent corpus. Its Parquet
file is pinned to dataset commit
`d86a387587ab6f2fd9ec7453b2765cec68111c87` (file last changed at
`f35743d5d25b564da46eb13db1ba9dd99cd23476`) and SHA-256
`b4558b11b37532c068bb92b8f65572d1a182e8d9c01602e00e4aae7bd98a3427`.
Filtering retained 3,203,514 bilingual pairs; the processed, balanced ZIP has
1,601,048 train and 1,601,048 held-out pairs. The generated asset manifest
records source and output hashes. These corpora are build-time inputs and are
not bundled in the APK.

The combined assets are 4,411,776 bytes (English) and 3,936,789 bytes
(Hebrew). Source hashes, filters, split details, and held-out benchmark results
are in [`docs/research/2026-10-18-conversational-corpus-sources.md`](../../docs/research/2026-10-18-conversational-corpus-sources.md).

Source facts and direct OPUS metadata queries are in
[`docs/research/2026-10-18-conversational-corpus-sources.md`](../../docs/research/2026-10-18-conversational-corpus-sources.md).
