package com.cleansweep.app.domain.scanner.hashing

/** Thermal state of the device, in the order of the `PowerManager.THERMAL_STATUS_*` constants. */
enum class ThermalStatus {
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    EMERGENCY,
    SHUTDOWN,
}

/** Everything the hashing worker count depends on. A null [batteryPercent] means the level is unknown. */
data class DeviceLoad(
    val availableProcessors: Int,
    val batteryPercent: Int?,
    val thermalStatus: ThermalStatus,
    val isPowerSaveMode: Boolean,
)

/**
 * How many files may be hashed at the same time (PRD section 6: 2 to 4 workers).
 *
 * Hashing is CPU work that heats the device and drains the battery, so the count follows the processor count and
 * drops as the device gets hotter, the battery gets lower or the user turns on battery saver. It never leaves the
 * 2 to 4 range: below 2 a scan takes unnecessarily long, above 4 it starts to starve the foreground.
 */
object HashingConcurrency {
    const val MIN_WORKERS: Int = 2
    const val MAX_WORKERS: Int = 4
    const val LOW_BATTERY_PERCENT: Int = 15

    fun workersFor(load: DeviceLoad): Int {
        require(load.availableProcessors > 0) { "availableProcessors must be positive" }
        var workers = (load.availableProcessors / 2).coerceIn(MIN_WORKERS, MAX_WORKERS)
        if (load.batteryPercent?.let { it <= LOW_BATTERY_PERCENT } == true) {
            workers--
        }
        if (load.isPowerSaveMode) {
            workers--
        }
        when (load.thermalStatus) {
            ThermalStatus.NONE, ThermalStatus.LIGHT -> Unit
            ThermalStatus.MODERATE -> workers--
            ThermalStatus.SEVERE, ThermalStatus.CRITICAL, ThermalStatus.EMERGENCY, ThermalStatus.SHUTDOWN -> {
                workers -= 2
            }
        }
        return workers.coerceIn(MIN_WORKERS, MAX_WORKERS)
    }
}
