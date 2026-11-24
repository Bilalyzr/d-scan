# CleanSweep architecture

CleanSweep is an offline-first Android app that scans device storage, finds exact duplicates and junk, and
explains why each file is safe to delete. Nothing ever leaves the device.

This document records the decisions behind the code and which layer enforces which rule. It is written for the
engineers picking up Phases 2 to 4. Source of truth for product behaviour is the PRD; this file only explains how
the code implements it and where it deliberately deviates.

## 1. Shape of the project

One Gradle module, `:app`, split by layer.

```
app/src/main/java/com/cleansweep/app/
├── CleanSweepApp.kt              @HiltAndroidApp, on-demand WorkManager through HiltWorkerFactory
├── MainActivity.kt               single-activity host
├── di/                           Hilt modules: Room, DataStore, Clock, repository bindings
├── domain/                       pure Kotlin, no Android or Compose imports
│   ├── model/                    IndexedFile, Suggestion, WhitelistRule, TrashEntry, enums, policy
│   ├── repository/               repository interfaces
│   └── util/StoragePaths.kt      canonical paths, descendant prefix
├── data/
│   ├── local/db/                 Room: entities, DAOs, query projections, CleanSweepDatabase
│   ├── mapper/                   entity <-> domain
│   └── repository/               repository implementations
└── ui/
    ├── theme/                    Material 3 theme
    └── bootstrap/                temporary Phase 1 screen, replaced in Phase 4
```

Dependency direction is one way: `ui` -> `domain` <- `data`. ViewModels see only the interfaces in
`domain.repository`, which keeps them testable with fakes and keeps Room out of the UI.

## 2. Toolchain

| Piece | Version | Notes |
| --- | --- | --- |
| Android Gradle plugin | 9.2.1 | Compiles Kotlin through **built-in Kotlin**. `org.jetbrains.kotlin.android` is never applied and `kapt` is incompatible. |
| Gradle | 9.4.1 | Pinned by the wrapper. |
| Kotlin | 2.4.21 | Raised above AGP's bundled minimum on the root `buildscript` classpath. Keep it equal to the Compose compiler plugin version. |
| KSP | 2.3.14 | Runs both Room's and Hilt's processors. |
| Room | 2.8.5 | Schemas exported to `app/schemas`. |
| Hilt | 2.60.1 | Plus `androidx.hilt` 1.4.0 for `hiltViewModel` and `@HiltWorker`. |
| Compose BOM | 2026.11.01 | Compose 1.13, Material 3 1.5. |
| JDK | 17 toolchain | Pinned in `app/build.gradle.kts`; Gradle itself may run on any JDK 17 or newer. |

`compileSdk` is 37.1 because Compose 1.13 requires it. `targetSdk` stays at 36: newer headers do not opt the app
into new runtime behaviour. AGP 9.2.1 prints a warning that it was tested up to 37.0. The warning is expected; the
clean fix is AGP 9.5 or newer, which needs Gradle 9.6 or newer.

AGP installs a missing SDK platform itself the first time a build runs, under the SDK license already accepted on
the machine.

## 3. Database

`CleanSweepDatabase` holds exactly the four tables from PRD section 6. Every PRD column is present with its PRD
name and type. The additions below exist because the PRD schema cannot on its own support the safety rules.

| Table | Addition | Why |
| --- | --- | --- |
| `file_index` | `category` | Dashboard breakdown and selective scans aggregate in SQL instead of re-deriving per row. |
| `file_index` | `created_at` (nullable) | Side-by-side metadata comparison (PRD 4.5). Best effort, see below. |
| `file_index` | `is_directory` | Marks empty-folder placeholder rows so content queries can exclude them. |
| `file_index` | `is_original_locked` | Records an explicit Original Lock chosen by the user. |
| `suggestion_items` | `reason_codes` | Every signal behind a score, so the score is explainable without recomputing it. |

Indexes: unique `file_index.path`; `size_bytes`, `full_hash`, `scan_timestamp` for the duplicate pipeline and the
stale sweep; unique `whitelist_rules.path_pattern`; `trash_manifest.expires_at` and `original_path` for purge and
restore lookups.

All timestamps are epoch millis. Enum columns (`category`, `rule_type`, reason codes) store the constant **name**,
so renaming or removing a constant is a schema change that needs a migration.

### Invariants

**1. Paths are canonical.** `/sdcard`, `/storage/self/primary` and `/storage/emulated/0` collapse to one key, so a
file reached through a symlinked alias is never indexed twice. Two parties are responsible for this. The whitelist
repository canonicalises every rule path and every lookup itself. For the file index the walker is responsible:
`FileIndexRepository.applyScanBatch` stores the path it is given, so the Phase 2 walker has to pass every entry
through `StoragePaths.canonicalize` first. That obligation is not enforced by the repository yet.

