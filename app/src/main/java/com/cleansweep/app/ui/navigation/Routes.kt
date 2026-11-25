package com.cleansweep.app.ui.navigation

import android.net.Uri
import com.cleansweep.app.domain.model.FileCategory
import com.cleansweep.app.domain.model.SuggestionCategory

/**
 * Where the user can be in the app. A route is a string, so that it can be saved and restored by the navigation
 * library across process death.
 */
object Routes {
    /** Shown for a moment at start, and at once replaced by [ONBOARDING] or [HOME]. */
    const val LAUNCH = "launch"
    const val ONBOARDING = "onboarding"
    const val HOME = "home"

    /** The progress screen of a running scan. */
    const val SCAN = "scan"

    /** The Suggestions Hub. Both arguments are optional: a suggestion category and a kind of file to show. */
    const val SUGGESTIONS = "suggestions?category={category}&type={type}"

    /** One group of identical files. The hash identifies the content. */
    const val GROUP = "group/{hash}"

    /** One file, shown as large as the screen allows before the user decides about it. */
    const val PREVIEW = "preview/{fileId}"
    const val TRASH = "trash"
    const val SETTINGS = "settings"

    const val ARG_CATEGORY = "category"
    const val ARG_TYPE = "type"
    const val ARG_HASH = "hash"
    const val ARG_FILE_ID = "fileId"

    fun suggestions(
        category: SuggestionCategory? = null,
        type: FileCategory? = null,
    ): String = "suggestions?category=${category?.name.orEmpty()}&type=${type?.name.orEmpty()}"

    fun group(
        fullHash: String,
    ): String = "group/${Uri.encode(fullHash)}"

    fun preview(
        fileId: Long,
    ): String = "preview/$fileId"
}
