package com.cleansweep.app.data.scanner

import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.PowerManager
import com.cleansweep.app.domain.scanner.hashing.DeviceLoad
import com.cleansweep.app.domain.scanner.hashing.DeviceStateReader
import com.cleansweep.app.domain.scanner.hashing.ThermalStatus
import com.cleansweep.app.domain.scanner.junk.InstalledApkOracle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** [DeviceStateReader] on the battery, thermal and power-save state of this device. */
class AndroidDeviceStateReader(
    private val context: Context,
) : DeviceStateReader {

    override suspend fun currentLoad(): DeviceLoad {
        val power = context.getSystemService(PowerManager::class.java)
        val batteryPercent = context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
        return DeviceLoad(
            availableProcessors = Runtime.getRuntime().availableProcessors(),
            batteryPercent = batteryPercent,
            thermalStatus = power?.let { thermalStatusOf(it.currentThermalStatus) } ?: ThermalStatus.NONE,
            isPowerSaveMode = power?.isPowerSaveMode == true,
        )
    }

    private fun thermalStatusOf(status: Int): ThermalStatus = when (status) {
        PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
        PowerManager.THERMAL_STATUS_CRITICAL -> ThermalStatus.CRITICAL
        PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalStatus.EMERGENCY
        PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.SHUTDOWN
        else -> ThermalStatus.NONE
    }
}

/**
 * [InstalledApkOracle] on `PackageManager`.
 *
 * The installer is read for its package name and version. The app counts as superseded when a package of that
 * name is installed at the installer's version or a newer one. An older installed version means the installer
 * is an update the user may still want, so it is not reported.
 */
class AndroidInstalledApkOracle(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) : InstalledApkOracle {

    override suspend fun isSupersededByInstalledApp(apkPath: String): Boolean =
        withContext(ioDispatcher) {
            val manager = context.packageManager
            // Reads and parses the installer's manifest: disk work, hence the dispatcher above.
            val installer = manager.getPackageArchiveInfo(apkPath, 0) ?: return@withContext false
            val installed = try {
                manager.getPackageInfo(installer.packageName, 0)
            } catch (error: PackageManager.NameNotFoundException) {
                return@withContext false
            }
            installed.longVersionCode >= installer.longVersionCode
        }
}
