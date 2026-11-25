package com.cleansweep.app.data.scanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.system.ErrnoException
import android.system.Os
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import com.cleansweep.app.domain.scanner.HardLinkReader
import com.cleansweep.app.domain.scanner.StorageAccess
import com.cleansweep.app.domain.scanner.StorageRootsProvider
import com.cleansweep.app.domain.scanner.StorageSpaceReader
import com.cleansweep.app.domain.scanner.VolumeSpace
import com.cleansweep.app.domain.scanner.walker.MimeTypeResolver
import com.cleansweep.app.domain.util.StoragePaths
import java.io.File

/**
 * [StorageRootsProvider] on the mounted storage volumes: the phone's own shared storage first, then SD cards
 * and USB drives. A volume that is ejected, unmounted or unmounted-but-still-listed is left out, so a scan
 * never starts by walking a folder that is not there.
 */
class AndroidStorageRootsProvider(
    private val context: Context,
) : StorageRootsProvider {

    override fun roots(): List<String> {
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            ?: return listOfNotNull(Environment.getExternalStorageDirectory().toRoot())
        return manager.storageVolumes
            .asSequence()
            .filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }
            .mapNotNull { volume -> rootDirectoryOf(volume)?.toRoot() }
            .distinct()
            .toList()
    }

    /**
     * The folder a volume is reachable under. A volume that is mounted read-only is still returned: its files
     * can be scanned and found to be duplicates, even if deleting them will need more from the user.
     */
    private fun rootDirectoryOf(volume: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return volume.directory
        }
        if (volume.isPrimary) {
            return Environment.getExternalStorageDirectory()
        }
        // Android 10 has no public accessor for a removable volume's root. The app's own folder on that
        // volume is public, though, and the volume root is what lies above its "Android/data/<package>/files" tail.
        val uuid = volume.uuid ?: return null
        val ownFolder = context.getExternalFilesDirs(null)
            .orEmpty()
            .filterNotNull()
            .firstOrNull { folder -> StoragePaths.canonicalize(folder.absolutePath).startsWith("/storage/$uuid/") }
        return ownFolder?.let { File(it.absolutePath.substringBefore("/Android/data/")) }
            ?: File("/storage/$uuid").takeIf { it.isDirectory }
    }

    private fun File.toRoot(): String? = takeIf { isDirectory }?.let { StoragePaths.canonicalize(it.absolutePath) }
}

/**
 * [StorageAccess] for shared storage.
 *
 * Android 10 has neither scoped storage nor All Files Access; the legacy read permission is what counts there.
 * From Android 11 on, the user grants All Files Access in the system settings.
 */
class AndroidStorageAccess(
    private val context: Context,
) : StorageAccess {

    override fun canReadStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        }
}

/**
 * [StorageSpaceReader] on `StatFs`, for the same volumes a scan would walk. The free space is what any app may
 * still use, not counting the part the system keeps to itself.
 */
class AndroidStorageSpaceReader(
    private val roots: StorageRootsProvider,
) : StorageSpaceReader {

    override fun volumes(): List<VolumeSpace> = roots.roots().mapNotNull { root ->
        try {
            val stats = StatFs(root)
            VolumeSpace(root = root, totalBytes = stats.totalBytes, freeBytes = stats.availableBytes)
        } catch (rejected: IllegalArgumentException) {
            // The volume went away between the two calls. It is simply not measured this time.
            null
        }
    }
}

/** [MimeTypeResolver] on the platform's table of extensions. Unknown extensions give null. */
class AndroidMimeTypeResolver : MimeTypeResolver {

    override fun mimeTypeFor(lowercaseExtension: String): String? =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(lowercaseExtension)
}

/** [HardLinkReader] on the file system's own link count. */
class AndroidHardLinkReader : HardLinkReader {

    override fun linkCount(path: String): Int = try {
        Os.lstat(path).st_nlink.toInt()
    } catch (error: ErrnoException) {
        // The entry is gone or cannot be looked at; either way there is nothing to suggest, and other checks
        // will say so. Counting one link here keeps this reader from ever rejecting a file on its own.
        1
    }
}
