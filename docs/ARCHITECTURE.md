# CleanSweep architecture

CleanSweep is an offline-first Android app that scans device storage, finds exact duplicates and junk, and
explains why each file is safe to delete. Nothing ever leaves the device.

This document records the decisions behind the code and which layer enforces which rule. Source of truth for
product behaviour is the PRD; this file only explains how the code implements it and where it deliberately
deviates.

**State:** all four phases are built: the foundation and data layer, the scanning engine, Safe Trash and the
whitelist, and the eight screens. Everything is tested on the JVM. Nothing has been run on an Android device
yet: no device or emulator was available. The screens are drawn by Robolectric in the unit tests and the
pictures are looked at, which shows layout and wording; it does not show what only a device has, such as the
system's permission pages, the video player or PDF pages.

## 1. Shape of the project

One Gradle module, `:app`, split by layer.

```
app/src/main/java/com/cleansweep/app/
├── CleanSweepApp.kt              @HiltAndroidApp; on-demand WorkManager, the start-up check of the trash, the background scan
├── MainActivity.kt               single-activity host; applies the theme setting before the first frame
├── di/                           Hilt modules: Room, DataStore, Clock, coroutines, scanner, trash, preview, clean, repositories
├── ui/                           the eight screens (section 7), one package per screen, each with its ViewModel
│   ├── navigation/               routes and the navigation graph, the tab bar, the activity's ViewModel
│   ├── onboarding/, home/, scan/, suggestions/, group/, preview/, trash/, settings/
│   ├── clean/                    shared by the hub and a group: marking, the confirmation sheet, the bar, the wording
│   ├── common/                   drawn icons, formatting, notices, paths as people name them
│   └── theme/                    Material 3 theme from the PRD colours, dynamic palette on Android 12+
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
│       ├── duplicates/           DuplicatePipeline, OriginalLockPolicy, LocationHeuristics, AutoSelectRule,
│       │                         DuplicateGroupEditor (keeping another copy, section 6)
│       ├── junk/                 JunkRules and the detectors
│       ├── scoring/              ConfidenceScorer, SuggestionEngine
│       ├── clean/                CleanSelection: what is marked for deletion, shared by every screen that marks
│       ├── trash/                Safe Trash (section 6): TrashManager, SafeTrash, TrashNaming, TrashStorage, PurgeScheduler
│       ├── whitelist/            WhitelistManager
│       ├── ScanControl.kt        the scan service as the screens see it
│       ├── StorageSpaceReader.kt, FolderLister.kt
└── data/
    ├── local/db/                 Room: entities, DAOs, query projections, CleanSweepDatabase
    ├── mapper/                   entity <-> domain
    ├── repository/               repository implementations
    └── scanner/                  Android adapters: storage roots, storage access, MIME types, link counts,
                                  device state, installed packages, the background workers; trash/ holds the
                                  storage operations of Safe Trash, its media scanner and its purge schedule
```

Dependency direction is one way: `ui` -> `domain` <- `data`. ViewModels see only the interfaces in
`domain.repository`, which keeps them testable with fakes and keeps Room out of the UI. The scanning engine
and the Safe Trash rules know nothing of Android: every platform fact reaches them through a small interface,
bound in `ScannerModule` and `TrashModule`.

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

**7. The trash manifest is written before an entry is moved.** `TrashManager` never touches an entry before its
row is in the database. A crash can then leave a row whose entry was never moved, which the next start-up detects
and forgets; it can never leave a trashed entry that the app does not know about. Section 6.5 has the detail.

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
  granularity keeps a stale cached hash. This is the standard trade-off of size-and-time caching. `TrashManager`
  compares size and modification time again before it trashes anything (section 6.2), which does not catch this
  case, since both look unchanged. What bounds the damage is Safe Trash itself: the entry is renamed, not deleted,
  and can be restored for 30 days.
