# CleanSweep architecture

CleanSweep is an offline-first Android app that scans device storage, finds exact duplicates and junk, and
explains why each file is safe to delete. Nothing ever leaves the device.

This document records the decisions behind the code and which layer enforces which rule. It is written for the
engineers picking up Phases 3 and 4. Source of truth for product behaviour is the PRD; this file only explains how
the code implements it and where it deliberately deviates.

**State:** Phase 1 (foundation and data layer) and Phase 2 (scanning engine) are built and tested on the JVM.
Nothing has been run on an Android device yet: no device or emulator was available. Phase 3 (Safe Trash) and
Phase 4 (the eight screens) are not started, so the engine cannot be started from the app's UI.

## 1. Shape of the project

One Gradle module, `:app`, split by layer.

```
app/src/main/java/com/cleansweep/app/
├── CleanSweepApp.kt              @HiltAndroidApp, on-demand WorkManager through HiltWorkerFactory
├── MainActivity.kt               single-activity host
├── di/                           Hilt modules: Room, DataStore, Clock, coroutines, scanner, repository bindings
├── domain/                       plain Kotlin: no Android, no Hilt
│   ├── model/                    IndexedFile, Suggestion, WhitelistRule, TrashEntry, enums, policy
│   ├── repository/               repository interfaces
│   ├── util/StoragePaths.kt      canonical paths, descendant prefix
│   └── scanner/                  the scanning engine (section 5)
│       ├── ScanController.kt     starts, pauses, resumes, cancels; exposes the state
│       ├── ScanRunner.kt         one scan from end to end
│       ├── ScanTracker.kt        running progress snapshots
│       ├── SuggestionGuard.kt    last check before anything is suggested
│       ├── PauseGate.kt, HardLinkReader.kt, ScanModels.kt
│       ├── walker/               FileWalker, FileNames, ScanExclusions, EmptyFolderRules, metadata
│       ├── hashing/              StreamingFileHasher, HashSchemes, HashingConcurrency
│       ├── duplicates/           DuplicatePipeline, OriginalLockPolicy, LocationHeuristics
│       ├── junk/                 JunkRules and the detectors
│       └── scoring/              ConfidenceScorer, SuggestionEngine
└── data/
    ├── local/db/                 Room: entities, DAOs, query projections, CleanSweepDatabase
    ├── mapper/                   entity <-> domain
    ├── repository/               repository implementations
    └── scanner/                  Android adapters: storage roots, storage access, MIME types, link counts,
                                  device state, installed packages, the background worker
```

Dependency direction is one way: `ui` -> `domain` <- `data`. ViewModels see only the interfaces in
`domain.repository`, which keeps them testable with fakes and keeps Room out of the UI. The scanning engine
knows nothing of Android: every platform fact reaches it through a small interface, bound in `ScannerModule`.

## 2. Toolchain

| Piece | Version | Notes |
| --- | --- | --- |
| Android Gradle plugin | 9.2.1 | Compiles Kotlin through **built-in Kotlin**. `org.jetbrains.kotlin.android` is never applied and `kapt` is incompatible. |
| Gradle | 9.4.1 | Pinned by the wrapper. |
| Kotlin | 2.4.21 | Raised above AGP's bundled minimum on the root `buildscript` classpath. Keep it equal to the Compose compiler plugin version. |
| KSP | 2.3.14 | Runs both Room's and Hilt's processors. |
| Room | 2.8.5 | Schemas exported to `app/schemas`. `RoomDatabase` is not `Closeable`: call `close()` in `finally`. |
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
so renaming or removing a constant is a schema change that needs a migration. Adding a constant is safe.

### Invariants

**1. Paths are canonical.** `/sdcard`, `/storage/self/primary` and `/storage/emulated/0` collapse to one key, so a
file reached through a symlinked alias is never indexed twice. The walker produces canonical paths: each folder is
canonicalised as it is entered and its files are addressed as `folder + "/" + name`, which is one lookup per folder
instead of one per file. The whitelist repository canonicalises every rule path and every lookup itself.

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

