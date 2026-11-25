package com.cleansweep.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cleansweep.app.R
import com.cleansweep.app.domain.util.StoragePaths

private const val PRIMARY_ROOT: String = "/storage/emulated/0"

/** The folder that holds [path], the way a person would name it: `Internal storage/DCIM` rather than a raw path. */
@Composable
fun parentForDisplay(
    path: String,
): String? = StoragePaths.parentOf(path)?.let { forDisplay(it) }

/**
 * A path the way a person would name it. The primary storage does not appear as `/storage/emulated/0` anywhere
 * in the system's own files app, so it is not shown that way here either.
 */
@Composable
fun forDisplay(
    path: String,
): String {
    val internal = stringResource(R.string.path_internal_storage)
    return when {
        path == PRIMARY_ROOT -> internal
        path.startsWith("$PRIMARY_ROOT/") -> "$internal/" + path.removePrefix("$PRIMARY_ROOT/")
        else -> path
    }
}
