package com.cleansweep.app.di

import android.content.Context
import com.cleansweep.app.ui.preview.FileTextPreviewReader
import com.cleansweep.app.ui.preview.PdfPages
import com.cleansweep.app.ui.preview.ScreenWidth
import com.cleansweep.app.ui.preview.TextPreviewReader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** What the preview needs from the platform to show a file. */
@Module
@InstallIn(SingletonComponent::class)
object PreviewModule {

    @Provides
    @Singleton
    fun provideTextPreviewReader(): TextPreviewReader = FileTextPreviewReader()

    @Provides
    @Singleton
    fun providePdfPages(): PdfPages = PdfPages()

    /** A PDF page is drawn as wide as the screen. */
    @Provides
    @Singleton
    fun provideScreenWidth(
        @ApplicationContext context: Context,
    ): ScreenWidth = ScreenWidth { context.resources.displayMetrics.widthPixels }
}