**4. The Original Lock is explicit and never absent.** `is_original_locked = 1` means the user pinned this copy.
`OriginalLockPolicy.selectOriginal` always returns exactly one copy of a group, in this order of precedence: a
pinned copy; a copy outside Downloads and messaging "Sent" folders; the oldest by modification time; the shortest
path; the alphabetically first path. Pinning a copy makes it the Original. The copy that was the Original by
default until then becomes a redundant copy like any other, so it is offered for deletion. Pinning never unpins
another pinned copy, so a group can hold several pinned copies and can never end up without one.

**5. Suggestions are derived data; dismissals are user data.** Every scan rebuilds the whole table with
`SuggestionRepository.replaceAll`, inside one transaction. The rebuild reads the dismissed file ids first and
writes them back as dismissed. `suggestion_items.file_id` is both the primary key and a cascading foreign key,
which guarantees one suggestion per file and removes suggestions with its file.

**6. The whitelist is exact and takes effect immediately.** `path_pattern` is a canonical path, not a glob. A
`FILE` rule matches one path; a `FOLDER` rule also matches everything below it. The walker never enters a
matching folder. Adding a rule also removes anything already indexed under the path, and its suggestions, in the
same transaction as the insert.

**7. The trash manifest is written before a file is moved.** This is the rule the Safe Trash manager has to follow
in Phase 3. A crash can then leave a manifest row whose file is missing, which is detectable and cleanable; it can
never leave an untracked file in the trash. Only the repository half exists so far.

**8. SQL stays within what Android 10's SQLite 3.22 can run.** That rules out `ON CONFLICT DO UPDATE` (use Room's
`@Upsert`), `RETURNING` and window functions. Statements also bind at most `MAX_BIND_ARGS_PER_QUERY` (900)
parameters, because Android 10 caps a statement at 999; collections going into `IN (...)` are chunked in the
repositories.

Prefix matching (stale sweep, folder whitelist) compares `substr(path, 1, length(prefix)) = prefix` instead of
`LIKE`. `LIKE` treats `%` and `_` as wildcards, so a folder called `My_Folder` would also match `MyXFolder`.

Room's `@Upsert` returns the new row id for an inserted row and **-1 for an updated one**. `applyScanBatch` and
`replaceEmptyFolderPlaceholders` therefore take the id of an existing row from the entity, not from Room's result.

### Known limitations

- **Creation time is not reliable.** Android shared storage does not expose a real creation timestamp;
  `created_at` holds the inode change time, which a rename updates. `last_modified` is the age signal everything
  relies on, and "oldest" in the Original Lock policy means oldest `last_modified`.
- **Same size and timestamp, different content.** A file modified twice within the filesystem's timestamp
  granularity keeps a stale cached hash. This is the standard trade-off of size-and-time caching. Safe Trash
  bounds the damage; Phase 3 can re-hash selected files before trashing them.
- **Absent volumes keep their index.** The stale sweep is scoped to the volumes a scan actually visited, so an
  ejected SD card is not purged and does not need a full re-hash when it returns. Suggestions are likewise limited
  to files below the volumes the scan could read.
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
| Read-only SD cards | Partly handled, by design and unverified on a device. A volume mounted read-only is scanned, so its duplicates are found. Deleting from it needs a Storage Access Framework tree grant, which is Phase 3. |

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
decision before release; it was left in because removing it could not be verified on a device.

## 5. Scanning engine

### 5.1 One scan, start to finish

`ScanRunner.run` is the whole scan, whoever starts it:

1. **Walk and index.** `FileWalker` lists every readable folder below the requested volumes and hands each file to
   `applyScanBatch`, 500 at a time, stamped with the time the scan started.
2. **Stale sweep.** Files below the volumes the walk could read that were not seen again are removed, together
   with their suggestions. A scan limited to some categories only sweeps those categories.
3. **Hash.** `DuplicatePipeline` brings the partial and full hashes up to date (5.2).
4. **Analyse.** Duplicate groups and the junk candidates in the index are turned into suggestions (5.4, 5.5),
   which replace the stored ones in one transaction.

A scan ends early only when its coroutine is cancelled. Anything it cannot deal with is counted in
`ScanProgress.errors` and the scan goes on: a folder that cannot be listed, a volume that is not mounted, a file
that cannot be read or that is being written to.

