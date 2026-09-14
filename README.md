# Iaido

An Android keyboard combining the best of Swype and Nintype gesture-typing, reimagined for modern Android.

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

## In-app APK updates

Settings can download the newest stable `app-release.apk` from the GitHub
Releases API. The APK must be signed with the same application key as the
installed build; Android then shows its normal install confirmation. The tag
release workflow expects these repository secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

For each release, update both `iaidoVersion` and the strictly increasing
`iaidoVersionCode` in `gradle.properties`, then push the matching `v<version>`
tag. The workflow publishes the resulting signed APK as `app-release.apk`.
