# Context-aware correction polish

## Goal

Improve swipe correction quality while keeping the sentence strip legible and making personal learning reflect corrections the user explicitly chose.

## Behaviors

1. Sequence replacement choices remain atomic, but an N-word to N-word choice is shown as one replacement token per source slot. Split choices show a compact first-token marker; split/join choices retain the existing grouped preview and commit path.
2. Candidate ranking scores the candidate with bounded evidence from words on both sides. Right context is limited to the scorer's n-gram window.
3. Sentence candidate ordering keeps the current word first unless an alternative beats it by a configurable confidence margin. Flow correction uses the same two-sided score with its existing margin gate.
4. Explicit suggestion picks and confirmed manual edits teach local neighboring word pairs. Automatic flow corrections do not teach. Persisted pair boosts are restored at startup and remain covered by the existing forget/reset lifecycle.

## Constraints

- Keep old n-gram assets compatible; no asset regeneration or network access is part of this change.
- Keep learning local to the existing Room database and English-only service path.
- Preserve split/join structured replacement semantics, editor offset handling, and existing correction history.
- Use conservative personal pair influence so corpus scores remain dominant.

## Validation

Add focused regressions for slot-aligned alternatives, left/right context ranking, confidence margin, explicit-only learning, persistence/restore, and reset/forget. Run core-engine and app unit suites plus the Python generator tests and `git diff --check`.
