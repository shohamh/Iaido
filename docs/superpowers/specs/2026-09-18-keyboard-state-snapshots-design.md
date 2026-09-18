# Keyboard State Snapshots Design

## Goal

Create a versioned, lossless model for Iaido's portable keyboard profile and safe typing-session checkpoints. The same model will support phone migration, deterministic E2E setup, and recovery after an IME service recreation without attempting to serialize Android runtime handles.

## Scope

The design has three explicit layers:

1. `KeyboardProfileSnapshot`: durable user-owned state such as typing settings, language preference, command bindings, personal word overrides, custom words, and n-gram overrides.
2. `TypingSessionSnapshot`: safe, quiescent model state useful for test restoration and future session recovery, including language, correction history, cursor metadata, and pending candidate metadata.
3. Runtime bindings: `InputConnection`, IME views, lifecycle owners, handlers, executors, accessibility nodes, and pointer events. These are never serialized; they are rebuilt and acknowledged as ready after restore.

The first implementation will establish pure Kotlin snapshot/restore seams and a lossless JSON codec. Runtime and E2E integration will use an explicit revision/readiness handshake rather than accessibility-node presence or fixed sleeps. User-facing file export/import will use Android's Storage Access Framework and will not include current editor text by default.

## Non-goals

- Serializing an `InputMethodService`, `InputConnection`, `ComposeView`, `Handler`, executor, `MotionEvent`, or UiAutomator object.
- Restoring an in-flight swipe, split grace window, delayed correction callback, or active selection guard.
- Exporting the current application's editor contents as part of the normal user profile.
- Replacing the existing E2E host Activity with an Activity-stack or heartbeat heuristic.

## State model

```kotlin
data class KeyboardStateSnapshot(
    val schemaVersion: Int,
    val profile: KeyboardProfileSnapshot,
    val session: TypingSessionSnapshot?,
)
```

The profile contains exact learning data, including boost and use counts. Converting learned words to effective `WordEntry` frequencies is not a valid export because it loses n-gram data and usage history.

The session snapshot is only valid at a quiescent boundary. Restore must validate non-negative positions, unique correction-history IDs, ordered ranges, bounded settings, and supported schema versions before changing live state.

## Restore protocol

1. Decode and validate the complete snapshot without mutating live state.
2. Apply durable profile state through one profile-store operation, using a staged copy and rollback on failure.
3. On the IME main thread, cancel or finalize transient gesture work and replace the model state as one revisioned operation.
4. Rebind the current `InputConnection` and recreate derived UI state from the restored model.
5. Publish `RuntimeReady(stateRevision)` only after the input connection and rendered input view are usable.
6. E2E waits for that readiness revision and then verifies real editor text/selection. It never treats a stale accessibility node as proof of readiness.

## Settings authority

The snapshot work must make the stored settings authoritative before exporting them. In particular, flow-correction depth, split grace duration, and command bindings are currently stored by Settings but are not all consumed by the runtime. The profile store will expose one resolved settings object so UI, service, export, and tests cannot diverge.

## Privacy and migration

The JSON envelope includes a schema version, app/core-engine version, and checksum. Import rejects unsupported versions, malformed values, duplicate entries, and invalid enum names without partially applying data. The UI will use a user-selected document URI; learned words and n-grams will be treated as sensitive typing data. Encryption is required before presenting cloud/share export as a feature, while local test fixtures may use unencrypted debug-only JSON.

## Verification criteria

- Pure Kotlin round trips preserve exact dictionary boosts, usage counts, custom words, n-grams, correction-history IDs, ranges, alternatives, and corrected flags.
- Invalid snapshots fail before mutation.
- Runtime restore has one observable revision and one readiness signal.
- E2E setup can restore a baseline once per suite and still relaunch/rebind when Android actually recreates the host or IME.
- Ten consecutive full E2E runs report zero state-mismatch failures and record setup p50/p95, restore latency, host launches, IME handoff retries, and failures.