**2. A cached hash is valid only while size and last-modified time are unchanged.** This is the whole incremental
indexing rule, implemented once in `FileIndexRepositoryImpl.applyScanBatch`:

| Cached row | Result |
| --- | --- |
| none | new row, no hashes |
| same size and `last_modified` | row id, both hashes and the Original Lock are carried over; the file is never hashed again |
| size or `last_modified` changed | row id and Original Lock are kept, both hashes are dropped, the file re-enters Stage 1 |

**3. Empty folders are placeholder rows in `file_index`.** A suggestion must reference a `file_index` row through
`suggestion_items.file_id`, so an empty folder needs a row to be suggestable. Placeholders have `is_directory = 1`,
size 0, no hashes and category `OTHER`. Every query about file content filters `is_directory = 0`; the DAO tests
pin this. Only a full scan refreshes placeholders, through `replaceEmptyFolderPlaceholders`, which keeps a
placeholder's row id while the folder stays empty so that a dismissed suggestion stays dismissed.

**4. The Original Lock is explicit and additive.** `is_original_locked = 1` means the user pinned this copy. When no
copy in a duplicate group is pinned, the Original Lock policy that Phase 2 adds will derive the Original at read
time. Pinning never unpins another copy, so a group can hold several locked copies and can never end up with none.

**5. Suggestions are derived data; dismissals are user data.** The scoring engine rebuilds the whole table after
each scan with `SuggestionRepository.replaceAll`, inside one transaction. The rebuild reads the dismissed file ids
first and writes them back as dismissed. `suggestion_items.file_id` is both the primary key and a cascading foreign
key, which guarantees one suggestion per file and removes suggestions with their file.

**6. The whitelist is exact and takes effect immediately.** `path_pattern` is a canonical path, not a glob. A
`FILE` rule matches one path; a `FOLDER` rule also matches everything below it. Adding a rule removes anything
already indexed under the path, and its suggestions, in the same transaction as the insert.

**7. The trash manifest is written before a file is moved.** This is the rule the Safe Trash manager has to follow
in Phase 3. A crash can then leave a manifest row whose file is missing, which is detectable and cleanable; it can
never leave an untracked file in the trash. Only the repository half exists so far.

**8. SQL stays within what Android 10's SQLite 3.22 can run.** That rules out `ON CONFLICT DO UPDATE` (use Room's
`@Upsert`), `RETURNING` and window functions. Statements also bind at most
`MAX_BIND_ARGS_PER_QUERY` (900) parameters, because Android 10 caps a statement at 999; collections going into
`IN (...)` are chunked in the repositories.

Prefix matching (stale sweep, folder whitelist) compares `substr(path, 1, length(prefix)) = prefix` instead of
`LIKE`. `LIKE` treats `%` and `_` as wildcards, so a folder called `My_Folder` would also match `MyXFolder`.

### Known limitations

- **Creation time is not reliable.** Android shared storage does not expose a real creation timestamp;
  `created_at` may be null or hold the inode change time. `last_modified` is the trustworthy age signal, and the
  Original Lock policy in Phase 2 should treat "oldest" as oldest `last_modified`.
- **Same size and timestamp, different content.** A file modified twice within the filesystem's timestamp
  granularity keeps a stale cached hash. This is the standard trade-off of size-and-time caching. Safe Trash
  bounds the damage; Phase 3 can re-hash selected files before trashing them.
- **Absent volumes keep their index.** The stale sweep is scoped to the volumes a scan actually visited, so an
  ejected SD card is not purged and does not need a full re-hash when it returns.
- **Cross-platform transfer has no rules yet.** Android 16 QPR2 adds a `<cross-platform-transfer>` section to the
  data extraction rules. It needs the iOS app's bundle id and team id, which do not exist until the iOS companion
  (PRD Phase 2). Add it then, with the same exclusions.

## 4. Permission and privacy posture

| Concern | Decision |
| --- | --- |
| Android 10 | Legacy storage model (`requestLegacyExternalStorage`) plus `READ`/`WRITE_EXTERNAL_STORAGE`, both capped at `maxSdkVersion` 29. |
| Android 11 and newer | `MANAGE_EXTERNAL_STORAGE` (All Files Access). The prominent disclosure and the settings deep link arrive in Phase 4. |
| Network | `INTERNET` is declared with `tools:node="remove"`, so the manifest merger strips it even if a dependency declares it. |
| Backup | Off, by two mechanisms. `allowBackup="false"` switches off every form of backup on Android 11 and lower, and `res/xml/backup_rules.xml` states the same exclusions for that range. From Android 12 on, cloud backup and device-to-device transfer follow `res/xml/data_extraction_rules.xml` instead, and a mode without rules is fully enabled, so that file excludes every storage domain from both. Auto Backup would otherwise copy the index of the user's filenames into their cloud backup or onto their next phone. |
| Read-only SD cards | Not handled yet; needs a Storage Access Framework tree grant before deleting. Tracked for Phase 3. |