- **Absent volumes keep their index.** The stale sweep is scoped to the volumes a scan actually visited, so an
  ejected SD card is not purged and does not need a full re-hash when it returns. Suggestions are likewise limited
  to files below the volumes the scan could read.
- **Cross-platform transfer has no rules yet.** Android 16 QPR2 adds a `<cross-platform-transfer>` section to the
  data extraction rules. It needs the iOS app's bundle id and team id, which do not exist until the iOS companion
  (PRD Phase 2). Add it then, with the same exclusions.

## 4. Permission and privacy posture

| Concern | Decision |
| --- | --- |
| Android 10 | Legacy storage model (`requestLegacyExternalStorage`) plus `READ`/`WRITE_EXTERNAL_STORAGE`, both capped at `maxSdkVersion` 29. Asked for by the system's permission dialog, again only after the disclosure; when the system will not ask again, the onboarding screen sends the user to the app's own settings page. |
| Android 11 and newer | `MANAGE_EXTERNAL_STORAGE` (All Files Access). Asked for only after the prominent disclosure on the onboarding screen was acknowledged; the user is sent to the system page for All Files Access. |
| Network | `INTERNET` is declared with `tools:node="remove"`, so the manifest merger strips it even if a dependency declares it. |
| Backup | Off, by two mechanisms. `allowBackup="false"` switches off every form of backup on Android 11 and lower, and `res/xml/backup_rules.xml` states the same exclusions for that range. From Android 12 on, cloud backup and device-to-device transfer follow `res/xml/data_extraction_rules.xml` instead, and a mode without rules is fully enabled, so that file excludes every storage domain from both. Auto Backup would otherwise copy the index of the user's filenames into their cloud backup or onto their next phone. |
| Read-only SD cards | Handled by refusing, by design and unverified on a device. A volume mounted read-only is scanned, so its duplicates are found. Trashing from it fails with `STORAGE_READ_ONLY`, and nothing on the volume is changed. The Storage Access Framework tree grant that would allow writing to such a volume is not built. |

The merged manifest requests exactly these permissions, and `ManifestPrivacyTest` pins the list together with the
backup attributes:

| Permission | Origin | Reason |
| --- | --- | --- |
| `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE` (up to API 29) | app | Android 10 storage access. |
| `MANAGE_EXTERNAL_STORAGE` | app | All Files Access on Android 11 and newer. |
| `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE` | WorkManager | Needed to run and reschedule background work: the trash purge, and background indexing once it has a setting. |
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
- **Entries of Safe Trash are not indexed.** A file or folder whose name carries the trash mark (section 6) is
  skipped. It still counts as content of its folder: moving that folder to the trash would carry the trashed entry
  along and cut it off from the manifest.
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
- it lies in Safe Trash already. The walker leaves such entries alone; this is the second lock on the same door;
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

`IncrementalIndexWorker` runs a full scan in the background. `BackgroundIndexingScheduler` plans it once a day
under the name `background-index`, and only while the setting "Index in the background" is on, which it is not
until the user turns it on. `BackgroundIndexing` applies the setting at every app start and whenever it changes;
if the settings cannot be read, the background scan is off. The worker never runs beside a scan the user
started: if one is running, it tries again later.

### Not built

The PRD lists these as "should have". They were not in the agreed Phase 2 scope, and Phase 3 did not add them:
near-duplicate documents, similar and blurry photos, old screenshots as their own detector, and redundant
messaging media as its own detector (duplicates inside WhatsApp and Telegram media are still found by the exact
pipeline). The scoring already has the signals for them (`BLURRY_MEDIA`).

## 6. Safe Trash and the whitelist

### 6.1 How an entry is trashed

Nothing the user deletes is gone at once. Trashing an entry only renames it, in the folder it is in, to
`.cleansweep-trashed-<token>-<name>` (`TrashNaming`), and writes a row for it in `trash_manifest` that expires
30 days later.

