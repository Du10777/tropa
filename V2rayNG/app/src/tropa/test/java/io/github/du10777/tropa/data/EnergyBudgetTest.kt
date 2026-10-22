package io.github.du10777.tropa.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnergyBudgetTest {
    /** Half charged 5000 mAh battery: allowance 25 000 µAh/h with the screen on, 15 000 with it off. */
    private fun battery(chargeUah: Long = 2_500_000) = BatteryState(chargeUah, levelPercent = 50, charging = false, powerSave = false)

    private val minute = 60_000L

    @Test
    fun `nothing measured yet gives the seeds`() {
        val b = EnergyBudget()
        assertEquals(30, b.intervalSec(CheckKind.ACTIVE, 30, ScreenMode.ON, battery(), 484.0))
        assertEquals(300, b.intervalSec(CheckKind.SCREEN_OFF, 300, ScreenMode.OFF, battery(), 36.0))
    }

    @Test
    fun `battery saver and a low battery give the longest intervals, charging gives the seeds`() {
        val b = EnergyBudget(model = EnergyModel(uahPerFetch = 1000.0, samples = 5))
        assertEquals(900, b.intervalSec(CheckKind.SCREEN_OFF, 300, ScreenMode.OFF, battery().copy(powerSave = true), 36.0))
        val low = BatteryState(800_000, levelPercent = 15, charging = false, powerSave = false)
        assertEquals(120, b.intervalSec(CheckKind.ACTIVE, 30, ScreenMode.ON, low, 500.0))
        assertEquals(30, b.intervalSec(CheckKind.ACTIVE, 30, ScreenMode.ON, low.copy(charging = true), 500.0))
    }

    @Test
    fun `over the allowance the intervals stretch, inside the corridor`() {
        // 500 requests an hour × 100 µAh = 50 000 µAh/h, twice the allowance.
        val twice = EnergyBudget(model = EnergyModel(uahPerFetch = 100.0, samples = 1))
        assertEquals(60, twice.intervalSec(CheckKind.ACTIVE, 30, ScreenMode.ON, battery(), 500.0))
        val far = EnergyBudget(model = EnergyModel(uahPerFetch = 1000.0, samples = 1))
        assertEquals(120, far.intervalSec(CheckKind.ACTIVE, 30, ScreenMode.ON, battery(), 500.0))
    }

    @Test
    fun `with room to spare the checks run more often only after a few measurements`() {
        // 500 × 10 µAh = 5 000 µAh/h, a fifth of the allowance.
        val unsure = EnergyBudget(model = EnergyModel(uahPerFetch = 10.0, samples = 1))
        assertEquals(30, unsure.intervalSec(CheckKind.ACTIVE, 30, ScreenMode.ON, battery(), 500.0))
        val sure = EnergyBudget(model = EnergyModel(uahPerFetch = 10.0, samples = EnergyBudget.CONFIDENT_SAMPLES))
        assertEquals(15, sure.intervalSec(CheckKind.ACTIVE, 30, ScreenMode.ON, battery(), 500.0))
    }

    @Test
    fun `a seed outside the corridor widens it`() {
        val b = EnergyBudget()
        assertEquals(10, b.intervalSec(CheckKind.ACTIVE, 10, ScreenMode.ON, battery(), 1000.0))
        assertEquals(1200, b.intervalSec(CheckKind.SCREEN_OFF, 1200, ScreenMode.OFF, battery(), 9.0))
        assertEquals(Corridor(10, 120), CheckKind.ACTIVE.corridor.around(10))
    }

    @Test
    fun `the cost of a request is learned from a quiet window and an ordinary one`() {
        val b = EnergyBudget()
        var now = 0L
        assertEquals(EnergyBudget.Event.QUIET_STARTED, b.tick(now, ScreenMode.OFF, battery(2_500_000), healthy = true))
        assertTrue(b.quiet(now))
        assertEquals(EnergyBudget.QUIET_MS, b.quietUntil())

        // 2 000 µAh in 10 minutes without checks: 12 000 µAh/h.
        now += 10 * minute
        assertEquals(EnergyBudget.Event.BASE_MEASURED, b.tick(now, ScreenMode.OFF, battery(2_498_000), healthy = true))
        assertEquals(12_000.0, b.model.baseUahPerHour!!, 1e-6)
        assertFalse(b.quiet(now))

        // Then an ordinary window: 60 requests, 9 000 µAh in 30 minutes = 6 000 of the phone + 3 000 of ours.
        assertNull(b.tick(now, ScreenMode.OFF, battery(2_498_000), healthy = true))
        repeat(60) { b.countFetch() }
        now += 30 * minute
        assertEquals(EnergyBudget.Event.COST_MEASURED, b.tick(now, ScreenMode.OFF, battery(2_489_000), healthy = true))
        assertEquals(50.0, b.model.uahPerFetch!!, 1e-6)
        assertEquals(1, b.model.samples)
    }

    @Test
    fun `a quiet window needs a healthy server and ends with the screen on or a wake-up`() {
        val shaky = EnergyBudget()
        assertNull(shaky.tick(0, ScreenMode.OFF, battery(), healthy = false))
        assertFalse(shaky.quiet(0))

        val screen = EnergyBudget()
        screen.tick(0, ScreenMode.OFF, battery(), healthy = true)
        assertTrue(screen.quiet(1_000))
        assertEquals(EnergyBudget.Event.QUIET_DROPPED, screen.tick(minute, ScreenMode.ON, battery(2_499_000), healthy = true))
        assertFalse(screen.quiet(minute))

        val woken = EnergyBudget()
        woken.tick(0, ScreenMode.OFF, battery(), healthy = true)
        assertTrue(woken.cancelQuiet())
        assertFalse(woken.quiet(1))
        assertFalse(woken.cancelQuiet())
    }

    @Test
    fun `charging, a missing counter or a phone that charged meanwhile teach nothing`() {
        val b = EnergyBudget(model = EnergyModel(baseUahPerHour = 12_000.0, baseAt = 0))
        assertNull(b.tick(0, ScreenMode.OFF, battery(2_500_000), healthy = true)) // ordinary window: the base is fresh
        repeat(10) { b.countFetch() }
        assertNull(b.tick(30 * minute, ScreenMode.OFF, battery(2_600_000), healthy = true))
        assertNull(b.model.uahPerFetch)

        val charging = EnergyBudget()
        assertNull(charging.tick(0, ScreenMode.OFF, battery().copy(charging = true), healthy = true))
        assertFalse(charging.quiet(0))
        assertNull(charging.tick(0, ScreenMode.OFF, BatteryState(null, 50, charging = false, powerSave = false), healthy = true))
        assertFalse(charging.quiet(0))
    }

    @Test
    fun `planned requests follow the schedule`() {
        val v = CheckSettings.DEFAULTS // 3 latency addresses; 30 s, 3 min, 15 min, screen off 5 min
        assertEquals(36.0, EnergyBudget.plannedFetchesPerHour(ScreenMode.OFF, v, 3), 1e-9)
        assertEquals(360.0 + 120.0 + 4.0, EnergyBudget.plannedFetchesPerHour(ScreenMode.ON, v, 3), 1e-9)
        assertEquals(364.0, EnergyBudget.plannedFetchesPerHour(ScreenMode.ON, v, 1), 1e-9)
    }

    @Test
    fun `capacity comes from the charge and the level, the allowance from the settings`() {
        assertEquals(5_000_000L, battery().capacityUah)
        assertNull(BatteryState(null, 50, charging = false, powerSave = false).capacityUah)
        assertNull(BatteryState(100_000, 2, charging = false, powerSave = false).capacityUah)
        assertEquals(15_000.0, EnergySettings().allowanceUahPerHour(ScreenMode.OFF, 5_000_000), 1e-9)
        assertEquals(25_000.0, EnergySettings().allowanceUahPerHour(ScreenMode.ON, 5_000_000), 1e-9)
    }
}
