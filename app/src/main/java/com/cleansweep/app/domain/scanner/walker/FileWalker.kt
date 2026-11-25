package com.cleansweep.app.domain.scanner.walker

import com.cleansweep.app.domain.model.FileCategory
import com.cleansweep.app.domain.model.IndexedFile
import com.cleansweep.app.domain.model.WhitelistMatcher
import com.cleansweep.app.domain.scanner.PauseGate
import com.cleansweep.app.domain.scanner.trash.TrashNaming
import com.cleansweep.app.domain.util.StoragePaths
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext

/** What to walk and what to index along the way. */
data class WalkRequest(
    /** Canonical absolute paths of the storage volumes to walk. */
    val roots: List<String>,
    /** The user's exclusions. Nothing below a matching path is walked. */
    val matcher: WhitelistMatcher,
    /** Files smaller than this are not indexed. They still count as content of their folder. */
    val minFileSizeBytes: Long,
    /** File categories to index. Empty means every category. */
    val categories: Set<FileCategory>,
    /**
     * Whether to work out which folders are empty. Only a scan that indexes everything can know, because a
     * folder skipped by a category filter looks empty without being so.
     */
    val detectEmptyFolders: Boolean,
) {
    /** True when a file with this lowercase extension belongs to what the scan indexes. */
    fun includes(lowercaseExtension: String): Boolean =
        categories.isEmpty() || FileCategory.fromExtension(lowercaseExtension) in categories
}

/** Receives what the walk finds. All calls happen on the walk's I/O dispatcher. */
interface FileSink {
    /**
     * A regular file was met, whether or not this scan indexes it. Meant for the progress counters; it is
     * called right before [onFile] for a file that is indexed.
     */
    fun onFileSeen(
        sizeBytes: Long,
    )

    /** A regular file that this scan indexes. Its id is 0 until the sink persists it. */
    suspend fun onFile(
        file: IndexedFile,
    )

    /** The walk has entered a folder. Meant for progress reporting. */
    fun onFolder(path: String)
}

/** Counters of a finished walk. */
data class WalkResult(
    /** Every regular file seen, indexed or not. */
    val filesSeen: Long,
    val filesIndexed: Long,
    val bytesSeen: Long,
    val foldersSeen: Long,
    val unreadableRoots: Int,
    val unreadableFolders: Int,
    /** Entries skipped because they are symbolic links rather than ordinary files or folders. */
    val symlinksSkipped: Int,
    /**
     * The roots the walk could read. Only what was found below them says anything about storage, so a later
     * step must leave the index of every other root alone.
     */
    val rootsWalked: List<String>,
    /**
     * Folders without a single file anywhere below them, from the top of each empty tree down only as far as
     * the first folder that may be suggested. Empty unless the request asked for them.
     */
    val emptyFolders: List<IndexedFile>,
)

/**
 * Walks storage volumes without recursion, so that a deep tree cannot overflow the stack.
 *
 * The walker produces canonical paths: each folder is canonicalised as it is entered and its files are then
 * addressed as `folder + "/" + name`, which costs one lookup per folder instead of one per file. Symbolic links
 * are never followed, so an alias such as `/sdcard/self` cannot index the same file twice.
 */