An entry is not copied anywhere. Copying it into the app's private storage would need as much free internal storage
as the entry is large, which is exactly what a phone that needs cleaning does not have, and it would take minutes
for a long video. Renaming within a folder takes no space and no time, works for a file of any size, and works the
same on an SD card. It is also how Android's own system trash works.

The leading dot hides the entry from MediaStore and from file managers that do not show hidden files. The media
index is told about each move, so a trashed photo leaves the gallery at once and a restored one comes back.

In the manifest, `trash_path` is that hidden path, `original_path` is where the entry came from, and `size_bytes`
is what it takes.

### 6.2 What is checked before anything is trashed

`TrashManager.trash(fileIds)` takes ids of indexed files, never paths, and looks at each file again before it is
moved. A suggestion only says what was true when the last scan ran. A file is refused when:

- it is not in the index any more (`NOT_INDEXED`);
- no suggestion asks for it, or the user dismissed the one that did (`NOT_SUGGESTED`);
- it is whitelisted (`WHITELISTED`);
- the user locked it, or it is the copy the Original Lock policy keeps for its duplicate group
  (`PROTECTED_ORIGINAL`). Which copy that is is judged by the copies that are still on storage, not by the index,
  which can be behind. If the Original had been removed by another app and the index still listed it, going by the
  index would have let "clean" delete every remaining copy;
- it is not what the suggestion was based on (`CHANGED_SINCE_SUGGESTED`): another size, another modification time,
  or a folder that is no longer empty. A new scan looks at it again;
- it is gone (`GONE`), or the storage is read-only (`STORAGE_READ_ONLY`) or fails (`COULD_NOT_MOVE`).

A refusal never touches the file. Every file asked for comes back either in `trashed` or in `refused`, with the
reason. `bytesTrashed` is what was moved, not what was freed: an entry keeps taking the same space until the trash
is emptied.

Only files and empty folders can be trashed, because only those are ever suggested. A folder with something in it
is never moved.

### 6.3 Restoring

`TrashManager.restore(entryIds)` moves each entry back to its original path and drops its row. A folder that no
longer exists is created. When something else has taken the original name meanwhile, the entry goes back next to it
as `name (restored)`, then `name (restored 2)`, and so on.

An entry whose file is no longer in the trash has nothing left to restore: its row is dropped and it is reported
under `nothingLeft`. An entry the storage would not let go of stays in the manifest and is reported under
`failures`, so it can be tried again.

A restored file is a file on storage like any other. The next scan indexes it again, and if it is still a duplicate
or still junk, it is suggested again.

### 6.4 Deleting for good

- `purgeExpired()` deletes every entry whose 30 days are over.
- `emptyTrashPermanently()` deletes every entry, whatever its age. It is the only call that cannot be undone, so
  the UI must have the user's explicit confirmation first.

Deleting is where the app could destroy something that is not its own, so three things are never deleted:

- a path that does not carry the trash mark. A manifest row that points at anything else is not ours: the row is
  dropped and the file is not touched;
- a folder that has something in it. The trash only ever trashed it empty, so what is in it was put there by
  someone else. Nothing in it is touched and the entry stays, so that the user can see it and restore it;
- an entry the storage refuses to give up. Its row stays, and the purge tries again later.

Each delete method also touches only its own kind: the one for files never removes a folder, not even an empty one,
and the other way round. This is the only place where space is freed.

### 6.5 What a crash can and cannot do

The row is written before the entry is touched, and each row is committed on its own. The two steps are deliberately
not in one database transaction: a transaction would hold the rows uncommitted while entries are already being
moved, and a crash would leave moved entries without rows.

| What the next start finds | What it means | What is done |
| --- | --- | --- |
| A row, and the entry at its trash path | Nothing went wrong. | Nothing. |
| A row, nothing at the trash path, the entry at its original path | The app stopped before the move. | The row is forgotten. The file was never moved, and the next scan treats it like any other file. |
| A row, nothing at either path | Something else removed the trashed entry. | The row is forgotten. |