### 5.2 The 3-stage exact duplicate pipeline

| Stage | Question | What is read |
| --- | --- | --- |
| 1 | Does another file have this byte size? | Nothing: a query on the index |
| 2 | Do they also share their first and last 4 KB? | Only the edges of the files that share a size |
| 3 | Are they the same all the way through? | The whole file, only where the edges match |

A file is therefore read from beginning to end only when two cheap checks already say it is a likely copy. A file
whose stored hash is current is skipped by Stage 2 and Stage 3 alike, which is what makes a second scan cheap: on
unchanged storage the hasher is not called at all.

- **Hashes.** Both stages use SHA-256. The PRD allows either a fast hash or SHA-256 for Stage 2, and 8 KB per
  file is cheap enough that a second algorithm and its dependency would buy nothing. A stored hash is
  `<scheme>:<hex>`, with `sha256` for the full hash and `sha256-head4k-tail4k` for the partial one. The scheme is
  part of the value, so a value from a build that used another scheme is recomputed instead of compared.
- **Memory.** `StreamingFileHasher` never holds more than three 64 KB buffers per file in flight. The buffers are
  pooled and reused, so hashing thousands of files allocates nothing per chunk, and a 4 GB video costs the same
  memory as a 4 KB note.
- **Threads.** Reads run on `Dispatchers.IO` and the digest on `Dispatchers.Default`. For the full hash the two are
  a pipeline: the reader fills one of the buffers and hands it to the digester through a channel, which folds it
  in and hands it back. Reading waits on storage and digesting waits on CPU, and the two overlap.
- **Workers.** Between 2 and 4 files are hashed at once (`HashingConcurrency`): half the processor count, lowered
  when the battery is at or below 15%, when battery saver is on, and as the device gets hotter. The count is
  chosen once, when the scan reaches the hashing stage.
- **A hash is only stored if it describes the file.** The hasher notes size and modification time before and after
  reading. A file that changed in between is reported as changed and gets no hash; a file that is gone is
  reported as vanished and its row is removed from the index.
- **Copies under 1 KB are not looked for** (`DuplicatePipeline.MIN_DUPLICATE_SIZE_BYTES`). They reclaim nothing worth
  showing, and identical marker and configuration files are common inside app folders, where removing one can
  break an app.

### 5.3 The walker

- **No recursion.** Folders are walked with an explicit stack, so a deep tree cannot overflow the stack.
- **Never walked.** Anything matching the user's whitelist, and `Android/data` and `Android/obb`, which belong to
  other apps. `Android/media` is walked on purpose: WhatsApp and Telegram keep their media there.
- **Symbolic links are never followed.** A link is reported by the metadata reader as a link and skipped. A folder
  reached through a link or mount point is caught by comparing its canonical path with where its name says it is.
