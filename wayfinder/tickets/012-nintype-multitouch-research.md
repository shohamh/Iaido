---
id: 12
title: Research how Nintype's multi-finger gesture typing actually worked
type: research
status: closed
assignee: agent
blocked_by: []
---

## Question

The initial assumption (independent concurrent per-finger word-gesture paths, each committing on lift) was flagged by the user as incorrect. Read [maxleiter.com/blog/nintype](https://maxleiter.com/blog/nintype) and the [lurebat/keyboard71](https://github.com/lurebat/keyboard71) repository (a modern reimplementation/reference) to correctly understand Nintype's actual multi-finger swipe mechanic, before designing Iaido' version of it.

## Resolution

**Corrected understanding — it is not two independent concurrent words, it's one word split across simultaneous partial gestures:**

- Nintype let you type a **single word** using **two hands/fingers at once**, each contributing a *portion* of that same word's letters, concurrently rather than sequentially. The article's example: for "there," you swipe "th" with one hand while simultaneously swiping/tapping "ere" with the other — both partial gestures resolve together into the one word "there."
- Gestures aren't purely continuous swipes either: **discrete taps can be mixed into the input**, specifically called out as the mechanism for doubled letters (e.g. "I'll" — swipe the first part, then double-tap "l" to finish), which is a known weak spot of pure continuous-path gesture typing (a repeated letter doesn't show up distinctly in one smooth path).
- Reported result: ~100 WPM at ~90-95% accuracy, i.e. faster than sequential single-finger swiping, because both hands contribute to the *same* word concurrently instead of one hand doing all the work.
- The `lurebat/keyboard71` repo (a reimplementation) confirms the *existence* of this mechanic (native `libgl2jni.so` core + `NINLib.kt`/`SoftKeyboard` Kotlin wrapper) but the actual matching algorithm is in an undocumented native library — no further algorithmic detail (e.g. exactly how two partial letter-sequences are merged/ordered before dictionary lookup) was recoverable from the repo's public docs. This detail will need to be designed from scratch for Iaido, informed by this behavioral understanding rather than reverse-engineered from source.

**Implication for the multi-finger gesture ticket**: this is a core-engine extension — the recognizer needs to accept multiple concurrent partial paths/taps that together form one word's letter sequence, not a "gesture → discrete action" binding. It belongs with a ticket that extends the gesture-recognition architecture, not the command-gesture customization ticket.
