package com.cleansweep.app.ui.onboarding

import com.cleansweep.app.domain.scanner.StorageAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The way through the storage permission, with the answer to the request given by a fake. */
class OnboardingViewModelTest {

    private class FakeAccess(
        var granted: Boolean = false,
    ) : StorageAccess {
        override fun canReadStorage(): Boolean = granted
    }

    @Test
    fun `nothing is asked for until the disclosure has been acknowledged`() {
        val access = FakeAccess()
        val viewModel = OnboardingViewModel(access, sdkInt = 34)

        assertEquals(OnboardingStep.EXPLAINING, viewModel.uiState.value.step)

        viewModel.onResume()

        // Merely coming back to the screen is not an answer: the request was never sent.
        assertEquals(OnboardingStep.EXPLAINING, viewModel.uiState.value.step)
    }

    @Test
    fun `acknowledging the disclosure is what unlocks the request`() {
        val access = FakeAccess()
        val viewModel = OnboardingViewModel(access, sdkInt = 34)

        viewModel.disclosureRead()

        assertEquals(OnboardingStep.REQUESTING, viewModel.uiState.value.step)
    }

    @Test
    fun `coming back from the system settings with access granted finishes onboarding`() {
        val access = FakeAccess()
        val viewModel = OnboardingViewModel(access, sdkInt = 34)
        viewModel.disclosureRead()
        viewModel.requestLaunched()

        // The user turned All Files Access on and returned to the app.
        access.granted = true
        viewModel.onResume()

        assertEquals(OnboardingStep.GRANTED, viewModel.uiState.value.step)
    }

    @Test
    fun `coming back from the system settings without access counts as a denial`() {
        val access = FakeAccess()
        val viewModel = OnboardingViewModel(access, sdkInt = 34)
        viewModel.disclosureRead()
        viewModel.requestLaunched()

        viewModel.onResume()

        assertEquals(OnboardingStep.DENIED, viewModel.uiState.value.step)
        assertFalse(viewModel.uiState.value.cannotAskAgain)
    }

    @Test
    fun `the result of the permission dialog is looked up, not taken from the dialog`() {
        val access = FakeAccess()
        val viewModel = OnboardingViewModel(access, sdkInt = 29)

        // The dialog was dismissed without an answer, and access was granted in some other way meanwhile.
        access.granted = true
        viewModel.onRequestResult(canAskAgain = true)

        assertEquals(OnboardingStep.GRANTED, viewModel.uiState.value.step)
    }

    @Test
    fun `a denial the system will not repeat is remembered as such`() {
        val access = FakeAccess()
        val viewModel = OnboardingViewModel(access, sdkInt = 29)

        viewModel.onRequestResult(canAskAgain = false)
        assertEquals(OnboardingStep.DENIED, viewModel.uiState.value.step)
        assertTrue(viewModel.uiState.value.cannotAskAgain)

        // Asking again and being refused once more does not un-remember it.
        viewModel.onRequestResult(canAskAgain = true)
        assertTrue(viewModel.uiState.value.cannotAskAgain)
    }

    @Test
    fun `Android 10 asks with a permission dialog and newer versions in the settings`() {
        assertEquals(AccessMethod.RUNTIME_PERMISSION, OnboardingViewModel(FakeAccess(), sdkInt = 29).uiState.value.method)
        assertEquals(AccessMethod.ALL_FILES_SETTINGS, OnboardingViewModel(FakeAccess(), sdkInt = 30).uiState.value.method)
        assertEquals(AccessMethod.ALL_FILES_SETTINGS, OnboardingViewModel(FakeAccess(), sdkInt = 36).uiState.value.method)
    }

    @Test
    fun `a device without the settings page says so`() {
        val viewModel = OnboardingViewModel(FakeAccess(), sdkInt = 34)

        viewModel.settingsPageMissing()

        assertEquals(OnboardingStep.DENIED, viewModel.uiState.value.step)
        assertTrue(viewModel.uiState.value.settingsPageMissing)
    }
}
