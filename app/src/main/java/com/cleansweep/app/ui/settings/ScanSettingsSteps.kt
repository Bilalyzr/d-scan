package com.cleansweep.app.ui.settings

import kotlin.math.abs

/** The values the Settings screen offers for the numbers a scan depends on. Each is a few steps, not a free range. */
object ScanSettingsSteps {

    /**
     * For "ignore files smaller than". Zero means every file. Whole decimal sizes, so that each reads on screen
     * as the round number it is meant to be.
     */
    val MIN_FILE_SIZE_BYTES: List<Long> = listOf(0L, 100_000L, 1_000_000L, 10_000_000L)

    /** One step of the large-file threshold: what is stored, and what it is called. */
    data class Threshold(
        val bytes: Long,
        val label: String,
    )

    /**
     * Above this a file counts as large, from the PRD's 50 MB up to the most the settings keep. The stored
     * numbers are multiples of the binary megabyte the settings are kept in; the labels say what people expect.
     */
    val LARGE_FILE_THRESHOLDS: List<Threshold> = listOf(
        Threshold(50L * 1024 * 1024, "50 MB"),
        Threshold(100L * 1024 * 1024, "100 MB"),
        Threshold(200L * 1024 * 1024, "200 MB"),
        Threshold(500L * 1024 * 1024, "500 MB"),
        Threshold(1024L * 1024 * 1024, "1 GB"),
        Threshold(2048L * 1024 * 1024, "2 GB"),
        Threshold(5120L * 1024 * 1024, "5 GB"),
        Threshold(10240L * 1024 * 1024, "10 GB"),
    )

    /** After this many days without a change, a large file is suggested. The PRD's figure is 90. */
    val LARGE_FILE_STALE_DAYS: List<Int> = listOf(30, 60, 90, 180, 365)

    /** The position in [steps] of the value nearest to [current], for a value that is not one of the steps. */
    fun <T> nearestIndex(
        steps: List<T>,
        current: Long,
        valueOf: (T) -> Long,
    ): Int = steps.indices.minByOrNull { index -> abs(valueOf(steps[index]) - current) } ?: 0
}