A moved entry without a row cannot occur.

### 6.6 When the purge runs

- **In the background.** One recurring WorkManager request, once a day, under the name `safe-trash-purge`. It is
  planned as soon as the trash holds something and cancelled once the trash is empty, and it survives the app being
  closed and the phone being restarted. `TrashPurgeWorker` tries a failed run again twice and then gives up until
  the next one.
- **At every app start.** `CleanSweepApp` calls `TrashManager.maintain()`, which forgets the rows of section 6.5 and
  purges what has expired. It never throws: a failed check is reported, and the next start-up tries again.

The start-up check exists because background work can run late. An entry that stays too long costs nothing but
space.

### 6.7 The whitelist manager

`WhitelistManager.protect(path)` protects a file or a folder with everything below it. The kind of the rule follows
what the path is on storage. A path where nothing exists is not protected, and neither is a storage root, because
that would hide the whole storage from every scan. A path that a folder rule above already covers gets no rule of
its own: a second rule would have kept it protected after that folder rule is dropped.

Protecting takes effect at once. Whatever was indexed below the path leaves the index, and with it every
suggestion, so nothing below the path can be trashed on the strength of an old suggestion. `unprotect(rule)` drops a
rule; the files below it are scanned and suggested again from the next scan on.

### 6.8 Known limitations of Safe Trash

- **Trashing frees no space.** That is true of any trash that keeps what was trashed. The PRD's happy path shows
  "Space Reclaimed" right after "Move to Trash"; the UI cannot promise that and should say that the space is freed
  when the trash is emptied.
- **Trashed entries stay on shared storage**, as hidden files in their own folders. A file manager that shows
  hidden files lists them. Another app with All Files Access can remove them. Uninstalling CleanSweep leaves them
  behind, hidden and without a row, so they cannot be restored by the next install.
- **Trashing while a scan runs** is not blocked by the manager. A scan that walked past a file just before it was
  trashed puts its row back until the next scan. The suggestion then leads to a refusal (`GONE`), and nothing is
  deleted twice. The screens do not offer it: while a scan runs nothing can be marked or moved, and the hub and
  the screen of a group say so.
- **The rename on Android's shared storage is assumed to work** with All Files Access, since it is what the system
  trash does. `TrashManagerInstrumentedTest` checks it on a real device; it has never been run.
- **Undo puts files back, it does not stop the move.** "Undo" after moving files to the trash restores them from
  the trash, which gives them their name back or a `name (restored)` one when something else took it in the
  meantime. It is not the same as never having moved them.

## 7. The screens

### 7.1 How the app is put together

One activity, one navigation graph (`ui/navigation`). Four screens have a tab in the bar at the bottom: the
dashboard, the Suggestions Hub, Safe Trash and Settings. They are roots of their own: they have no back arrow,
and the system's back button goes to the screen beneath. Onboarding, the scan progress, the screen of one group of
duplicates and the preview are ordinary destinations.

The app opens on onboarding when it may not read storage yet, and on the dashboard otherwise. A launch route
that is replaced at once keeps the dashboard the root of the back stack.

Each screen is a thin composable over a ViewModel, and each ViewModel is tested against fakes for everything it
talks to: the repositories, the scan service (`ScanControl`), Safe Trash (`SafeTrash`), the marking
(`CleanSelectionStore`). The composables take their state and their callbacks as plain parameters, so that the
tests draw them without a ViewModel.

What is marked for deletion lives in one place, `CleanSelection` in the domain, and is shared by the hub, the
screen of a group and the preview. It records the exceptions rather than the whole set, so that the PRD's rule
holds whatever the list contains: "Safe to Clean" items are ticked from the start and "Review Recommended" items
are not, a safe item that appears after another scan starts out ticked, and one that was unticked stays so.

### 7.2 The eight screens