The merged manifest requests exactly these permissions, and `ManifestPrivacyTest` pins the list together with the
backup attributes:

| Permission | Origin | Reason |
| --- | --- | --- |
| `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE` (up to API 29) | app | Android 10 storage access. |
| `MANAGE_EXTERNAL_STORAGE` | app | All Files Access on Android 11 and newer. |
| `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE` | WorkManager | Needed to run and reschedule background indexing. |
| `ACCESS_NETWORK_STATE` | WorkManager | Unused by CleanSweep (no work has a network constraint) and it does not allow any network access. It can be stripped with `tools:node="remove"` if the permission list should be minimal. |
| `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | androidx.core | App-internal; protects receivers that are not exported. |

No analytics, crash-reporting or cloud SDK is included. `ACCESS_NETWORK_STATE` is the one entry worth a conscious
decision before release; it was left in because removing it could not be verified on a device in Phase 1.

## 5. Where each safety rule lives

| Rule | Enforced by | Status |
| --- | --- | --- |
| 3-stage duplicate pipeline, 64 KB streaming buffers | scanning engine | Phase 2 |
| Incremental indexing | `FileIndexRepositoryImpl.applyScanBatch` | done, tested |
| Canonical paths | `StoragePaths` helper; whitelist repository canonicalises itself | helper and whitelist done and tested; the walker that canonicalises indexed paths is Phase 2 |
| Mandatory Original Lock | `is_original_locked` column and DAO | column done; policy in Phase 2, enforcement at deletion in Phase 3 |
| 30-day Safe Trash by default | manifest, `TrashPolicy` | manifest done; manager in Phase 3 |
| Whitelist excludes from scans and suggestions | `WhitelistRepositoryImpl`, matcher | done, tested |
| 2 to 4 hashing workers, instant pause/resume/cancel | scanning engine | Phase 2 |
| Prominent disclosure before All Files Access | onboarding | Phase 4 |

Three obligations are deliberately left for later phases and must not be forgotten: the Phase 2 walker
canonicalises every path before indexing it and never suggests a locked copy, and the deletion path in Phase 3
re-checks the whitelist and the Original Lock at execution time instead of trusting stored suggestions.

## 6. Phase map

| Phase | Lands in |
| --- | --- |
| 1 (this) | Project, Gradle, manifest, Room layer, repositories, Hilt, bootstrap screen, tests |
| 2 | `domain/scanner/` (walker, 3-stage hashing engine, junk detectors, confidence scoring, Original Lock policy) and the WorkManager worker |
| 3 | Safe Trash manager (move, restore, 30-day purge) |
| 4 | The eight Compose screens and their ViewModels; `ui/bootstrap/` is deleted |

## 7. Testing

- **Pure JVM tests** (`app/src/test`): domain rules, path helpers, matcher, trash policy, settings codec.
- **Robolectric tests** (`app/src/test`): the DAO queries and the Room-backed repositories against an in-memory
  database, including batches above the SQLite parameter limit, and the manifest privacy guard on the packaged
  manifest.
- **Instrumented test** (`app/src/androidTest`): one smoke test on real device SQLite. Needs a device or emulator:
  `./gradlew connectedDebugAndroidTest`.

Run everything that does not need a device with `./gradlew testDebugUnitTest`.

Two caveats for JVM tests on a Windows host, both caused by the host and not by the Android code:

- Robolectric fetches its Android runtime image (about 200 MB) on first use with its own Java downloader, which
  can stall on some networks. Fetching the same artifact with any HTTP client into
  `~/.m2/repository/org/robolectric/android-all-instrumented/<version>/` avoids the stall.
- DataStore replaces its file with `File.renameTo`, which cannot overwrite an existing file on some Windows setups.
  The settings I/O tests therefore write at most once each; defaults, clamping and recovery are covered by the pure
  codec tests. On Android the rename replaces the file as usual.

A schema change requires bumping the `version` literal in `@Database`, a `Migration`, and a migration test.
`DatabaseModule` intentionally does not configure a destructive fallback: rebuilding the database would orphan
every file in Safe Trash and drop the whitelist.
