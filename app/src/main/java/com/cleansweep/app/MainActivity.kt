package com.cleansweep.app

import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleansweep.app.domain.repository.SettingsRepository
import com.cleansweep.app.domain.scanner.StorageAccess
import com.cleansweep.app.ui.navigation.CleanSweepRoot
import com.cleansweep.app.ui.navigation.AppViewModel
import com.cleansweep.app.ui.theme.CleanSweepTheme
import com.cleansweep.app.ui.theme.isDark
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** Single-activity host: everything the user sees is one Compose navigation graph. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var storageAccess: StorageAccess

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        paintWindowBackground()
        // Read once, before the navigation graph is built: it decides whether the app opens on onboarding.
        val startOnOnboarding = !storageAccess.canReadStorage()
        setContent {
            val appViewModel: AppViewModel = hiltViewModel()
            val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()
            CleanSweepTheme(darkTheme = themeMode.isDark(isSystemInDarkTheme())) {
                CleanSweepRoot(startOnOnboarding = startOnOnboarding)
            }
        }
    }

    /**
     * The window is on screen before Compose draws its first frame, in the colour the XML theme gives it. That
     * colour follows the system's dark setting. When the user chose another theme for the app, the window is
     * recoloured here, so that the app does not flash in the wrong colours at start.
     *
     * The theme is read from disk for this, once, and only for as long as a cold start can spare. If it takes
     * longer, the window keeps the system colour for that one frame.
     */
    private fun paintWindowBackground() {
        val theme = runBlocking { withTimeoutOrNull(200L) { settings.current().themeMode } }
        val color = if (theme.isDark(systemIsDark())) R.color.window_background_dark else R.color.window_background
        window.setBackgroundDrawable(ColorDrawable(ContextCompat.getColor(this, color)))
    }

    private fun systemIsDark(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}
