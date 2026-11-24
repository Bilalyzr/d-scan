package com.cleansweep.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cleansweep.app.ui.bootstrap.BootstrapScreen
import com.cleansweep.app.ui.theme.CleanSweepTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity host. In Phase 1 it shows a bootstrap screen that proves the whole graph is wired
 * (ViewModel -> repositories -> Room). Phase 4 replaces its content with the navigation host.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            CleanSweepTheme {
                BootstrapScreen()
            }
        }
    }
}
