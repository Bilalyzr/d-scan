package com.cleansweep.app.ui.common

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Calls [action] whenever this screen comes to the foreground, including the first time. For checks whose answer
 * can change while the app is in the background, such as whether storage access was granted.
 */
@Composable
fun OnResumeEffect(
    action: () -> Unit,
) {
    val owner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(action)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) current()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/** The activity a composable is drawn in, or null when the context is not one. */
fun Context.findActivity(): Activity? {
    var context: Context = this
    while (context is ContextWrapper) {
        if (context is Activity) {
            return context
        }
        context = context.baseContext
    }
    return null
}

/**
 * Opens the system page where All Files Access is turned on (Android 11 and newer). The page for this one app is
 * tried first; not every device has it, so the general page comes next, and the app's own settings page last.
 *
 * @return false when the device has none of these pages, so the caller can say so instead of failing silently.
 */
fun openAllFilesAccessSettings(
    context: Context,
): Boolean {
    val packageUri = Uri.fromParts("package", context.packageName, null)
    val attempts = listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
    )
    for (intent in attempts) {
        try {
            context.startActivity(intent)
            return true
        } catch (missing: ActivityNotFoundException) {
            // This device does not have that page; the next one may exist.
        }
    }
    return false
}

/** Opens the app's own page in the system settings, where Android 10's permissions are. */
fun openAppSettings(
    context: Context,
): Boolean = try {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    true
} catch (missing: ActivityNotFoundException) {
    false
}
