package com.cleansweep.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.cleansweep.app.R
import com.cleansweep.app.domain.model.FileCategory
import com.cleansweep.app.ui.theme.LocalChartColors

/** What a kind of file is called on screen. */
@Composable
fun FileCategory.label(): String = stringResource(
    when (this) {
        FileCategory.DOCUMENT -> R.string.category_documents
        FileCategory.IMAGE -> R.string.category_images
        FileCategory.VIDEO -> R.string.category_videos
        FileCategory.AUDIO -> R.string.category_audio
        FileCategory.APK -> R.string.category_apk
        FileCategory.ARCHIVE -> R.string.category_archives
        FileCategory.OTHER -> R.string.category_other
    },
)

val FileCategory.icon: ImageVector
    get() = when (this) {
        FileCategory.DOCUMENT -> AppIcons.Document
        FileCategory.IMAGE -> AppIcons.Image
        FileCategory.VIDEO -> AppIcons.Video
        FileCategory.AUDIO -> AppIcons.Audio
        FileCategory.APK -> AppIcons.Installer
        FileCategory.ARCHIVE -> AppIcons.Archive
        FileCategory.OTHER -> AppIcons.File
    }

val FileCategory.chartColor: Color
    @Composable get() {
        val colors = LocalChartColors.current
        return when (this) {
            FileCategory.DOCUMENT -> colors.document
            FileCategory.IMAGE -> colors.image
            FileCategory.VIDEO -> colors.video
            FileCategory.AUDIO -> colors.audio
            FileCategory.APK -> colors.installer
            FileCategory.ARCHIVE, FileCategory.OTHER -> colors.other
        }
    }
