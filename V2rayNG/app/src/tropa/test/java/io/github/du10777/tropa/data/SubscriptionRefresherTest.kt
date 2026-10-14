package io.github.du10777.tropa.data

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubscriptionRefresherTest {
    private val hour = 3_600_000L

    @Test
    fun `a subscription is due after its interval`() {
        val now = 100 * hour
        assertFalse(SubscriptionRefresher.isDue(lastUpdated = now - 11 * hour, intervalMinutes = 12 * 60, now = now))
        assertTrue(SubscriptionRefresher.isDue(lastUpdated = now - 12 * hour, intervalMinutes = 12 * 60, now = now))
        assertTrue(SubscriptionRefresher.isDue(lastUpdated = 0, intervalMinutes = 12 * 60, now = now))
    }

    @Test
    fun `too short intervals count as 15 minutes`() {
        val now = 100 * hour
        assertFalse(SubscriptionRefresher.isDue(lastUpdated = now - 10 * 60_000L, intervalMinutes = 0, now = now))
        assertTrue(SubscriptionRefresher.isDue(lastUpdated = now - 15 * 60_000L, intervalMinutes = 0, now = now))
    }
}
