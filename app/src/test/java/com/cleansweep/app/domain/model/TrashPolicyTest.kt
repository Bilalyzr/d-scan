package com.cleansweep.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashPolicyTest {

    private val day: Long = 24L * 60 * 60 * 1000
    private val deletedAt: Long = 1_700_000_000_000L

    @Test
    fun `items expire exactly 30 days after deletion`() {
        assertEquals(deletedAt + 30 * day, TrashPolicy.expiresAt(deletedAt))
        assertEquals(30, TrashPolicy.RETENTION_DAYS)
    }

    @Test
    fun `an item is purgeable once its expiry is reached`() {
        val expiresAt = TrashPolicy.expiresAt(deletedAt)
        assertFalse(TrashPolicy.isExpired(expiresAt, now = expiresAt - 1))
        assertTrue(TrashPolicy.isExpired(expiresAt, now = expiresAt))
        assertTrue(TrashPolicy.isExpired(expiresAt, now = expiresAt + day))
    }

    @Test
    fun `days remaining counts whole days rounded up and never goes negative`() {
        val expiresAt = TrashPolicy.expiresAt(deletedAt)
        assertEquals(30, TrashPolicy.daysRemaining(expiresAt, now = deletedAt))
        // Rounded up: with 1.5 days left the countdown shows 2 and never reaches 0 before the purge is due.
        assertEquals(2, TrashPolicy.daysRemaining(expiresAt, now = expiresAt - day - day / 2))
        assertEquals(1, TrashPolicy.daysRemaining(expiresAt, now = expiresAt - 1))
        assertEquals(0, TrashPolicy.daysRemaining(expiresAt, now = expiresAt))
        assertEquals(0, TrashPolicy.daysRemaining(expiresAt, now = expiresAt + 10 * day))
    }
}
