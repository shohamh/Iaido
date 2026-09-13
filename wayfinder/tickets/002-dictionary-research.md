---
id: 2
title: Research English + Hebrew word-frequency dictionaries
type: research
status: closed
assignee: agent
blocked_by: []
---

## Question

Find open, license-compatible (personal use now, possibly published later) word-frequency dictionaries/wordlists for English and Hebrew suitable as the base vocabulary for swipe-typing prediction. Surface: candidate sources, license terms, format, size, and frequency-data quality (needed for scoring common vs. rare words).

**Scope extended**: also find sources for **n-gram (bigram/trigram) word-sequence frequency data** for English and Hebrew, needed for the contextual scoring in the [Gesture-recognition algorithm architecture](001-gesture-algorithm-architecture.md) ticket. Prefer existing/open data over anything requiring custom training.

## Resolution

**Single-word frequency data:**
- **English**: [`wordfreq`](https://github.com/rspeer/wordfreq) (rspeer) — MIT-licensed code, data redistributable under CC BY-SA 4.0, covers 40+ languages including English and Hebrew, sourced from Wikipedia, OpenSubtitles, Twitter, Google Books Ngrams, and web text. A ready-made [25,000-word English frequency list](https://github.com/aparrish/wordfreq-en-25000) export also exists. Note: wordfreq's data snapshot is from ~2021 and is not being updated further, which is fine for a frequency baseline but won't reflect newer slang.
- **English (vocabulary/coverage)**: [dwyl/english-words](https://github.com/dwyl/english-words) — 479k English words, good as a coverage/spellcheck backstop alongside a frequency-ranked core list.
- **Hebrew**: `wordfreq` also covers Hebrew (same sourcing as above). Alternative/supplementary: [Hebrew word lists extracted from Hspell 1.4 by Eyal Gruss](https://github.com/NNLP-IL/Hebrew-Resources) (AGPL-3.0 — **note**: AGPL is copyleft and would require open-sourcing the app if this specific dataset is bundled; only use if publishing as open source, otherwise prefer the CC BY-SA/CC0 sources below). [Wikidata Lexemes Hebrew data](https://github.com/NNLP-IL/Hebrew-Resources) (CC0) and a [CC0 public-domain Hebrew words database](https://github.com/roni5604/hebrew-words-db) (nouns/verbs/adjectives/slang) are cleaner license fits if publishing is a real possibility.
- General: the [Leipzig Corpora Collection](https://invokeit.wordpress.com/frequency-word-lists/) offers CC BY-4.0 frequency lists (10K–1M+ words) for 270+ languages including Hebrew, as a further cross-check source.

**N-gram (bigram/trigram) data:**
- **Google Books Ngram dataset** (standard, non-syntactic version) is CC BY 3.0 — **permits commercial use with attribution** — and includes 2-grams and 3-grams directly, for English. (The separate "syntactic ngrams" dataset is CC BY-NC-SA and must be avoided — non-commercial only.) This is large; only the relevant frequency-ranked slice would need to be extracted/shipped.
- No equivalent pre-built open n-gram dataset was found specifically for Hebrew. Recommended approach: build a bigram/trigram frequency table **at build time** from an open Hebrew text corpus rather than relying on a pre-made dataset — e.g. the [OpenSubtitles/OPUS corpus](https://opus.nlpl.eu/OpenSubtitles-v2018.php) has monolingual Hebrew and English plain text available for download, or Hebrew Wikipedia dumps. This also gives full control over corpus size/quality and sidesteps per-dataset license fragmentation, at the cost of a one-time offline processing step (not runtime training — consistent with "no ML training" preference).
- **Recommendation**: use Google Books Ngrams (CC BY 3.0) for English n-grams, and self-generate a Hebrew n-gram table from OPUS/Wikipedia text as part of build tooling (one-time, offline, no ML/training pipeline involved — just frequency counting).

**Format**: ship processed frequency tables (word/n-gram → count or log-probability) as a compact binary or SQLite asset bundled with the app, loaded into Room at first run — avoids parsing large raw text/CSV on-device.
