package com.cleansweep.app.ui.onboarding

import android.os.Build
import androidx.lifecycle.ViewModel
import com.cleansweep.app.di.SdkInt
import com.cleansweep.app.domain.scanner.StorageAccess
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** How this Android version lets the user grant access to storage. */
enum class AccessMethod {
    /** Android 10 asks with a permission dialog of its own. */
    RUNTIME_PERMISSION,

    /** Android 11 and newer have a page in the system settings for it. */
    ALL_FILES_SETTINGS,
}

enum class OnboardingStep {
    /** The privacy note and the disclosure are on screen. Nothing has been asked yet. */
    EXPLAINING,

    /** The disclosure was acknowledged; access can be asked for. */
    REQUESTING,

    /** Access was asked for and is still not granted. */
    DENIED,

    GRANTED,
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.EXPLAINING,
    val method: AccessMethod,
    /** Android 10: the system will not show its permission dialog again. The app's settings page is the only way then. */
    val cannotAskAgain: Boolean = false,
    /** This device has none of the system pages where access is granted. */
    val settingsPageMissing: Boolean = false,
)

/**
 * Walks the user through the storage permission: first the prominent disclosure, then the request, and a way
 * back for every answer the system can give (PRD screen 1).
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val storageAccess: StorageAccess,
    @SdkInt sdkInt: Int,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        OnboardingUiState(
            method = if (sdkInt >= Build.VERSION_CODES.R) AccessMethod.ALL_FILES_SETTINGS else AccessMethod.RUNTIME_PERMISSION,
        ),
    )
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    /** Whether the request was already sent off, so that coming back without access counts as an answer. */
    private var asked = false

    /** The user acknowledged the disclosure. Only from here on may the permission be requested. */
    fun disclosureRead() {
        _uiState.update { state ->
            if (state.step == OnboardingStep.EXPLAINING) state.copy(step = OnboardingStep.REQUESTING) else state
        }
    }

    /** The screen is about to ask the system for access. */
    fun requestLaunched() {
        asked = true
    }

    /** The screen is back in the foreground. The user may have granted access in the system settings. */
    fun onResume() {
        _uiState.update { state ->
            when {
                storageAccess.canReadStorage() -> state.copy(step = OnboardingStep.GRANTED)
                asked -> state.copy(step = OnboardingStep.DENIED)
                else -> state
            }
        }
    }

    /**
     * The answer to a permission request. Whether access was granted is not taken from the answer but looked up
     * again, because a dialog can be dismissed without giving one.
     *
     * @param canAskAgain False when the system will not show its dialog again (Android 10, after "Don't ask again").
     */
    fun onRequestResult(
        canAskAgain: Boolean,
    ) {
        _uiState.update { state ->
            if (storageAccess.canReadStorage()) {
                state.copy(step = OnboardingStep.GRANTED)
            } else {
                state.copy(step = OnboardingStep.DENIED, cannotAskAgain = state.cannotAskAgain || !canAskAgain)
            }
        }
    }

    /** The device has no system page for All Files Access. The user is told so instead of nothing happening. */
    fun settingsPageMissing() {
        _uiState.update { it.copy(step = OnboardingStep.DENIED, settingsPageMissing = true) }
    }
}