| Screen | What it does | What it never does |
| --- | --- | --- |
| 1. Onboarding and permissions | The privacy note and the prominent disclosure: what is read, and what for. "I understand, continue" is the only button until the disclosure was acknowledged. Then access is asked for. | Asks for the permission before the disclosure was acknowledged. A refusal has its own screen and a way back; "Not now" goes on to the dashboard, which keeps a way to allow access. |
| 2. Home dashboard | The storage gauge, what could be freed by tier, the scan button, the breakdown of the scanned files by kind, and the state of the last scan. | Shows a gauge when no volume could be measured; it says so instead. |
| 3. Scan progress | How far a scan is and where it is, with pause, resume and cancel. When the scan is done it goes on to the hub. | Blocks anything: leaving the screen leaves the scan going. It shows a percentage only where one exists, in the two hashing stages. |
| 4. Suggestions Hub | Three tabs: safe to clean, review, and all duplicates. A rule to tick every group by: keep the oldest, the newest or the shortest path, or untick all. A sticky bar and a sheet that asks before anything moves. | Moves anything while a scan runs or without access to storage. Moves "Review Recommended" items without one more acknowledgement in the sheet. |
| 5. Duplicate group | Every copy with its path, size and dates, the kept copy first; the copies side by side; "Keep this copy"; the rule to tick by, for this group. | Ticks the kept copy. A copy kept by the rule can be offered only by keeping another copy first. |
| 6. Preview | A picture (pinch to zoom), a film or music, the first pages of a PDF, or the start of a text file, with "Keep this copy" and "Mark for deletion". | Changes the file. |
| 7. Safe Trash | What the bin holds, with the days left for each entry; put back one or all; empty the bin. | Deletes for good without one more question that says it cannot be undone. |
| 8. Settings and whitelist | The exclusions with a folder picker; the smallest file a scan looks at; the large-file threshold; the days before a large file is suggested; the daily background scan; the theme. | Excludes a whole storage, or a folder that is not there. |

### 7.3 Decisions the screens make for the user

- **"Space Reclaimed" is not shown after moving to the trash.** The PRD's happy path shows it there; Safe Trash
  frees no space until it is emptied (section 6.8). The sheet that asks for confirmation says so before anything
  moves, the hub says only that files were moved, and the bin shows how much emptying would free.
- **A group keeps its copy until the user keeps another.** "Keep this copy" on a copy that is not the kept one
  makes it the kept copy through `DuplicateGroupEditor`, and the copy that was kept until then is offered at
  once. "Keep this copy" on any other file takes its suggestion off the list, which can be undone.
- **The folder picker is the app's own.** It starts at the storages and goes down one folder at a time, showing
  what the app can see with All Files Access. The system's picker returns a document URI, which does not say
  which folder on shared storage it is; the app's own picker gives the path the whitelist needs. A single file is
  kept from being suggested from its suggestion ("Never suggest this file again"), not from Settings.
- **The numbers in Settings are steps.** The smallest file a scan looks at: nothing, 100 KB, 1 MB or 10 MB. The
  large-file threshold: 50 MB to 10 GB in eight steps. The days before a large file is suggested: 30, 60, 90,
  180 or 365. A value from somewhere else is shown on the step nearest to it. All three apply from the next scan.
- **The theme setting covers the first frame.** The activity reads the theme once before composing, within a
  short limit, and paints the window in the matching colour, so that the app does not flash in the other theme
  at start.

### 7.4 What the preview shows, and what it needs a device for

| Kind of file | Shown by | In the tests |
| --- | --- | --- |
| Image | Coil, with pinch to zoom and a double tap to reset | drawn by a stand-in |
| Video, audio | Media3's player, paused when the app goes to the background | a stand-in |
| PDF | The first three pages, drawn by the platform's `PdfRenderer` as wide as the screen | only the failure: the renderer has no native library on the JVM |
| Text | The first 64 KB of a file up to 1 MB with a text extension | read from a real file |
| Anything else | Name, path, size and dates | drawn |

