---
id: 19
title: Release packaging (app icon, store listing, publishing path)
type: grilling
status: closed
assignee: agent
blocked_by: []
---

## Question

Decide what release packaging Iaido needs: app icon/branding, whether and how to prepare for eventual publishing (Play Store / F-Droid / open source), and how this interacts with the already-decided dev-distribution approach (dynamic `core-engine` loading, which is incompatible with Play distribution).

## Resolution

- **App icon/branding**: placeholder icon for now — no real branding investment until there's a concrete reason (an actual publish decision) to justify it.
- **Publishing path**: GitHub Releases is the real distribution channel for now, doubling as both the dev-hot-update mechanism (from the [dev-distribution ticket](017-dev-distribution-hot-reload.md)) and the release channel. F-Droid isn't a target. **Google Play Store is a genuine future possibility, not merely hypothetical** — this keeps the previously-flagged tension live rather than dismissed: the [dev-distribution ticket](017-dev-distribution-hot-reload.md)'s `DexClassLoader`-based dynamic `core-engine` loading is incompatible with Play's policy against loading executable code from outside the installed APK. If Play publishing is pursued later, `core-engine`'s update mechanism will need reworking to ship via Play's own update path instead — an explicitly known, accepted future cost, not a surprise to rediscover then.
- **Versioning**: adopt lightweight semantic-versioning tags (`v0.1.0`, etc.) on GitHub Releases from the start — effectively free, and prevents "which build is this" confusion once `core-engine` and `app` can update independently of each other.
