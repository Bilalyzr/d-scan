package com.cleansweep.app.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Gives ViewModels a test dispatcher where Android would have its main thread, and runs their work as soon as it
 * is started, so that a test can read the result of an action without advancing time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {

    override fun starting(
        description: Description,
    ) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(
        description: Description,
    ) {
        Dispatchers.resetMain()
    }
}
