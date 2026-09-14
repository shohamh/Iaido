---
id: 20
title: Research a stable English and Hebrew offline word-list source
type: research
status: closed
assignee: agent
blocked_by: []
---

## Question

Choose a stable, high-quality English and Hebrew word-list source for
Iaido' offline Android IME context model. Verify the real download URLs,
approximate artifact sizes, the available coverage, and whether the source's
normalization rules fit the keyboard's English and Hebrew layouts.

## Resolution

Use the tagged `rspeer/wordfreq` v3.0.2 data files for both languages. The
release is pinned to commit
[`372f6dbb3bd2ad4b675a3b18b94d44d0dd5fea8b`](https://github.com/rspeer/wordfreq/commit/372f6dbb3bd2ad4b675a3b18b94d44d0dd5fea8b),
so builds do not follow the mutable `master` branch. The upstream README
documents English and Hebrew support, the `small` and `large` coverage tiers,
frequency-ranked lookup, and the source/data licensing information:
[`wordfreq` v3.0.2 README](https://github.com/rspeer/wordfreq/blob/v3.0.2/README.md).

### Verified source artifacts

The URLs below are the exact tagged raw downloads checked on 2026-09-14. The
HTTP `Content-Length`, decompressed size, SHA-256, and word count were measured
locally after downloading to a temporary directory. The payload is the
upstream cBpack format (`msgpack` compressed with gzip), not a line-oriented
text file.

| Language | Tier | Download | Compressed | Uncompressed | Entries | SHA-256 |
| --- | --- | --- | ---: | ---: | ---: | --- |
| English | small | [`small_en.msgpack.gz`](https://raw.githubusercontent.com/rspeer/wordfreq/v3.0.2/wordfreq/data/small_en.msgpack.gz) | 124,892 bytes (~122 KiB) | 232,074 bytes (~227 KiB) | 28,917 | `F94A80CBA6A3857B260D0666B5432BB7EA9B85315574DEE9C306E87F61298247` |
| Hebrew | small | [`small_he.msgpack.gz`](https://raw.githubusercontent.com/rspeer/wordfreq/v3.0.2/wordfreq/data/small_he.msgpack.gz) | 252,281 bytes (~246 KiB) | 685,713 bytes (~670 KiB) | 58,370 | `B68A4D94DBDA037255C3992D4C3AE7250AC6A67AEDCF90246AFC020681A462E5` |
| English | large | [`large_en.msgpack.gz`](https://raw.githubusercontent.com/rspeer/wordfreq/v3.0.2/wordfreq/data/large_en.msgpack.gz) | 1,494,836 bytes (~1.43 MiB) | 2,646,278 bytes (~2.52 MiB) | 321,180 | `DFFAE8066B78DCE0A6667CF5F58E567054F902674667090A7AC8A8A44628B05C` |
| Hebrew | large | [`large_he.msgpack.gz`](https://raw.githubusercontent.com/rspeer/wordfreq/v3.0.2/wordfreq/data/large_he.msgpack.gz) | 2,891,674 bytes (~2.76 MiB) | 7,756,144 bytes (~7.40 MiB) | 591,944 | `4B35EF0A8E6226E4F062D3220C9B2982F4C70A5C90F60864E16E7ED98BBFB3D9` |

The two `small` artifacts together are about 377 KiB compressed and 896 KiB
uncompressed. The two `large` artifacts together are about 4.18 MiB
compressed and 9.92 MiB uncompressed. These measurements are source-artifact
sizes, before conversion to Iaido' compact runtime dictionary/trie format.

### Recommendation for Iaido

Start with the two `small` lists as the default bundled vocabulary. Upstream
defines `small` as words appearing at least once per million words and `large`
as the broader tier reaching at least once per hundred million words. The
small bilingual pair already supplies roughly 87,000 ranked entries while
keeping the download and on-device memory budget modest. Keep the two `large`
files as the optional build-time expansion source; do not bundle them by
default until profiling shows that their additional long tail improves swipe
candidate quality enough to justify the larger trie.

The build pipeline should:

1. Pin v3.0.2 and verify the recorded SHA-256 before decoding.
2. Decode the cBpack buckets using a build-time tool, preserving descending
   frequency order or converting it to the engine's explicit frequency field.
3. Filter entries to characters supported by each keyboard layout. The source
   includes numbers, emoji, punctuation-bearing forms, and cross-language
   tokens; for the current letter-only swipe layouts, keep ASCII letters for
   English and Hebrew letters (`U+05D0`–`U+05EA`) for Hebrew. Preserve
   apostrophe-bearing forms only if the punctuation/alternate-key design later
   makes them directly typeable.
4. Apply the same normalization to lookup input and generated assets. Do not
   silently use a generic ASCII-only lowercasing pass for Hebrew.

### Normalization fit

The upstream implementation is unusually useful as a normalization contract:
[`language_info.py` at v3.0.2](https://github.com/rspeer/wordfreq/blob/v3.0.2/wordfreq/language_info.py)
selects NFC for Latin-script languages such as English and retains the default
NFKC for Hebrew, while enabling mark removal for Hebrew. The corresponding
[`preprocess.py` implementation](https://github.com/rspeer/wordfreq/blob/v3.0.2/wordfreq/preprocess.py)
normalizes Unicode, removes Unicode combining marks for abjad scripts, and
case-folds before token lookup.

For Iaido this means:

- English lookup/assets: Unicode NFC, then case-fold; retain only the layout's
  supported alphabetic tokens for the current dictionary asset.
- Hebrew lookup/assets: Unicode NFKC, remove combining marks such as niqqud,
  then case-fold (which is harmless for Hebrew); retain Hebrew-letter tokens.
- Keep output spelling separate from lookup normalization so a future
  punctuation/diacritic feature can display the user's intended form without
  changing the base frequency key.

### Why this source

`wordfreq` is preferable here to separate English and Hebrew lists because it
uses one frequency representation and one documented preprocessing contract
for both languages. Its official README states that the data combines several
corpus domains and that the language tables include both `en` and `he`. The
tagged raw artifacts are small enough for build tooling, stable enough to hash,
and already frequency-ranked, so the Android app only needs a one-time offline
conversion rather than parsing the upstream Python package at runtime.

The existing `wayfinder/tickets/002-dictionary-research.md` remains useful for
the separate question of English/Hebrew n-gram sources. This ticket resolves
the bilingual single-word vocabulary source and its reproducible download
contract; it does not claim that these word lists are themselves word-bigram or
trigram data.

## Primary sources

- [`wordfreq` v3.0.2 release](https://github.com/rspeer/wordfreq/releases/tag/v3.0.2)
- [`wordfreq` v3.0.2 README](https://github.com/rspeer/wordfreq/blob/v3.0.2/README.md)
- [`wordfreq` v3.0.2 language metadata](https://github.com/rspeer/wordfreq/blob/v3.0.2/wordfreq/language_info.py)
- [`wordfreq` v3.0.2 preprocessing](https://github.com/rspeer/wordfreq/blob/v3.0.2/wordfreq/preprocess.py)
- [Tagged English small data](https://raw.githubusercontent.com/rspeer/wordfreq/v3.0.2/wordfreq/data/small_en.msgpack.gz)
- [Tagged Hebrew small data](https://raw.githubusercontent.com/rspeer/wordfreq/v3.0.2/wordfreq/data/small_he.msgpack.gz)
- [Tagged English large data](https://raw.githubusercontent.com/rspeer/wordfreq/v3.0.2/wordfreq/data/large_en.msgpack.gz)
- [Tagged Hebrew large data](https://raw.githubusercontent.com/rspeer/wordfreq/v3.0.2/wordfreq/data/large_he.msgpack.gz)