class FileWalker(
    private val metadata: FileMetadataReader,
    private val mimeTypes: MimeTypeResolver = MimeTypeResolver.NONE,
    private val exclusions: ScanExclusions = ScanExclusions(),
) {
    suspend fun walk(
        request: WalkRequest,
        gate: PauseGate,
        sink: FileSink,
    ): WalkResult {
        val tally = WalkTally()
        val emptyFolders = ArrayList<IndexedFile>()
        val rootsWalked = ArrayList<String>(request.roots.size)
        for (root in request.roots) {
            coroutineContext.ensureActive()
            gate.awaitOpen()
            val rootFile = File(root)
            val rootMetadata = if (rootFile.isDirectory && rootFile.canRead()) metadata.read(root) else null
            if (rootMetadata == null) {
                // Ejected, unmounted, or never mounted: the scan goes on with the other volumes.
                tally.unreadableRoots++
                continue
            }
            rootsWalked += root
            walkRoot(rootFile, root, rootMetadata, request, gate, sink, tally, emptyFolders)
        }
        // A folder and the empty folders inside it are all empty. Suggesting all of them would count the same
        // "nothing" several times and offer deletions that overlap, so only the top of each empty tree is kept.
        val topmost = EmptyFolderRules.topmost(emptyFolders.map { it.path }).toSet()
        return tally.toResult(rootsWalked, emptyFolders.filter { it.path in topmost })
    }

    private suspend fun walkRoot(
        rootFile: File,
        rootPath: String,
        rootMetadata: FileMetadata,
        request: WalkRequest,
        gate: PauseGate,
        sink: FileSink,
        tally: WalkTally,
        emptyFolders: MutableList<IndexedFile>,
    ) {
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(rootFile, rootPath, depthBelowRoot = 0, rootMetadata))
        sink.onFolder(rootPath)

        while (stack.isNotEmpty()) {
            coroutineContext.ensureActive()
            gate.awaitOpen()
            val frame = stack.last()

            val children = frame.children
            if (children == null) {
                val listed = try {
                    frame.file.listFiles()
                } catch (error: SecurityException) {
                    null
                }
                if (listed == null) {
                    tally.unreadableFolders++
                    finish(frame, stack, request, tally, emptyFolders, listingFailed = true)
                    continue
                }
                frame.children = listed.sortedBy { it.name }.iterator()
                continue
            }
            if (!children.hasNext()) {
                finish(frame, stack, request, tally, emptyFolders, listingFailed = false)
                continue
            }

            val child = children.next()

            // An entry of Safe Trash rests where the user deleted it, under a hidden name. It is not the
            // user's storage any more, so it is not indexed. It still counts as content of its folder: moving
            // that folder to the trash would carry trashed entries along and cut them off from the manifest.
            if (TrashNaming.isTrashedName(child.name)) {
                frame.hasContent = true
                continue
            }

            val childPath = frame.path + "/" + child.name

            // Protected content is pruned, and it counts as content: a folder holding only a whitelisted
            // subfolder must never come out as empty and get suggested.
            if (exclusions.isExcluded(childPath, rootPath) || request.matcher.matches(childPath)) {
                frame.hasContent = true
                continue
            }

            val childMetadata = metadata.read(childPath) ?: continue // gone again between listing and reading
            when {
                childMetadata.isSymbolicLink -> {
                    tally.symlinksSkipped++
                    frame.hasContent = true
                }

                childMetadata.isDirectory -> {
                    // A folder reached through a link or mount point is not where its name says it is.
                    // Folders are few compared with files, so checking them is cheap.
                    if (StoragePaths.canonicalize(childPath) != childPath) {
                        tally.symlinksSkipped++
                        frame.hasContent = true
                        continue
                    }
                    tally.foldersSeen++
                    sink.onFolder(childPath)
                    stack.addLast(Frame(child, childPath, frame.depthBelowRoot + 1, childMetadata))
                }

                childMetadata.isRegularFile -> {
                    frame.hasContent = true
                    tally.filesSeen++
                    tally.bytesSeen += childMetadata.sizeBytes
                    sink.onFileSeen(childMetadata.sizeBytes)
                    val extension = FileNames.extensionOf(child.name)
                    if (childMetadata.sizeBytes >= request.minFileSizeBytes && request.includes(extension)) {
                        tally.filesIndexed++
                        sink.onFile(toIndexedFile(childPath, child.name, extension, childMetadata))
                    }
                }

                else -> frame.hasContent = true // devices, sockets and the like: never indexed, but content
            }
        }
    }

    /**
     * Called when every child of [frame] has been looked at. By then every child folder has finished too,
     * because the stack is last-in first-out, so the frame can say whether its folder is empty.
     */
    private fun finish(
        frame: Frame,
        stack: ArrayDeque<Frame>,
        request: WalkRequest,
        tally: WalkTally,
        emptyFolders: MutableList<IndexedFile>,
        listingFailed: Boolean,
    ) {
        stack.removeLast()
        // A folder that could not be listed is treated as full: claiming it is empty would be a guess.
        val isEmpty = !frame.hasContent && !listingFailed
        if (isEmpty && request.detectEmptyFolders && EmptyFolderRules.isSuggestable(frame.path, frame.depthBelowRoot)) {
            emptyFolders += frame.toPlaceholder()
        }
        val parent = stack.lastOrNull() ?: return
        if (!isEmpty) {
            parent.hasContent = true
        }
    }

    private fun toIndexedFile(
        path: String,
        name: String,
        extension: String,
        metadata: FileMetadata,
    ): IndexedFile = IndexedFile(
        path = path,
        name = name,
        extension = extension,
        mimeType = mimeTypes.mimeTypeFor(extension),
        category = FileCategory.fromExtension(extension),
        sizeBytes = metadata.sizeBytes,
        lastModified = metadata.lastModified,
        createdAt = metadata.createdAt,
    )

    /** One folder being walked: its remaining children and what has been found in it so far. */
    private class Frame(
        val file: File,
        val path: String,
        val depthBelowRoot: Int,
        private val metadata: FileMetadata,
    ) {
        var children: Iterator<File>? = null

        /** A file, a link, a pruned entry or a non-empty child folder was met in this folder. */
        var hasContent = false

        fun toPlaceholder(): IndexedFile = IndexedFile(
            path = path,
            name = path.substringAfterLast('/'),
            extension = "",
            mimeType = null,
            category = FileCategory.OTHER,
            sizeBytes = 0L,
            lastModified = metadata.lastModified,
            createdAt = metadata.createdAt,
            isDirectory = true,
        )
    }

    private class WalkTally {
        var filesSeen = 0L
        var filesIndexed = 0L
        var bytesSeen = 0L
        var foldersSeen = 0L
        var unreadableRoots = 0
        var unreadableFolders = 0
        var symlinksSkipped = 0

        fun toResult(
            rootsWalked: List<String>,
            emptyFolders: List<IndexedFile>,
        ) = WalkResult(
            filesSeen = filesSeen,
            filesIndexed = filesIndexed,
            bytesSeen = bytesSeen,
            foldersSeen = foldersSeen,
            unreadableRoots = unreadableRoots,
            unreadableFolders = unreadableFolders,
            symlinksSkipped = symlinksSkipped,
            rootsWalked = rootsWalked,
            emptyFolders = emptyFolders,
        )
    }
}
