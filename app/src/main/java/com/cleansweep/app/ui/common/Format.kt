package com.cleansweep.app.ui.common

import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Decimal units, as in Android's own storage settings, so the numbers here match what the system shows. */
private val SIZE_UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

/**
 * A byte count for people: `0 B`, `512 B`, `1 KB`, `12.5 KB`, `156 KB`, `2.4 GB`, `84 GB`.
 *
 * One decimal below 100 of a unit when it says something, none from 100 up. Sizes come from disk and can be off
 * by a block either way, so more digits would only suggest a precision that is not there.
 */
fun formatBytes(
    bytes: Long,
    locale: Locale = Locale.getDefault(),
): String {
    if (bytes <= 0L) {
        return "0 B"
    }
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1_000.0 && unit < SIZE_UNITS.lastIndex) {
        value /= 1_000.0
        unit++
    }
    // 999.6 KB reads as 1.0 MB rather than as 1000.0 KB.
    if (value >= 999.5 && unit < SIZE_UNITS.lastIndex) {
        value /= 1_000.0
        unit++
    }
    val number = when {
        unit == 0 -> String.format(locale, "%d", bytes)
        // 99.96 would round to 100.0, which reads better as 100.
        value < 99.95 -> oneDecimal(value, locale)
        else -> String.format(locale, "%.0f", value)
    }
    return number + " " + SIZE_UNITS[unit]
}

/** `2.4`, or `84` when there is nothing behind the decimal point. */
private fun oneDecimal(
    value: Double,
    locale: Locale,
): String {
    val formatted = String.format(locale, "%.1f", value)
    val zeroEnd = DecimalFormatSymbols.getInstance(locale).decimalSeparator + "0"
    return if (formatted.endsWith(zeroEnd)) formatted.dropLast(2) else formatted
}

/** A count for people, with the separators of the locale: `6,265`. */
fun formatCount(
    count: Long,
    locale: Locale = Locale.getDefault(),
): String = NumberFormat.getIntegerInstance(locale).format(count)

/** A day, as `12 Mar 2026` in the locale and time zone of the device. */
fun formatDate(
    epochMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    .withLocale(locale)
    .format(Instant.ofEpochMilli(epochMillis).atZone(zone))