- **What counts as content.** Every file makes its folder non-empty, whether indexed or not. A pruned entry (a
  whitelisted subfolder, another app's folder) counts as content too: whether its parent is empty is not for the
  scan to say.
- **Empty folders.** A folder with no file anywhere below it is empty. Only a full scan works them out, because a
  folder full of files skipped by a category filter looks empty without being so. Only the top folder of an empty
  tree is kept, so the same "nothing" is not offered three times over.
- **Storage roots.** The primary shared storage, then SD cards and USB drives, as reported by `StorageManager`. A
  volume that is not mounted is left out; one mounted read-only is included.

### 5.4 Junk detectors and the Original Lock

| Detector | Reports | Signal |
| --- | --- | --- |
| `TempFileDetector` | `.tmp`, `.log`, `.bak` files and anything inside a `.thumbnails` folder | `TEMP_OR_LOG` or `THUMBNAIL_CACHE` |
| `ZeroByteDetector` | Files of exactly 0 bytes | `ZERO_BYTE` |
| `LargeForgottenFileDetector` | Files at or above the large-file threshold, untouched for the stale window (settings: 50 MB and 90 days) | `LARGE_FILE` |
| `ObsoleteApkDetector` | Installers whose app is installed at that version or newer, and installers older than 7 days | `INSTALLED_APK` or `OLD_INSTALLER` |
| The scan itself | Every copy of a duplicate group but the Original | `EXACT_COPY` |
| The scan itself | Empty folders as placeholder rows | `EMPTY_FOLDER` |

Only plain APK files are read for the app inside them; an XAPK goes by age alone. An installer of an app that is
not installed carries the weaker `OLD_INSTALLER` signal, because it may be the only copy the user has.

### 5.5 Confidence scoring

A flagged item starts at a base of 35 and every signal adds its weight:

| Signal | Weight |
| --- | --- |
| Exact byte-for-byte copy | +50 |
| Temp, log, backup, thumbnail cache, 0-byte, installed APK, empty folder | +45 |
| In Downloads, or in a messaging "Sent" folder | +20 |
| Untouched for 90 days, old installer, large file, blurry photo | +15 |
| Modified within the last 7 days | -20 |

The PRD gives the weights and two thresholds without saying what an item with no signal scores. The base is derived
from those numbers: the weakest signal the PRD lists as "safe to clean" (+45) lands exactly on the Safe threshold
(80), and the weakest signal (+15) lands exactly on the Review threshold (50). With a base of zero the PRD's own
safe categories (45) would fall below 50 and never be suggested at all.

Weights alone cannot be trusted to place an item in the right tier. A large video in Downloads that has sat
untouched for 90 days scores 85. On the score alone it would land in Safe to Clean and be pre-selected for
deletion, which the PRD rules out for large files. So the tier follows from what the item *is*, and the score is
kept inside the tier's range:

- **Safe to Clean** (80 to 100): the signals include one of the certain ones (`EXACT_COPY`, `TEMP_OR_LOG`,
  `THUMBNAIL_CACHE`, `ZERO_BYTE`, `INSTALLED_APK`, `EMPTY_FOLDER`), and the item was not modified in the last 7 days.
- **Review Recommended** (50 to 79): everything else that is suggested.

The 7-day rule is not in the PRD. A temp file written a minute ago may be a download in progress, and a folder an
app has just created is usually about to receive a file, so recent temp files, 0-byte files and empty folders are
held back from pre-selection. An exact copy and an installed app's installer are certain whatever their age.

One file can be reported by several detectors. `SuggestionEngine` merges everything about one file into a single
suggestion: the strongest defining signal decides the category, and every signal stays on as explanation.

### 5.6 What is never suggested

`SuggestionGuard` is the last check before a finding becomes a suggestion. An entry is rejected when:

- the user pinned it as an Original;
- it is hidden, meaning it or a folder above it starts with a dot. Hidden entries belong to apps and to the system
  (`.nomedia`, `.trashed-*`, a pending download), not to the user. The one exception is temporary junk: a hidden
  `.log` or a thumbnail cache is litter whoever put it there;
- it is an empty folder with a name the system or its apps use (`DCIM`, `Download`, `Screenshots`, ...), or fewer
  than two levels below the storage root;
- other directory entries refer to the same stored content (a hard link), because deleting one frees no space;
- it lies below a whitelisted path, or below a volume this scan could not read.

### 5.7 The scan service

`ScanController` is the single entry point for the UI and for anything else that starts scans.

- One scan at a time. `start` returns at once and returns false when a scan is running or when the app may not
  read storage yet. In the second case the state is `Failed(STORAGE_PERMISSION_MISSING)`, which is the caller's
  cue to show the prominent disclosure.
- The state is a `StateFlow<ScanState>`: `Idle`, `Running(progress, isPaused)`, `Finished(summary)`, `Cancelled` or
  `Failed`. A scan runs in the application scope, so it outlives any screen.
- **Pause and resume** close and open a `PauseGate` that the walk and every hashing worker pass through between
  entries. Nothing is undone; the scan continues with the next entry.
- **Cancel** is coroutine cancellation. The walker checks between entries and the hasher between buffers. What was
  indexed and hashed up to then is kept for the next scan.
- **Progress** is thinned out to one snapshot per 150 ms plus one per phase change, because files go by far faster
  than any screen can draw. Every snapshot is complete: a later stage never resets the counters of an earlier one.
- **Unexpected exceptions** end in `Failed(UNEXPECTED_ERROR)` with the cause attached, rather than leaving the
  state at `Running` forever.

`IncrementalIndexWorker` runs a full scan in the background. It is not scheduled anywhere yet: it joins the
periodic schedule only once the user turns background indexing on, which is part of the settings screen in Phase 4.

### Not built in Phase 2

The PRD lists these as "should have" and they are not in the agreed Phase 2 scope: near-duplicate documents,
similar and blurry photos, old screenshots as their own detector, and redundant messaging media as its own
detector (duplicates inside WhatsApp and Telegram media are still found by the exact pipeline). The scoring
already has the signals for them (`BLURRY_MEDIA`).

## 6. Where each safety rule lives

| Rule | Enforced by | Status |
| --- | --- | --- |
| 3-stage duplicate pipeline, fixed 64 KB streaming buffers | `DuplicatePipeline`, `StreamingFileHasher` | built, tested on the JVM |
| Incremental indexing | `FileIndexRepositoryImpl.applyScanBatch` | built, tested |
| Canonical paths, symbolic links never followed | `FileWalker`, `StoragePaths`, whitelist repository | built, tested on the JVM; symlink tests are skipped where the host cannot create links |
| Mandatory Original Lock | `is_original_locked`, `OriginalLockPolicy`, `SuggestionGuard` | selection built, tested; the check at deletion time is Phase 3 |
| 30-day Safe Trash by default | manifest, `TrashPolicy` | manifest built; manager is Phase 3 |
| Whitelist excludes from scans and suggestions | `FileWalker`, `WhitelistRepositoryImpl`, matcher | built, tested |
| 2 to 4 hashing workers, instant pause, resume, cancel | `HashingConcurrency`, `ScanController`, `PauseGate` | built, tested on the JVM |
| Nothing hidden, system-named or hard-linked is suggested | `SuggestionGuard` | built, tested |
| Prominent disclosure before All Files Access | onboarding | Phase 4 |

The deletion path in Phase 3 must re-check the whitelist, the Original Lock and the suggestion itself when it runs,
instead of trusting what is stored. The PRD's "Original Lock" is only as safe as that last check.

## 7. Testing

- **Pure JVM tests** (`app/src/test`): domain rules, path helpers, matcher, trash policy, settings codec, scoring,
  Original Lock policy, junk detectors, hashing engine and concurrency.
- **Robolectric tests** (`app/src/test`): the DAO queries and the Room-backed repositories against an in-memory
  database, and whole scans over real files in a temporary folder (`ScanRunnerTest`, `ScanControllerTest`). What the
  scanner was asked to read is taken from a recording hasher, which is how the stage gating is checked.
- **Instrumented tests** (`app/src/androidTest`): the schema on real device SQLite, and one whole scan on real
  storage through the real platform adapters. They need a device or emulator:
  `./gradlew connectedDebugAndroidTest`. They have never been run: they compile, nothing more.

Run everything that does not need a device with `./gradlew testDebugUnitTest`.

`assembleDebug` and `testDebugUnitTest` do not compile the instrumented tests. The Phase 1 instrumented test did
not compile, and nothing noticed until Phase 2 compiled them. Compile them explicitly:
`./gradlew compileDebugAndroidTestKotlin`.

Four caveats for JVM tests on a Windows host, all caused by the host and not by the Android code:

- Robolectric fetches its Android runtime image (about 200 MB) on first use with its own Java downloader, which
  can stall on some networks. Fetching the same artifact with any HTTP client into
  `~/.m2/repository/org/robolectric/android-all-instrumented/<version>/` avoids the stall.
- DataStore replaces its file with `File.renameTo`, which cannot overwrite an existing file on some Windows setups.
  The settings I/O tests therefore write at most once each; defaults, clamping and recovery are covered by the pure
  codec tests. On Android the rename replaces the file as usual.
- Creating symbolic links needs a privilege a plain Windows shell does not have. Two symlink tests are reported as
  skipped rather than failed there.
- `File.getPath()` turns `/` into `\`. Tests that compare paths compare canonical ones, never `File.path`.

A schema change requires bumping the `version` literal in `@Database`, a `Migration`, and a migration test.
`DatabaseModule` intentionally does not configure a destructive fallback: rebuilding the database would orphan
every file in Safe Trash and drop the whitelist.
