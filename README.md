# CleanSweep

Offline-first Android storage scanner, exact-duplicate finder, junk cleaner and deletion recommender for
Android 10 and newer. Everything runs on the device: the app cannot request the `INTERNET` permission, by
construction, and a test fails the build if it ever does.

**Status: Phase 2 of 4.** The foundation and data layer (Phase 1) and the scanning engine (Phase 2) are built:
the 3-stage duplicate finder, the incremental scanner, the junk detectors, the Original Lock policy and the
confidence scoring, with a scan service that reports progress and pauses, resumes and cancels. Nothing has been
run on an Android device yet, and the engine cannot be started from the app: the real screens are Phase 4, so the
app still opens a bootstrap screen that shows live aggregates from the database.

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
./gradlew testDebugUnitTest               # JVM tests, including whole scans over real files through Robolectric
./gradlew compileDebugAndroidTestKotlin   # compiles the on-device tests; nothing else does
./gradlew lintDebug                       # Android lint
./gradlew connectedDebugAndroidTest       # on-device tests; needs a device or emulator
```

Use `gradlew.bat` on Windows.

The first build downloads all dependencies, so it takes a few minutes. Later builds are incremental.

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
- Lint reports `OldTargetApi` (targetSdk is deliberately 36) and `AndroidGradlePluginVersion` twice (newer Gradle
  and AGP versions exist).

## Notes for JVM tests on a Windows host

- The first test run downloads Robolectric's Android runtime image (about 200 MB) into `~/.m2`. Robolectric's own
  downloader can stall on some networks. If it does, fetch the same artifact with any HTTP client into
  `~/.m2/repository/org/robolectric/android-all-instrumented/<version>/`; the version is the one named in
  Robolectric's `DefaultSdkProvider` for SDK 35.
- Creating symlinks needs Developer Mode or an elevated shell. Without it, one test in `StoragePathsTest` is
  reported as skipped rather than failed.
- DataStore replaces its file with `File.renameTo`, which cannot overwrite an existing file on some Windows setups.
  The settings I/O tests therefore write at most once each; the defaults, clamping and recovery are covered by the
  pure codec tests. On Android the rename replaces the file as usual.

## Where things are

| Path | Contents |
| --- | --- |
| `app/src/main/java/com/cleansweep/app/domain` | Models, repository interfaces, path helpers. Pure Kotlin. |
| `app/src/main/java/com/cleansweep/app/data` | Room entities, DAOs, database, mappers, repository implementations. |
| `app/src/main/java/com/cleansweep/app/di` | Hilt modules. |
| `app/src/main/java/com/cleansweep/app/ui` | Material 3 theme and the temporary bootstrap screen. |
| `app/src/main/res/xml/backup_rules.xml`, `data_extraction_rules.xml` | Exclude all app data from backup on Android 11 and lower, and from cloud backup and device transfer on Android 12 and newer. |
| `app/schemas` | Exported Room schemas. Commit them. |
| `app/src/main/java/com/cleansweep/app/domain/scanner` | The scanning engine: walker, hashing, duplicate pipeline, junk detectors, Original Lock policy, confidence scoring, scan service. Plain Kotlin. |
| `app/src/main/java/com/cleansweep/app/data/scanner` | The engine's Android adapters: storage volumes, storage access, MIME types, link counts, device state, installed packages, background worker. |
| `app/src/test` | Unit tests and whole scans over real files, through Robolectric. |
| `app/src/androidTest` | On-device tests: the schema on real SQLite, and one scan on real storage. |

## Phases

| Phase | Scope | State |
| --- | --- | --- |
| 1 | Project structure, Gradle, manifest, Room layer, repositories | done |
| 2 | 3-stage duplicate hashing engine, incremental scanner, junk detectors, Original Lock policy, confidence scoring, scan service | done, tested on the JVM only |
| 3 | Safe Trash manager, whitelist UI support | next |
| 4 | The eight Material 3 screens and their ViewModels | planned |
