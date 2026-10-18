# Context-aware swipe sequence evaluation

## Method

The benchmark replayed the current English and Hebrew NKG2 assets through the one-word-per-gesture decoder and its six-gesture rolling window. It used the checked-in held-out phrase sets and deterministic synthetic swipes. English had 400 held-out phrases (1,617 swiped words) and 18 targeted phrases (480 words across repeated noisy swipes). Hebrew had 300 held-out phrases (1,190 words). Candidate generation, path scoring, and the window size stayed fixed.

The no-context comparison uses the same decoder and candidates with an empty n-gram store. “Recognizer-only” is the gesture recognizer's raw top candidate. Phrase exactness is the percentage of decoded swipe groups exactly matching their reference words.

Sequence word accuracy compares each decoded word with the reference at the same swipe position; it does not use longest-common-subsequence alignment. (The separate split/join benchmark keeps LCS alignment because its output can change word boundaries.) Candidate recall counts whether the correct reference word appeared anywhere in that gesture's available candidates. A harmful revision changes a previously correct visible word to an incorrect one after a later swipe; a recovered revision changes a previously incorrect word to the reference. A previously correct word is considered high confidence when it was the recognizer's top candidate with a score gap of at least 1.0 over the next distinct word.

## Shipped-weight results

| Language / set | Reference words | Sequence word accuracy | No-context sequence | Recognizer-only | Phrase exact: sequence / no-context | Candidate recall | Harmful / recovered revisions | High-confidence correct words changed |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| English held-out | 1,617 | 81.14% | 75.63% | 75.76% | 47.3% / 35.5% | 90.54% (1,464/1,617) | 13 / 61 | 4 / 1,644 observations |
| English targeted | 480 | 87.50% | 81.67% | 81.25% | 67.8% / 52.6% | 93.33% (448/480) | 0 / 10 | 0 / 427 observations |
| Hebrew held-out | 1,190 | 65.13% | 60.42% | 61.51% | 22.3% / 14.3% | 91.68% (1,091/1,190) | 26 / 61 | 5 / 815 observations |

The rolling decoder improves word accuracy over the no-context run by 5.51 percentage points on English held-out phrases and 4.71 points on Hebrew. English target phrases improve by 5.83 points. Most English target changes were recoveries; the held-out sets show some harmful revisions, including a small number of changes to previously correct high-confidence choices.

Candidate recall bounds what context can fix: in these runs, roughly 8–9% of reference words were absent from the recognizer candidate lists.

## Weight tuning

The `seq-grid` pass tuned on the separate tune phrase sets, then evaluated the selected configuration on held-out phrases:

| Language | Tune result | Held-out result | Targeted result | Selected benchmark settings |
|---|---:|---:|---:|---|
| English | 80.73% | 81.51% | 87.08% | bigram 0.50, trigram 0.50, margin 0.25, residual trigram, frequency 0.05 |
| Hebrew | 67.11% | 66.47% | n/a | bigram 0.30, trigram 0.15, margin 0.25, floor −1, frequency 0.05 |

The weight searches were rerun after correcting sequence accuracy to compare each output against its swipe position. They reproduced the selected language-specific settings and scores above. The English tune improved held-out accuracy by 0.37 points but reduced targeted accuracy by 0.42 points; its selected residual-trigram mode is not exposed by the production scorer. Hebrew improved by 1.34 points on held-out phrases. A shared trial configuration reached 81.45% English held-out, 87.08% English targeted, and 65.80% Hebrew held-out, versus shipped-weight results of 81.14%, 87.50%, and 65.13%. The shared trial also caused 3 harmful changes on targeted English phrases, versus 0 with shipped weights. These differences do not support changing the shared runtime weights: the optima differ by language and targeted English accuracy regresses. The runtime weights remain unchanged.

## Limits and next measurement

These are synthetic swipes over checked-in phrases, not user typing. The benchmark measures sequence ranking and revisions, not real editor replacement timing. App tests exercise the exact-source, caret, cursor-movement, and replacement-failure paths; a device-backed run is still needed to measure `InputConnection` latency and real-host replacement failures. The English targeted result is aggregated across 18 phrases, so it does not isolate the individual “today of all days” phrase.

## Review corrections

The final read-only review found that split options could consume the group cap before the one-word policy filtered them, causing the normal-mode decoder to fall back to raw recognizer choices. The decoder now generates one-word groups before pruning. It also found that a capitalized frozen sentence-start word could miss lowercase n-gram keys; scoring now normalizes case while rendered text remains capitalized. Finally, sequence accuracy now aligns by swipe position, and generator cache settings fingerprint dictionary order, vocabulary membership, and conversation-source membership so cached integer IDs cannot be reused for a different dictionary.
