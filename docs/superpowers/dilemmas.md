# Open Dilemmas

This file records decisions that may need product-owner input. Entries are kept
until answered or intentionally resolved by the implementation.

## 2026-09-14

- Stage 3 data scope: the resolved ticket names English and Hebrew wordfreq
  data, but Stage 3's roadmap demo explicitly targets real English first. I am
  implementing the English 25k baseline now and leaving the Hebrew source for
  Stage 5, unless you want Hebrew bundled earlier.
- Stage 4 keyboard surface: the current Stage 2 view has no bottom-row space,
  punctuation, or backspace controls. I am adding the smallest complete English
  surface needed by the ticket (letters, comma, period, question mark, quote,
  space, backspace, and number flicks) and will record any later UX choices
  here rather than blocking implementation.
- Stage 4 long-press and mixed-word behavior: the pure policy seam supports
  accent resolution, but Android long-press timing and tap/swipe accumulation
  still need instrumented validation and completion.
- Stage 5 asset reproducibility: Hebrew export currently uses the locally
  installed `wordfreq` package; before release, pin that tool version and add a
  dependency/bootstrap check to the data pipeline.
- Stages 6-12: command execution/settings persistence, personal learning,
  contextual correction, split-word gestures, theming, test infrastructure,
  and release packaging are not yet implemented.
- Stage 8 context data: the runtime correction seam is wired with a small,
  conservative built-in context seed so the feature is deterministic; the
  production n-gram asset still needs an approved source/license and size
  budget. Please choose the approved corpus and whether Hebrew ships with the
  first production model.
- Stage 9 split typing: the grace window is currently a fixed 350ms in the
  controller and the keyboard keeps space-started multi-finger commands. If
  you want the grace duration or command/split boundary exposed in Settings,
  that should be added to the Stage 10 typing tunables.
- Stage 12 runtime updates: the ticket selects GitHub Releases plus
  `DexClassLoader`, but the repo does not yet define a signed release manifest,
  public-key trust root, version/rollback rule, or behavior when an artifact is
  unavailable or fails verification. I have completed the reproducible JAR
  packaging and checksum contract; please approve those security and fallback
  policies before a silent executable-code updater is added.
- Stage 7 manual-edit signal: the core and Room APIs accept `MANUAL_EDIT`, but
  Android's IME contract does not provide a reliable generic callback for text
  edits performed by the host app. I have left this signal available without
  falsely classifying ordinary keyboard taps as manual edits.
