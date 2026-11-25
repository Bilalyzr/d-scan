package com.cleansweep.app.ui.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cleansweep.app.R
import com.cleansweep.app.ui.common.AppIcons
import com.cleansweep.app.ui.common.NoticeCard
import com.cleansweep.app.ui.common.NoticeTone
import com.cleansweep.app.ui.common.OnResumeEffect
import com.cleansweep.app.ui.common.Tag
import com.cleansweep.app.ui.common.findActivity
import com.cleansweep.app.ui.common.openAllFilesAccessSettings
import com.cleansweep.app.ui.common.openAppSettings
import com.cleansweep.app.ui.theme.CleanSweepTheme

/**
 * Screen 1: the privacy note, the prominent disclosure, and the storage permission (PRD screen 1).
 *
 * The permission is never asked for on sight. First the user reads what is accessed and what for, and
 * acknowledges it with a button; only then does the button that asks for access appear.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Coming back from the system settings page is the only way to learn about All Files Access: it has no
    // result callback of its own.
    OnResumeEffect { viewModel.onResume() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // After "Don't ask again" the system will not show its dialog again; the rationale flag is how to tell.
        val activity = context.findActivity()
        val canAskAgain = activity == null ||
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_EXTERNAL_STORAGE)
        viewModel.onRequestResult(canAskAgain)
    }

    if (state.step == OnboardingStep.GRANTED) {
        LaunchedEffect(Unit) { onFinished() }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    OnboardingContent(
        state = state,
        onDisclosureRead = viewModel::disclosureRead,
        onRequestAccess = {
            viewModel.requestLaunched()
            if (state.method == AccessMethod.ALL_FILES_SETTINGS) {
                if (!openAllFilesAccessSettings(context)) viewModel.settingsPageMissing()
            } else {
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                )
            }
        },
        onOpenAppSettings = { openAppSettings(context) },
        onNotNow = onFinished,
    )
}

/** The screen without its ViewModel, so that every state of it can be shown and tested on its own. */
@Composable
internal fun OnboardingContent(
    state: OnboardingUiState,
    onDisclosureRead: () -> Unit,
    onRequestAccess: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onNotNow: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            OnboardingActions(state, onDisclosureRead, onRequestAccess, onOpenAppSettings, onNotNow)
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Tag(
                text = stringResource(R.string.onboarding_badge),
                icon = AppIcons.Shield,
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.onboarding_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The disclosure comes before anything else that is only nice to know, so that it is on screen
            // without scrolling on any phone.
            NoticeCard(
                title = stringResource(R.string.onboarding_disclosure_title),
                text = listOf(
                    stringResource(R.string.onboarding_disclosure_data),
                    stringResource(R.string.onboarding_disclosure_purpose),
                    stringResource(R.string.onboarding_disclosure_private),
                ).joinToString("\n\n"),
                icon = AppIcons.Storage,
                tone = NoticeTone.ACTION,
            )
            NoticeCard(
                title = stringResource(R.string.onboarding_privacy_title),
                text = stringResource(R.string.onboarding_privacy_text),
                icon = AppIcons.Shield,
                tone = NoticeTone.INFO,
            )
            Point(
                icon = AppIcons.Document,
                title = stringResource(R.string.onboarding_point_duplicates_title),
                text = stringResource(R.string.onboarding_point_duplicates_text),
            )
            Point(
                icon = AppIcons.Restore,
                title = stringResource(R.string.onboarding_point_trash_title),
                text = stringResource(R.string.onboarding_point_trash_text),
            )
            Point(
                icon = AppIcons.Shield,
                title = stringResource(R.string.onboarding_point_control_title),
                text = stringResource(R.string.onboarding_point_control_text),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Point(
    icon: ImageVector,
    title: String,
    text: String,
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.size(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OnboardingActions(
    state: OnboardingUiState,
    onDisclosureRead: () -> Unit,
    onRequestAccess: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onNotNow: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val centered = TextAlign.Center
        when (state.step) {
            OnboardingStep.EXPLAINING -> {
                Button(onClick = onDisclosureRead, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onboarding_disclosure_button))
                }
            }

            OnboardingStep.REQUESTING, OnboardingStep.DENIED -> {
                val denied = state.step == OnboardingStep.DENIED
                if (denied) {
                    Text(
                        text = stringResource(R.string.onboarding_denied_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(
                            when {
                                state.settingsPageMissing -> R.string.onboarding_settings_page_missing
                                state.cannotAskAgain -> R.string.onboarding_denied_permanent_text
                                else -> R.string.onboarding_denied_text
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = centered,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.onboarding_permission_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = centered,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onRequestAccess, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (denied) R.string.action_retry else R.string.action_allow_access))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    if (denied && (state.cannotAskAgain || state.settingsPageMissing)) {
                        TextButton(onClick = onOpenAppSettings) {
                            Text(stringResource(R.string.action_open_app_settings))
                        }
                    }
                    TextButton(onClick = onNotNow) { Text(stringResource(R.string.action_not_now)) }
                }
                // Once access was refused there is no point in saying that it can be granted later.
                if (!denied) {
                    Text(
                        text = stringResource(R.string.onboarding_skip_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = centered,
                    )
                }
            }

            OnboardingStep.GRANTED -> Unit
        }
    }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun OnboardingExplainingPreview() {
    CleanSweepTheme {
        OnboardingContent(
            state = OnboardingUiState(method = AccessMethod.ALL_FILES_SETTINGS),
            onDisclosureRead = {},
            onRequestAccess = {},
            onOpenAppSettings = {},
            onNotNow = {},
        )
    }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun OnboardingDeniedPreview() {
    CleanSweepTheme {
        OnboardingContent(
            state = OnboardingUiState(
                step = OnboardingStep.DENIED,
                method = AccessMethod.RUNTIME_PERMISSION,
                cannotAskAgain = true,
            ),
            onDisclosureRead = {},
            onRequestAccess = {},
            onOpenAppSettings = {},
            onNotNow = {},
        )
    }
}
