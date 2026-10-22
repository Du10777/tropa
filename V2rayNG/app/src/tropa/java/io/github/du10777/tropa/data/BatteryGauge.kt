package io.github.du10777.tropa.data

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/** Reads the battery facts for the energy budget (BatteryManager properties, no receivers). */
object BatteryGauge {
    /** A full charge below this is not believable: the counter is junk on this device (or in mAh). */
    private const val MIN_CAPACITY_UAH = 500_000L

    fun read(context: Context): BatteryState {
        val manager = context.getSystemService(BatteryManager::class.java) ?: return BatteryState.UNKNOWN
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 } ?: 100
        // isCharging is false once a plugged-in battery is full; the status still says so.
        val full = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) == BatteryManager.BATTERY_STATUS_FULL
        val charge = manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeIf { it > 0 && it != Long.MIN_VALUE }
        val state = BatteryState(
            chargeUah = charge,
            levelPercent = level,
            charging = manager.isCharging || full,
            powerSave = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true,
        )
        return if (state.capacityUah?.let { it < MIN_CAPACITY_UAH } == true) state.copy(chargeUah = null) else state
    }
}
