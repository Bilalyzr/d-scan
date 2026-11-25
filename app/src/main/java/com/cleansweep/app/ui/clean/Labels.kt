package com.cleansweep.app.ui.clean

import com.cleansweep.app.domain.model.ReasonCode
import com.cleansweep.app.domain.model.SuggestionCategory
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cleansweep.app.R

/** Why an item is suggested, in a few words (PRD 4.4: the reason tag). */
@Composable
fun ReasonCode.label(): String = stringResource(
    when (this) {
        ReasonCode.EXACT_COPY -> R.string.reason_exact_copy
        ReasonCode.TEMP_OR_LOG -> R.string.reason_temp_or_log
        ReasonCode.THUMBNAIL_CACHE -> R.string.reason_thumbnail_cache
        ReasonCode.ZERO_BYTE -> R.string.reason_zero_byte
        ReasonCode.INSTALLED_APK -> R.string.reason_installed_apk
        ReasonCode.OLD_INSTALLER -> R.string.reason_old_installer
        ReasonCode.EMPTY_FOLDER -> R.string.reason_empty_folder
        ReasonCode.LARGE_FILE -> R.string.reason_large_file
        ReasonCode.IN_DOWNLOADS -> R.string.reason_in_downloads
        ReasonCode.IN_MESSAGING_SENT -> R.string.reason_in_messaging_sent
        ReasonCode.STALE_90_DAYS -> R.string.reason_stale_90_days
        ReasonCode.BLURRY_MEDIA -> R.string.reason_blurry_media
        ReasonCode.RECENT -> R.string.reason_recent
    },
)

/** What kind of suggestion it is, by name: for the list of what is about to be moved to the trash. */
@Composable
fun SuggestionCategory.label(): String = stringResource(
    when (this) {
        SuggestionCategory.EXACT_DUPLICATE -> R.string.suggest_exact_duplicate
        SuggestionCategory.OBSOLETE_INSTALLER -> R.string.suggest_obsolete_installer
        SuggestionCategory.TEMP_FILE -> R.string.suggest_temp_file
        SuggestionCategory.ZERO_BYTE -> R.string.suggest_zero_byte
        SuggestionCategory.EMPTY_FOLDER -> R.string.suggest_empty_folder
        SuggestionCategory.LARGE_FORGOTTEN -> R.string.suggest_large_forgotten
    },
)
