# Open Dilemmas

This file records decisions that still need product-owner input. Resolved
choices are recorded in the stage plans and research tickets instead of being
left here as false blockers.

## Resolved on 2026-09-14

- Stage 3/8 vocabulary: bundle both English and Hebrew from pinned
  `rspeer/wordfreq` v3.0.2 `small_*` data. The source files and hashes are in
  `tools/data/README.md`; generated assets are letter-only and normalized per
  language.
- Stage 9 timing and visibility: keep the 350ms default within the existing
  300–400ms setting range, and show the live best-effort split word above the
  keyboard throughout the gesture and grace window.
- Stage 12 trust policy: use HTTPS GitHub Releases, a canonical manifest,
  Ed25519 signatures with the APK-embedded public key, newer-semver gating,
  atomic current/previous storage, rollback on load failure, and silent
  fallback to the current engine on update failure. The private signing key is
  local at `secrets/core-engine-update-ed25519-private.pem` and ignored by Git.
- Manual-edit learning: monitor extracted text only for non-password text
  fields, suppress expected Iaido edits, show external changes as a
  confirmation candidate, and record `MANUAL_EDIT` only after confirmation.

## Resolved on 2026-09-14 (continued)

- Stage 12 release operations: use the repository's GitHub Releases endpoint and
  check at most once per 24 hours. A failed check is silent and the APK's
  compile-time engine remains available; a verified staged entrypoint is used
  on the next swipe.