The picture, the player and the PDF pages have never been seen running. That needs a device.

## 8. Where each safety rule lives

| Rule | Enforced by | Status |
| --- | --- | --- |
| 3-stage duplicate pipeline, fixed 64 KB streaming buffers | `DuplicatePipeline`, `StreamingFileHasher` | built, tested on the JVM |
| Incremental indexing | `FileIndexRepositoryImpl.applyScanBatch` | built, tested |
| Canonical paths, symbolic links never followed | `FileWalker`, `StoragePaths`, whitelist repository | built, tested on the JVM; symlink tests are skipped where the host cannot create links |
| Mandatory Original Lock | `is_original_locked`, `OriginalLockPolicy`, `SuggestionGuard`, `TrashManager` | built, tested on the JVM: when suggestions are made, and again when something is trashed |
| 30-day Safe Trash by default | `TrashManager`, `TrashNaming`, `TrashPurgeWorker` | built, tested on the JVM |
| Nothing is trashed on a stale suggestion | `TrashManager.trash` | built, tested on the JVM |
| Nothing is moved without the sheet's confirmation; a look-first item needs one more tick | `SuggestionsViewModel`, `GroupViewModel`, `CleanConfirmationSheet` | built, tested as logic and as rendered screens |
| Nothing is deleted for good without one more question | `TrashViewModel`, the dialog on the Safe Trash screen | built, tested as logic and as a rendered screen |
| Whitelist excludes from scans, suggestions and trashing | `FileWalker`, `WhitelistRepositoryImpl`, `SuggestionGuard`, `TrashManager`, `WhitelistManager` | built, tested on the JVM |
| 2 to 4 hashing workers, instant pause, resume, cancel | `HashingConcurrency`, `ScanController`, `PauseGate` | built, tested on the JVM |
| Nothing hidden, system-named or hard-linked is suggested | `SuggestionGuard` | built, tested |
| Prominent disclosure before All Files Access | onboarding screen, `OnboardingViewModel` | built, tested as a rendered screen; the system pages it opens need a device |

The PRD's "Original Lock" is only as safe as the last check, the one made when something is actually trashed.
That check exists (`TrashManager.trash`). `emptyTrashPermanently` is the one call the UI must confirm explicitly.

## 9. Testing

- **Pure JVM tests** (`app/src/test`): domain rules, path helpers, matcher, trash policy and trash naming, settings
  codec, scoring, Original Lock policy, junk detectors, hashing engine and concurrency.
- **Robolectric tests** (`app/src/test`): the DAO queries and the Room-backed repositories against an in-memory
  database; whole scans over real files in a temporary folder (`ScanRunnerTest`, `ScanControllerTest`), with what
  the scanner was asked to read taken from a recording hasher; Safe Trash over real files, moved, restored and
  purged (`TrashManagerTest`, `TrashPurgeTest`); the purge and the daily background scan as WorkManager plans and
  runs them; the whitelist manager; keeping another copy of a group (`DuplicateGroupEditorTest`).
- **ViewModel tests** (`app/src/test`, plain JVM): every screen's logic against fakes, with no Android and no
  database: what each state works out, and that each action reaches what it was meant for.
- **Rendered screens** (`app/src/test`, Robolectric with native graphics): each screen is drawn in each of its
  states, what it says is asserted, its buttons are clicked, and a picture is written to
  `app/build/screenshots/<name>.png` to look at. That is how layout and wording were checked without a device.
- **Instrumented tests** (`app/src/androidTest`): the schema on real device SQLite, one whole scan on real
  storage through the real platform adapters, and Safe Trash on real shared storage. They need a device or emulator:
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
- Robolectric's dynamic palette is light in dark mode too, so a test of dark colours or of the brand palette
  turns the dynamic palette off (`CleanSweepTheme(dynamicColor = false)`).
