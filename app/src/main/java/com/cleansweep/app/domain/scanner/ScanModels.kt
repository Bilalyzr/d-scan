package com.cleansweep.app.domain.scanner

import com.cleansweep.app.domain.model.FileCategory
import com.cleansweep.app.domain.model.SuggestionCategory

/** What a scan should cover. */
data class ScanRequest(
    /** Canonical paths of the storage volumes to walk. Null means every volume that is mounted. */
    val roots: List<String>? = null,
    /**
     * File categories to index. Empty means every category.
     *
     * A scan of every category is a full scan, and only a full scan may work out which folders are empty:
     * with a category filter, a folder full of skipped files looks empty without being so.
     */
    val categories: Set<FileCategory> = emptySet(),
) {
    val isFullScan: Boolean
        get() = categories.isEmpty()
}

enum class ScanPhase {
    /** Folders are being read and files written to the index. */
    WALKING,

    /** The first and the last 4 KB of each candidate are being hashed. */
    PARTIAL_HASHING,

    /** Whole files are being hashed. */
    FULL_HASHING,

    /** Duplicates and junk are being turned into suggestions. */
    ANALYSING,
}

/** A snapshot of a running scan, for the progress screen. */
data class ScanProgress(
    val phase: ScanPhase = ScanPhase.WALKING,
    /** Regular files met so far, indexed or not. */
    val filesScanned: Long = 0L,
    val bytesScanned: Long = 0L,
    val foldersScanned: Long = 0L,
    val currentFolder: String? = null,
    /** Files hashed so far in the current hashing stage. */
    val filesHashed: Int = 0,
    /** How many files the current hashing stage has to get through, once that is known. */
    val filesToHash: Int? = null,
    /** Files or folders that could not be read, hashed or indexed. The scan goes on past each of them. */
    val errors: Int = 0,
)

/** Everything a finished scan learned, for the summary the app shows afterwards. */
data class ScanSummary(
    /** The storage volumes the scan could read. */
    val rootsScanned: List<String>,
    val filesIndexed: Long,
    val bytesScanned: Long,
    val foldersScanned: Long,
    val emptyFolders: Int,
    val duplicateGroups: Int,
    /** Space the redundant copies of every duplicate group take up together. */
    val duplicateBytes: Long,
    val suggestions: Map<SuggestionCategory, Int>,
    val errors: Int,
)

/** Receives snapshots while a scan runs. Called very often; thin them out before they reach a screen. */
fun interface ScanProgressListener {
    fun onProgress(progress: ScanProgress)
}

/** Lists the storage the app may scan. */
fun interface StorageRootsProvider {
    /** Canonical paths of every storage volume that is mounted, without duplicates. */
    fun roots(): List<String>

    companion object {
        val NONE: StorageRootsProvider = StorageRootsProvider { emptyList() }
    }
}

/** Whether the app is allowed to read shared storage at all. */
fun interface StorageAccess {
    fun canReadStorage(): Boolean

    companion object {
        val GRANTED: StorageAccess = StorageAccess { true }
        val DENIED: StorageAccess = StorageAccess { false }
    }
}

/** Why a scan could not be run or could not finish. */
enum class ScanFailure {
    /** The user has not granted access to shared storage. */
    STORAGE_PERMISSION_MISSING,

    /** There is no storage to scan: every volume is ejected, unmounted or unreadable. */
    NO_STORAGE_TO_SCAN,

    /** Something unexpected went wrong. The cause is attached to the state. */
    UNEXPECTED_ERROR,
}

/** A scan that could not be carried out, for a reason the app can explain to the user. */
class ScanException(
    val failure: ScanFailure,
    detail: String,
) : Exception(detail)
