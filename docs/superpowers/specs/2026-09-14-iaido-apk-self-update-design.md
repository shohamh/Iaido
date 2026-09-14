# Iaido APK self-update design

## Goal

Add an explicit Update app action to Settings that discovers the newest stable
Iaido APK from GitHub Releases, downloads it safely, and hands it to the
Android package installer for the user's confirmation.

## Constraints

- GitHub Releases is the only update source: the app must not accept arbitrary
  URLs from release metadata or user input.
- APK updates require the same package name and signing certificate as the
  installed app. Android still requires user approval; silent installation is
  not a target.
- The release APK must be signed. The current unsigned release artifact is not
  a valid update for an installed signed/debug build, so release automation must
  publish a signed APK using the established application signing key.
- Downloads must be bounded, streamed through a temporary file, and moved into
  place only after validation.

## Flow

1. The Settings screen starts a background check when the user presses Update
   app and reports checking, downloading, current, ready-to-install, or an
   actionable failure state.
2. A fixed GitHub Releases API endpoint returns the latest stable release. The
   updater selects the APK asset, rejects missing/ambiguous assets, and compares
   the archive's `versionCode` with the installed version.
3. The updater validates the downloaded archive before exposing it to Android:
   HTTPS and host allowlist, size limit, package name, strictly newer version,
   and signing-certificate match. Temporary and final files are private app
   files and are replaced atomically.
4. Settings launches `ACTION_VIEW` with a `FileProvider` content URI and read
   permission. If Android has not allowed this app to request package installs,
   Settings opens the system permission screen and explains the next action.

## Components

- A pure Kotlin release/asset selector and version policy, covered by JVM tests.
- An Android APK updater responsible for HTTP, archive/package inspection,
  certificate validation, file staging, and installer intents.
- A Settings UI state holder that owns the coroutine and maps updater results to
  accessible button/status text.
- A manifest `REQUEST_INSTALL_PACKAGES` permission and narrowly scoped
  `FileProvider` paths for staged APKs.
- Release workflow changes that build and publish a signed, consistently named
  APK asset. Signing credentials remain in GitHub Actions secrets and are never
  committed.

## Verification

- Unit tests cover stable-release selection, asset rejection, version checks,
  certificate/package validation, size and network failures, and state mapping.
- Instrumented tests cover Settings rendering and the permission/install intent
  boundary without attempting to bypass Android's confirmation UI.
- Debug and release builds must both pass; release manifest inspection must
  confirm the provider/permission are present and the APK is installable.
