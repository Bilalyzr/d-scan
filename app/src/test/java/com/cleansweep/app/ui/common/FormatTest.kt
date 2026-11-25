package com.cleansweep.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

class FormatTest {

    private val english: Locale = Locale.ENGLISH

    @Test
    fun `sizes use decimal units and drop the decimal from 100 of a unit up`() {
        assertEquals("0 B", formatBytes(0L, english))
        assertEquals("0 B", formatBytes(-5L, english))
        assertEquals("512 B", formatBytes(512L, english))
        assertEquals("1 KB", formatBytes(1_000L, english))
        assertEquals("12.5 KB", formatBytes(12_500L, english))
        assertEquals("99.9 KB", formatBytes(99_900L, english))
        assertEquals("100 KB", formatBytes(99_960L, english))
        assertEquals("156 KB", formatBytes(156_200L, english))
        assertEquals("2.4 GB", formatBytes(2_400_000_000L, english))
        assertEquals("128 GB", formatBytes(128_000_000_000L, english))
        assertEquals("1.2 TB", formatBytes(1_200_000_000_000L, english))
    }

    @Test
    fun `a size that rounds up to the next unit is shown in that unit`() {
        assertEquals("1 MB", formatBytes(999_600L, english))
        assertEquals("999 KB", formatBytes(999_400L, english))
        // Beyond the last unit there is nothing to round up to.
        assertEquals("1000 TB", formatBytes(999_600_000_000_000L, english))
        assertEquals("2.4 GB", formatBytes(2_400_000_000L, english))
    }

    @Test
    fun `the decimal separator follows the locale`() {
        assertEquals("1,8 GB", formatBytes(1_800_000_000L, Locale.GERMANY))
        assertEquals("84 GB", formatBytes(84_000_000_000L, Locale.GERMANY))
    }

    @Test
    fun `counts are separated the way the locale writes them`() {
        assertEquals("6,265", formatCount(6_265L, english))
        assertEquals("12", formatCount(12L, english))
    }

    @Test
    fun `dates are written out in the locale and time zone given`() {
        val instant = Instant.parse("2026-03-12T10:15:00Z")

        assertEquals("Mar 12, 2026", formatDate(instant.toEpochMilli(), ZoneOffset.UTC, english))
    }
}
