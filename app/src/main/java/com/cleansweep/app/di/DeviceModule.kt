package com.cleansweep.app.di

import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier

/** The Android version the app is running on, as `Build.VERSION.SDK_INT`. Injected so that screens can be tested on any. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class SdkInt

@Module
@InstallIn(SingletonComponent::class)
object DeviceModule {

    @Provides
    @SdkInt
    fun provideSdkInt(): Int = Build.VERSION.SDK_INT
}
