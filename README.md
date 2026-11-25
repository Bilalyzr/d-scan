# CleanSweep

Offline-first Android storage scanner, exact-duplicate finder, junk cleaner and deletion recommender for
Android 10 and newer. Everything runs on the device: the app cannot request the `INTERNET` permission, by
construction, and a test fails the build if it ever does.

**Status: all four phases built.** The data layer, the scanning engine, Safe Trash with the whitelist, and the
eight screens are in place: onboarding with the storage disclosure, the dashboard, the scan progress, the
Suggestions Hub, the screen of one group of duplicates, the file preview, Safe Trash, and Settings with the
exclusions. Nothing has been run on an Android device yet. The screens are drawn by Robolectric in the unit
tests and their buttons are clicked there; what only a device has has not been seen (see below).

The decisions behind the code are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Requirements

- **JDK 17 or newer** to launch Gradle. The build itself compiles with a pinned JDK 17 toolchain and downloads one
  if the machine does not have it.
- **Android SDK** with an accepted SDK license. Point `local.properties` (`sdk.dir`) or `ANDROID_HOME` at it.
  Missing platforms (37.1) and build tools are installed by the first build.
- To strip native libraries for release builds, install the NDK. Without it they are packaged unstripped.

## Build and test

```bash
./gradlew assembleDebug                   # debug APK
./gradlew assembleRelease                 # release APK, minified, unsigned
./gradlew bundleRelease                   # release app bundle, minified, unsigned
./gradlew testDebugUnitTest               # JVM tests: the engine, the screens' logic, and the screens drawn by Robolectric
./gradlew compileDebugAndroidTestKotlin   # compiles the on-device tests; nothing else does
./gradlew lintDebug                       # Android lint
./gradlew connectedDebugAndroidTest       # on-device tests; needs a device or emulator
```

Use `gradlew.bat` on Windows.

The first build downloads all dependencies, so it takes a few minutes. Later builds are incremental.

The unit tests write a picture of every screen they draw to `app/build/screenshots/`. Open that folder after a
run to see what the app looks like.

## What has not been seen running

No device or emulator was available while the app was built. These need one before a release:

- The system's permission pages that onboarding opens, and the permission dialog of Android 10.
- The picture preview, the video and audio player, and the PDF pages. The unit tests draw stand-ins for
  the first two and see only the failure of the last.
- Trashing and restoring on real shared storage, and on an SD card. `connectedDebugAndroidTest` covers it.
- How fast a scan is on a real phone, and how hot it gets.

## Expected build output

These messages are expected and do not affect the result:

- `We recommend using a newer Android Gradle plugin to use compile SDK version 37.1`. Compose 1.13 requires
  `compileSdk` 37.1 and AGP 9.2.1 was tested up to 37.0. Moving to AGP 9.5 or newer (which needs Gradle 9.6 or
  newer) removes the warning.
- Manifest merger notes of the form `... was tagged ... to remove other declarations but no other declaration
  present`, for `android.permission.INTERNET` and for `androidx.work.WorkManagerInitializer`. The manifest strips
  both on purpose: INTERNET as a guard, and WorkManager's default initializer because the app supplies its own.
  `ManifestPrivacyTest` checks the packaged manifest.
- `Unable to strip the following libraries` when the NDK is not installed.
- Lint reports four version notices and nothing else: `OldTargetApi` (targetSdk is deliberately 36),
  `AndroidGradlePluginVersion` twice (newer Gradle and AGP versions exist) and `GradleDependency` once (a newer
  Navigation exists).

## Notes for JVM tests on a Windows host

- The first test run downloads Robolectric's Android runtime image (about 200 MB) into `~/.m2`. Robolectric's own
  downloader can stall on some networks. If it does, fetch the same artifact with any HTTP client into
  `~/.m2/repository/org/robolectric/android-all-instrumented/<version>/`; the version is the one named in
  Robolectric's `DefaultSdkProvider` for SDK 35.
- Creating symlinks needs Developer Mode or an elevated shell. Without it, two tests in `StoragePathsTest` are
  reported as skipped rather than failed.
- DataStore replaces its file with `File.renameTo`, which cannot overwrite an existing file on some Windows setups.
  The settings I/O tests therefore write at most once each; the defaults, clamping and recovery are covered by the
  pure codec tests. On Android the rename replaces the file as usual.
- The whole suite takes three to five minutes; a single class that draws screens about a minute and a half.

## Where things are

| Path | Contents |
| --- | --- |
| `app/src/main/java/com/cleansweep/app/domain` | Models, repository interfaces, path helpers. Pure Kotlin. |
| `app/src/main/java/com/cleansweep/app/domain/scanner` | The scanning engine: walker, hashing, duplicate pipeline, junk detectors, Original Lock policy, confidence scoring, scan service; Safe Trash (`trash`), the marking for deletion (`clean`), the whitelist manager. Plain Kotlin. |
| `app/src/main/java/com/cleansweep/app/data` | Room entities, DAOs, database, mappers, repository implementations. |
| `app/src/main/java/com/cleansweep/app/data/scanner` | The engine's Android adapters: storage volumes, storage access, MIME types, link counts, device state, installed packages, the background workers and their schedules; the storage operations of Safe Trash (`trash`). |
| `app/src/main/java/com/cleansweep/app/di` | Hilt modules. |
| `app/src/main/java/com/cleansweep/app/ui` | The eight screens, one package each (`onboarding`, `home`, `scan`, `suggestions`, `group`, `preview`, `trash`, `settings`), each with its ViewModel; `navigation` for the routes and the tab bar; `clean` for what the hub and a group share; `common` and `theme`. |
| `app/src/main/res/xml/backup_rules.xml`, `data_extraction_rules.xml` | Exclude all app data from backup on Android 11 and lower, and from cloud backup and device transfer on Android 12 and newer. |
| `app/schemas` | Exported Room schemas. Commit them. |
| `app/src/test` | Unit tests: the engine over real files through Robolectric, every screen's logic against fakes, and every screen drawn and clicked. |
| `app/src/androidTest` | On-device tests: the schema on real SQLite, one scan on real storage, and Safe Trash on real shared storage. |

## Phases

| Phase | Scope | State |
| --- | --- | --- |
| 1 | Project structure, Gradle, manifest, Room layer, repositories | done |
| 2 | 3-stage duplicate hashing engine, incremental scanner, junk detectors, Original Lock policy, confidence scoring, scan service | done, tested on the JVM only |
| 3 | Safe Trash manager (move to trash, restore, 30-day purge, empty bin) and whitelist manager | done, tested on the JVM only |
| 4 | The eight Material 3 screens and their ViewModels | done, tested on the JVM only: logic against fakes, and each screen drawn and clicked under Robolectric |

## Not built

These are in the PRD but not in the agreed scope of the four phases:

- Near-duplicate documents, similar and blurry photos, old screenshots and messaging media as detectors of
  their own.
- Smart alerts: notifications when free storage runs low, and weekly summaries.
- The cleanup history.
- A file browser and search. The Play Console's declaration for All Files Access asks for file management
  features besides cleaning, so this matters before a release.
