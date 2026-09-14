# Iaido offline bilingual n-gram model

## Goal

Generate English and Hebrew bigram/trigram assets offline from the pinned
large `wordfreq` vocabularies plus Leipzig 1M sentence corpora, and consume
them in the Android IME without materializing Kotlin maps of all n-grams.

## Design decisions

- `wordfreq` large files are vocabulary/frequency filters; Leipzig sentence
  files provide ordered sequences.
- Normalize input at build time with the existing language-specific contract:
  English NFC + case-fold + ASCII letters; Hebrew NFKC + case-fold + remove
  combining marks + Hebrew letters only.
- Count sentence-internal bigrams and trigrams, using sentence boundaries only
  to prevent cross-sentence n-grams. Keep candidates that are in the large
  vocabulary, have a configurable minimum count, and are among the top 32
  successors for each context.
- Store deterministic binary files with a small header, fixed-width context
  indexes, and delta-varint successor IDs plus UInt16 quantized log-count
  scores. Runtime lookup scans at most 32 edges and uses the dictionary CSV as
  the word-to-ID contract.
- Keep source archives and the temporary count database outside the repository;
  commit only the generator, runtime reader, tests, manifests/documentation,
  and generated APK assets if they fit the agreed size budget.

## Implementation steps

1. Specify and test the binary format and an in-memory reference store. Add a
   `NgramScoreStore` seam, retain compatibility with existing map-based tests,
   and make `NgramContextScorer` consume either map data or the compact store.
2. Implement `tools/generate_ngrams.py` with pinned source metadata, download
   and SHA-256 verification, wordfreq decoding, streamed Leipzig extraction,
   bounded temporary SQLite aggregation, top-K pruning, quantization, and a
   reproducible manifest. Support local source paths for offline reruns.
3. Add generator fixture tests covering normalization, sentence boundaries,
   vocabulary filtering, deterministic output, varint encoding, and top-K
   selection. Add JVM tests for compact reader lookup, missing contexts, and
   malformed headers/versions.
4. Wire the app to load the English/Hebrew binary assets and construct the
   language-specific compact scorer. Keep asset loading lazy and fall back to
   the existing empty/default behavior if an optional asset is absent.
5. Generate the real bilingual assets from the pinned sources, record source
   and output hashes/counts, and add only the resulting compact assets and
   required attribution/readme updates.
6. Run focused Python and Gradle tests, then build the debug APK and inspect
   asset sizes. Report any checks that are unavailable or fail for unrelated
   pre-existing WIP.

## Verification

- Python unit tests for the generator pass.
- Core JVM tests pass, including all existing n-gram/scoring tests.
- App JVM tests pass.
- Debug APK assembles with both language assets present.
- Generated manifest hashes and byte counts match the checked-in files.
