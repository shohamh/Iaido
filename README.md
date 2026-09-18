# Iaido

An Android keyboard combining the best of Swype and Nintype gesture-typing, reimagined for modern Android.

## Why “Iaido”?

Iaido (居合道) is a Japanese martial art focused on drawing a sword and attacking an opponent in one swift, controlled motion. We chose the name because we want the keyboard to offer that same swiftness: one deliberate swipe following a clean path to produce a complete word.

### Pronunciation

**Iaido** is pronounced **“ee-eye-doh”** (*i-a-i-dō*).

## Status

Stages 1-12 are implemented on `main`. Stage 12 provides signed GitHub
Release packaging, verified core-engine updates, atomic current/previous
rollback storage, and a compiled fallback when no update is available.

## Development commands

```powershell
.\gradlew.bat :core-engine:test :app:testDebugUnitTest :app:assembleDebug --no-daemon
powershell -ExecutionPolicy Bypass -File tools\package_core_engine.ps1
powershell -ExecutionPolicy Bypass -File tools\deploy_app.ps1 -DeviceSerial <adb-serial> -Build
```

The package command writes ignored `dist/` outputs: the core-engine JAR, its
SHA-256 checksum, and a versioned JSON manifest. Wireless ADB pairing is done
once through Android Studio or `adb pair`; subsequent installs use the device
serial passed to `deploy_app.ps1`.

## Keyboard profile migration

Open Iaido Settings and use **Export profile** to save a versioned,
checksum-protected JSON file through Android's document picker. The file contains
typing settings, command bindings, and learned words/ngrams; it does not contain
the text in the currently focused editor. Use **Import profile** on the new phone
to validate and replace the stored profile. Reopen the keyboard afterward so the
IME service reloads imported learning data.

The same validated snapshot model is used by debug E2E setup. It restores model
state through a monotonic runtime revision and waits until both the input
connection and rendered keyboard acknowledge that revision; Android runtime
handles and in-flight gestures are intentionally never serialized.

## In-app APK updates

Settings can download the newest stable `app-release.apk` from the GitHub
Releases API. The APK must be signed with the same application key as the
installed build; Android then shows its normal install confirmation. The tag
release workflow expects these repository secrets:

If a debug build is installed on a device, uninstall it before installing the
first production release. Android does not allow an update across signing keys;
uninstalling removes app-local settings and learned words.

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

For each release, update both `iaidoVersion` and the strictly increasing
`iaidoVersionCode` in `gradle.properties`, then push the matching `v<version>`
tag. The workflow publishes the resulting signed APK as `app-release.apk`.
