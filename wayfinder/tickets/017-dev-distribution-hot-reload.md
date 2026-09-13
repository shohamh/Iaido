---
id: 17
title: Fast-iteration distribution to physical phone (auto-update, hot reload)
type: grilling
status: closed
assignee: agent
blocked_by: []
---

## Question

Design how NinjaKeys gets onto the user's Galaxy S25 during development with minimal friction: auto-updating and as much hot-reloading as is realistically achievable for an Android IME, given IMEs have real platform constraints on live code swapping. This is about the personal development/iteration loop, distinct from — and not to be confused with — eventual public release/store packaging (which stays explicitly out of scope until publishing is actually pursued).

## Resolution

**Two-tier update mechanism, split by module:**

- **`core-engine` (pure Kotlin, no Android deps)**: built as a separate `.jar`/`.dex`, loaded at runtime via `DexClassLoader`/`PathClassLoader`. Updates to scoring weights, the algorithm, or dictionary data can be pushed to the phone and picked up by the already-installed app **without a full APK reinstall or IME restart** — genuinely unattended/silent self-update. Checked periodically against **GitHub Releases** (this repo's own releases/a small manifest), avoiding any separate self-hosted update infrastructure.
- **`app` module (UI, manifest, IME service structure)**: requires a full APK install to change. Auto-checked and auto-downloaded unattended from GitHub Releases, but Android requires one explicit tap to confirm install (`REQUEST_INSTALL_PACKAGES`) on a normal, non-rooted phone — genuinely silent installs would require rooting the daily-driver S25, which isn't worth it for this.

**Active-development hot reload**: during active dev sessions, use Jetpack Compose Live Edit / Android Studio's "Apply Changes" as the fast path for UI and logic changes, automatically falling back to a full ADB reinstall whenever a change requires it (lifecycle/manifest/permission changes) — this is the real ceiling Android's tooling offers for a native IME, not something to fight past.

**Wireless deployment**: the ADB-based dev-loop (installs, Apply Changes, log streaming) works over wireless ADB (Wi-Fi debugging pairing), not just USB — needed because testing a keyboard requires holding and using the phone naturally while iterating.

**Play Store tension (explicit, deferred)**: Google Play prohibits apps distributed via Play from loading executable code from outside the installed APK — meaning the `core-engine` dynamic-loading mechanism above is incompatible with Play distribution. This is accepted as a *future* tradeoff, not a blocker now: NinjaKeys today is sideloaded and personal, so optimizing for actual current workflow wins over a hypothetical future distribution channel. If/when Play publishing is ever pursued, `core-engine`'s update mechanism would need reworking to ship via Play's own update mechanism instead — consistent with the map's existing "structure for publishing later, don't build for it yet" stance.
