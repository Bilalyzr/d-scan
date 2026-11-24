package com.cleansweep.app.domain.scanner.hashing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HashingConcurrencyTest {

    private fun load(
        cores: Int = 8,
        battery: Int? = 80,
        thermal: ThermalStatus = ThermalStatus.NONE,
        powerSave: Boolean = false,
    ) = DeviceLoad(cores, battery, thermal, powerSave)

    @Test
    fun `half the cores are used, kept within 2 to 4 workers`() {
        assertEquals(2, HashingConcurrency.workersFor(load(cores = 1)))
        assertEquals(2, HashingConcurrency.workersFor(load(cores = 4)))
        assertEquals(3, HashingConcurrency.workersFor(load(cores = 6)))
        assertEquals(4, HashingConcurrency.workersFor(load(cores = 8)))
        assertEquals(4, HashingConcurrency.workersFor(load(cores = 16)))
    }

    @Test
    fun `a low battery, battery saver and heat each take workers away`() {
        assertEquals(3, HashingConcurrency.workersFor(load(battery = 10)))
        assertEquals(3, HashingConcurrency.workersFor(load(powerSave = true)))
        assertEquals(3, HashingConcurrency.workersFor(load(thermal = ThermalStatus.MODERATE)))
        assertEquals(2, HashingConcurrency.workersFor(load(thermal = ThermalStatus.SEVERE)))
    }

    @Test
    fun `mild heat and an unknown battery change nothing`() {
        assertEquals(4, HashingConcurrency.workersFor(load(thermal = ThermalStatus.LIGHT)))
        assertEquals(4, HashingConcurrency.workersFor(load(battery = null)))
    }

    @Test
    fun `the count never drops below 2 or exceeds 4, whatever the state`() {
        val worst = load(cores = 64, battery = 1, thermal = ThermalStatus.SHUTDOWN, powerSave = true)
        assertEquals(HashingConcurrency.MIN_WORKERS, HashingConcurrency.workersFor(worst))

        val smallest = load(cores = 1)
        assertEquals(HashingConcurrency.MIN_WORKERS, HashingConcurrency.workersFor(smallest))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a machine without processors is a bug rather than a worker count`() {
        HashingConcurrency.workersFor(load(cores = 0))
    }

    @Test
    fun `the worker count is at least the minimum on every path`() {
        // Guards the invariant the PRD relies on, independently of the individual rules above.
        for (cores in listOf(1, 2, 3, 4, 5, 6, 7, 8, 12)) {
            for (battery in listOf(null, 5, 50, 100)) {
                for (thermal in ThermalStatus.entries) {
                    for (powerSave in listOf(false, true)) {
                        val workers = HashingConcurrency.workersFor(load(cores, battery, thermal, powerSave))
                        assertTrue("workers=$workers", workers in HashingConcurrency.MIN_WORKERS..HashingConcurrency.MAX_WORKERS)
                    }
                }
            }
        }
    }
}
