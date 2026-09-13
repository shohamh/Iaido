---
id: 1
title: Gesture-recognition algorithm architecture
type: grilling
status: closed
assignee: agent
blocked_by: []
---

## Question

Design the pluggable gesture-recognition interface (so the matching algorithm can be swapped later) and the initial concrete algorithm: classic path-matching of a drawn trace against a weighted dictionary/trie, in the style of Swype/Nintype. Covers: how a gesture path is sampled/normalized, how candidate words are scored against the path, how ties/ambiguity are resolved, and the shape of the interface a future alternate algorithm would implement.

## Resolution

**Interfaces (in `core-engine`, pure Kotlin):**
- `CandidateGenerator`: gesture path → shortlist of plausible dictionary words. Implemented by traversing the dictionary trie, at each letter checking whether the path passes near that letter's key (proximity/ordering threshold), pruning to a compatible shortlist cheaply.
- `PathScorer`: (path, shortlist, context) → ranked list of scored candidates. Swappable independently of `CandidateGenerator`.
- Both are separately swappable, so a future alternate pruning or scoring strategy can replace just one stage.

**Path handling:**
- Raw touch points are resampled to a fixed number of evenly-spaced points along the path (arc-length resampling), then normalized in position/scale relative to the current keyboard layout, so shape comparison is independent of finger speed or keyboard size.

**Scoring:**
- Base shape score: DTW-style elastic distance between the normalized user path and an "ideal path" (straight lines connecting the candidate word's successive key centers).
- Corner-matching bonus/penalty term on top of raw path distance, to capture Swype's corner-sensitivity (distinguishing words with similar overall shape but different turning points).
- Combined with: word frequency weight (from the base dictionary), personal on-device learning weight (from corrections/additions), and **contextual n-gram fit** using the previous words as context.
- Final score is a weighted combination (e.g. `finalScore = shapeScore + λ₁·log(frequency·personalBoost) + λ₂·contextScore`), with all weights (λ's, corner-bonus weight, proximity thresholds) exposed as tunable constants/config in `core-engine`, not hardcoded magic numbers.

**Context window:**
- Previous-words context defaults to 2 words (trigram-level), configurable up to 3.
- Context scoring uses a classical n-gram frequency table (not custom-trained ML) — see the dictionary research ticket, which now also covers sourcing n-gram frequency data. Architecture leaves room to later swap in an existing pretrained on-device language model behind the same interface if the n-gram approach proves insufficient — no custom model training is in scope.
- Personal usage reinforces n-gram weights over time the same way single-word learning works (see the personal dictionary/learning ticket).

**Timing:**
- Incremental recognition during the drag (recompute best-guess periodically, e.g. every ~30-50ms or every N new points) for live visual feedback.
- Full authoritative recognition at finger-lift, target <50ms, benchmarked on the Galaxy S25.
- The interface supports both a "partial path so far" call (incremental) and a "final path" call (authoritative).

**Ambiguity / output:**
- Recognizer output exposes the full ranked list (top-5 default, tunable) with scores — not just the winner — so the UI can show a suggestion strip and the learning system can use rank/score data.
- On finger lift, the top-ranked candidate auto-commits; the suggestion strip lets the user tap an alternative.

**Split off as a new ticket:** background "flow correction" (re-scoring already-committed words using fuller sentence context and silently correcting past a margin threshold, with a flash/highlight on change) and the gestures for reviewing/undoing those corrections on committed text — this is a distinct interaction surface (gestures on already-typed text, not the keyboard), not part of this ticket. See [Flow correction & text-correction gestures](011-flow-correction-and-undo-gestures.md).