- A ViewModel shares its state only while something collects it, on a test scheduler that runs only when told.
  The ViewModel tests keep a collector alive, give the scheduler a turn between looks, and read the state by
  polling; a wait built on `yield` alone would keep the thread busy and never time out.

A schema change requires bumping the `version` literal in `@Database`, a `Migration`, and a migration test.
`DatabaseModule` intentionally does not configure a destructive fallback: rebuilding the database would orphan
every entry in Safe Trash and drop the whitelist.

## 10. Decisions waiting for confirmation

These were reported to the product owner after each phase and have not been answered. The code follows the choice
given here.

| Phase | Decision |
| --- | --- |
| 1 | Application id `com.cleansweep.app` is a placeholder. |
| 1 | Columns beyond the PRD schema: `file_index.category`, `created_at`, `is_directory`, `is_original_locked`, and `suggestion_items.reason_codes`. |
| 1 | Empty folders are placeholder rows in `file_index`. |
| 1 | "Oldest" for the Original Lock means oldest modification time. |
| 1 | Backups are off entirely, so the whitelist does not survive a change of phone. |
| 1 | `targetSdk` 36 and AGP 9.2.1 are kept although both have newer versions. |
| 1 | `ACCESS_NETWORK_STATE` is left in, merged in by WorkManager. |
| 2 | Scoring starts from a base of 35, derived from the PRD's weights and thresholds, and the score is kept inside the tier's range. |
| 2 | Temp files, 0-byte files and empty folders modified in the last 7 days are never pre-selected. |
| 2 | Stage 2 uses SHA-256 over the first and last 4 KB. |
| 2 | Exact copies under 1 KB are not looked for. |
| 2 | Locking a copy makes it the Original, and the copy that was the Original by default is then offered for deletion. |
| 2 | Empty folders are suggested only two levels below the storage root or deeper, never with a system name, and only at the top of an empty tree. |
| 2 | Hidden entries are suggested only as temporary junk. |
| 2 | The hashing worker count is chosen once per scan. |
| 2 | The background indexing worker runs only when the setting is on (see Phase 4). |
| 2 | Near-duplicate documents, similar photos, old screenshots and messaging media have no detectors of their own, although the PRD lists them as "should have". |
| 3 | Safe Trash renames entries in place instead of copying them into private storage. |
| 3 | Only a suggested file can be trashed, and a file that changed since the scan is left alone. |
| 3 | Which copy is the Original is judged by the copies still on storage. |
| 3 | An empty folder is trashed like a file, by renaming it, not deleted outright. |
| 3 | The whitelist adds no rule for a path that a folder rule already covers. |
| 3 | Trashing frees no space until the trash is emptied, so the screens cannot show "Space Reclaimed" right after "Move to Trash". |
| 4 | Screens with a tab have no back arrow; the system's back button goes to the screen beneath. |
| 4 | Copies of a group that are safe to clean are ticked from the start on the group's screen, as in the hub. |
| 4 | "Review Recommended" items are moved only after one more tick in the sheet that asks for confirmation. |
| 4 | "Undo" after a move to the trash restores the files from the trash; it does not stop the move. |
| 4 | "Keep this copy" on a file that is not one of a group of duplicates takes its suggestion off the list. |
| 4 | The folder picker in Settings is the app's own and starts at the storages; a single file is kept from its suggestion. |
| 4 | Settings offer steps, not free ranges: smallest file nothing, 100 KB, 1 MB, 10 MB; large-file threshold 50 MB to 10 GB in eight steps; 30, 60, 90, 180 or 365 days. |
| 4 | Background indexing is a setting that is off until the user turns it on, then runs once a day. |
| 4 | A PDF is previewed by its first three pages, a text file by its first 64 KB, and only up to 1 MB. |
| 4 | Not built from the PRD: smart alerts (notifications when storage runs low, weekly summaries), the cleanup history, and a file browser and search. The last two matter for the Play Console's permission declaration, which asks for file management features besides cleaning. |

