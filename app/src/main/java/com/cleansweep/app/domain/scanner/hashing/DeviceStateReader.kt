package com.cleansweep.app.domain.scanner.hashing

/** Reads the processor, battery, thermal and power-save state that the hashing worker count depends on. */
fun interface DeviceStateReader {
    suspend fun currentLoad(): DeviceLoad
}
