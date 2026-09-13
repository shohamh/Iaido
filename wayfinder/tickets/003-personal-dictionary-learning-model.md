---
id: 3
title: Personal dictionary & on-device learning data model
type: grilling
status: closed
assignee: agent
blocked_by: [1, 2]
---

## Question

Design how user corrections and manually added words feed back into future predictions: data model, storage format, how learned weights blend with the base dictionary's frequency data, and how this stays strictly on-device.

## Resolution

**Data model:**
- A separate `personal_overrides` Room table (words and n-grams, each with a boost value and usage-activity counters) layered on top of the bundled, immutable base dictionary/n-gram tables from the [dictionary research](002-dictionary-research.md) ticket.
- Keeping it separate means the base dictionary asset can be updated/re-shipped later without touching learned data, and "reset learning" is just clearing this one table.
- Final scoring combines `shapeScore + λ₁·log(baseFrequency · personalBoost) + λ₂·contextScore(baseNgram · personalNgramBoost)`, per the [gesture-recognition algorithm](001-gesture-algorithm-architecture.md) ticket's scoring formula.

**Learning signals** (all of these reinforce the personal overlay):
- Explicitly adding a word via an "add to dictionary" action.
- Picking a non-top suggestion from the suggestion strip after a swipe (positive signal for the picked word, implicit negative for the auto-committed top pick).
- Manually editing/retyping over a committed word.
- The flow-correction system's own silent corrections (reinforcing the word it changed *to*).
- Undoing a flow-correction (negative signal on the word it changed *to*, positive signal restoring the original).
- **Deleting a word and retyping it differently**: treated as a strong signal — negative for the deleted word, positive for the replacement, and it additionally amplifies the surrounding n-gram context (the words before/after), since this pattern indicates the original word didn't fit the sentence context.

**Boost growth/decay:**
- Each use increases a word/n-gram's personal boost with diminishing returns (capped, so no single word can dominate scoring indefinitely).
- Decay is **usage-activity-based, not time-based**: a boost decays relative to how much *other* typing has happened since it was last reinforced (e.g. a counter of words-typed-since-last-use), not elapsed wall-clock time. A keyboard left unused for a month loses nothing; a word that simply falls out of use relative to your ongoing typing gradually fades.

**Backup/restore:**
- Relies entirely on Android's standard app-data backup (Auto Backup / Backup Service) to carry the Room database (including `personal_overrides`) across device restores — no custom export/import format. The app's backup rules must explicitly include the Room database file.

**User control:**
- Settings expose both an all-or-nothing "reset learning" (clears `personal_overrides` back to the bundled baseline) and a per-word "forget this word" action for removing a single mis-learned entry.
