package io.github.du10777.tropa.data

import com.google.gson.Gson
import com.v2ray.ang.handler.MmkvManager
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Battery facts the energy budget works with (BatteryGauge reads them on the device). */
data class BatteryState(
    /** Remaining charge in µAh (BatteryManager CHARGE_COUNTER); null when the device does not report it usably. */
    val chargeUah: Long?,
    /** 0–100. */
    val levelPercent: Int,
    val charging: Boolean,
    val powerSave: Boolean,
) {
    /** Full charge in µAh, estimated from the remaining charge and the level; null when unknown. */
    val capacityUah: Long?
        get() = chargeUah?.takeIf { it > 0 && levelPercent in MIN_LEVEL_FOR_CAPACITY..100 }?.let { it * 100 / levelPercent }

    /** Low battery: the checks become rare (unless the phone charges). */
    val low: Boolean get() = levelPercent in 0..LOW_LEVEL

    companion object {
        const val LOW_LEVEL = 20
        private const val MIN_LEVEL_FOR_CAPACITY = 5
        val UNKNOWN = BatteryState(chargeUah = null, levelPercent = 100, charging = false, powerSave = false)
    }
}

enum class ScreenMode { ON, OFF }

/** The range an interval may move in, seconds. */
data class Corridor(val minSec: Int, val maxSec: Int) {
    /** Widened to hold [seedSec]: a seed set by the user or the server outside the range still counts. */
    fun around(seedSec: Int) = Corridor(min(minSec, seedSec), max(maxSec, seedSec))
}

/** Scheduled checks whose interval the energy budget moves, with the corridor of each. */
enum class CheckKind(val corridor: Corridor) {
    /** The active server with the screen on (seed 30 s). */
    ACTIVE(Corridor(15, 120)),

    /** The other servers with the screen on (seed 3 min). */
    OTHERS(Corridor(180, 600)),

    /** The download (speed) check (seed 15 min). */
    SPEED(Corridor(900, 3600)),

    /** The active server with the screen off (seed 5 min). */
    SCREEN_OFF(Corridor(180, 900)),
}

/** How much battery the background checks may use, per screen state (Settings → Connection check). */
data class EnergySettings(
    val screenOnPercent: Int = 5,
    val screenOnHours: Int = 10,
    val screenOffPercent: Int = 3,
    val screenOffHours: Int = 10,
) {
    fun percent(mode: ScreenMode): Int = if (mode == ScreenMode.ON) screenOnPercent else screenOffPercent

    fun hours(mode: ScreenMode): Int = if (mode == ScreenMode.ON) screenOnHours else screenOffHours

    /** µAh an hour the checks of [mode] may use with a battery of [capacityUah]. */
    fun allowanceUahPerHour(mode: ScreenMode, capacityUah: Long): Double =
        capacityUah * percent(mode) / 100.0 / hours(mode).coerceAtLeast(1)

    companion object {
        val PERCENT_CHOICES = listOf(1, 2, 3, 5, 10)
        val HOURS_CHOICES = listOf(5, 10, 24)
        val PERCENT_RANGE = 1..20
        val HOURS_RANGE = 1..48
    }
}

/** What the budget learned about the phone, kept between sessions. */
data class EnergyModel(
    /** Battery one network request of a check costs, µAh (moving average); null until measured. */
    val uahPerFetch: Double? = null,
    /** Measurements behind [uahPerFetch]. */
    val samples: Int = 0,
    /** How fast the phone discharges with the screen off and the checks paused, µAh an hour; null until measured. */
    val baseUahPerHour: Double? = null,
    /** When [baseUahPerHour] was measured last. */
    val baseAt: Long = 0L,
)

/**
 * Keeps the background checks within a battery budget: a closed loop, pure logic (time and battery
 * facts come from outside), so it is unit tested.
 *
 * Android does not tell an app its own battery use, so the budget watches the whole phone. With the
 * screen off it now and then pauses the checks for a short quiet window to learn how fast the phone
 * discharges without them, and compares that with ordinary windows where the checks run: the
 * difference divided by the requests made is the cost of one request. With that cost the requests
 * planned for a screen state are compared with its allowance (a share of the battery over some
 * hours), and every interval of that state is stretched or shrunk by the same factor, inside its
 * corridor. The cost learned with the screen off is used with the screen on too: a request costs
 * about the same, and with the screen on the phone's own use is too large to measure ours against.
 *
 * Guards: battery saver or a low battery → the longest intervals; charging → the seeds; nothing
 * measured yet → the seeds; more often than the seeds only after a few measurements. Windows where
 * our share drowns in the phone's own use teach nothing, so noise errs on the side of rarer checks.
 */
class EnergyBudget(
    var settings: EnergySettings = EnergySettings(),
    model: EnergyModel = EnergyModel(),
) {
    @get:Synchronized
    var model: EnergyModel = model
        private set

    private class Window(val start: Long, val chargeUah: Long, val quiet: Boolean) {
        var fetches = 0
    }

    private var window: Window? = null
    private var quietUntil = 0L

    enum class Event { QUIET_STARTED, QUIET_DROPPED, BASE_MEASURED, COST_MEASURED }

    /** One network request of a check went out. */
    @Synchronized
    fun countFetch() {
        window?.takeIf { !it.quiet }?.let { it.fetches++ }
    }

    /** The checks are paused now to measure the phone without them. */
    @Synchronized
    fun quiet(now: Long): Boolean = window?.quiet == true && now < quietUntil

    /** When the running quiet window ends (0 when there is none). */
    @Synchronized
    fun quietUntil(): Long = if (window?.quiet == true) quietUntil else 0L

    /** Something happened (network change, settings): a running quiet window is dropped. True if there was one. */
    @Synchronized
    fun cancelQuiet(): Boolean {
        if (window?.quiet != true) return false
        dropWindow()
        return true
    }

    /** Forgets the open window: what follows is not comparable (checks paused, an outage). */
    @Synchronized
    fun dropWindow() {
        window = null
        quietUntil = 0L
    }

    /**
     * One watchdog round: opens and closes measurement windows (screen off, not charging, the
     * device reports its charge). [healthy] = the active server answered its last check; a quiet
     * window starts only then. Returns what happened, for the log and to save the model.
     */
    @Synchronized
    fun tick(now: Long, mode: ScreenMode, battery: BatteryState, healthy: Boolean): Event? {
        val charge = battery.chargeUah
        if (charge == null || battery.charging || mode == ScreenMode.ON) {
            val dropped = window?.quiet == true
            dropWindow()
            return if (dropped) Event.QUIET_DROPPED else null
        }
        val open = window ?: run {
            val quiet = healthy && (model.baseUahPerHour == null || now - model.baseAt >= BASE_REFRESH_MS)
            window = Window(now, charge, quiet)
            if (quiet) quietUntil = now + QUIET_MS
            return if (quiet) Event.QUIET_STARTED else null
        }
        val elapsed = now - open.start
        if (elapsed < 0) {
            // The clock went back: the window says nothing.
            val dropped = open.quiet
            dropWindow()
            return if (dropped) Event.QUIET_DROPPED else null
        }
        val used = open.chargeUah - charge
        if (open.quiet) {
            if (now < quietUntil) return null
            dropWindow()
            if (used < 0) return Event.QUIET_DROPPED
            model = model.copy(baseUahPerHour = ema(model.baseUahPerHour, used / hours(elapsed)), baseAt = now)
            return Event.BASE_MEASURED
        }
        if (elapsed < WINDOW_MS) return null
        dropWindow()
        val base = model.baseUahPerHour ?: return null
        if (used < 0 || open.fetches == 0) return null
        val ours = used - base * hours(elapsed)
        if (ours <= 0) return null
        val perFetch = (ours / open.fetches).coerceIn(MIN_UAH_PER_FETCH, MAX_UAH_PER_FETCH)
        model = model.copy(uahPerFetch = ema(model.uahPerFetch, perFetch), samples = model.samples + 1)
        return Event.COST_MEASURED
    }

    /**
     * The interval for [kind] in seconds: [seedSec] multiplied by [scale], kept in the corridor of
     * [kind] (widened to hold the seed).
     */
    @Synchronized
    fun intervalSec(kind: CheckKind, seedSec: Int, mode: ScreenMode, battery: BatteryState, plannedFetchesPerHour: Double): Int {
        val corridor = kind.corridor.around(seedSec)
        val scale = scale(mode, battery, plannedFetchesPerHour)
        if (scale.isInfinite()) return corridor.maxSec
        return (seedSec * scale).roundToInt().coerceIn(corridor.minSec, corridor.maxSec)
    }

    /**
     * The factor every interval of [mode] is multiplied by: 1 = the seeds, more = rarer checks,
     * infinity = the longest intervals. [plannedFetchesPerHour] = requests an hour the checks of
     * [mode] make at their seed intervals (see [plannedFetchesPerHour] in the companion).
     */
    @Synchronized
    fun scale(mode: ScreenMode, battery: BatteryState, plannedFetchesPerHour: Double): Double {
        if (battery.powerSave || (battery.low && !battery.charging)) return Double.POSITIVE_INFINITY
        if (battery.charging) return 1.0
        val perFetch = model.uahPerFetch ?: return 1.0
        val capacity = battery.capacityUah ?: return 1.0
        val allowance = settings.allowanceUahPerHour(mode, capacity)
        if (allowance <= 0.0) return Double.POSITIVE_INFINITY
        val floor = if (model.samples >= CONFIDENT_SAMPLES) MIN_SCALE else 1.0
        return (plannedFetchesPerHour * perFetch / allowance).coerceIn(floor, MAX_SCALE)
    }

    private fun ema(old: Double?, sample: Double): Double = if (old == null) sample else old + ALPHA * (sample - old)

    private fun hours(ms: Long): Double = ms / 3_600_000.0

    companion object {
        /** Length of an ordinary measurement window. */
        const val WINDOW_MS = 30 * 60_000L

        /** Length of a quiet window (checks paused); one cut short (screen on, network change) is dropped. */
        const val QUIET_MS = 10 * 60_000L

        /** The phone's own discharge is measured again after this long. */
        const val BASE_REFRESH_MS = 6 * 60 * 60_000L

        /** Measurements needed before the checks may run more often than their seeds. */
        const val CONFIDENT_SAMPLES = 3
        const val MIN_SCALE = 0.25
        const val MAX_SCALE = 8.0

        /** A single measurement outside this range is moved into it (outliers). */
        const val MIN_UAH_PER_FETCH = 1.0
        const val MAX_UAH_PER_FETCH = 2000.0
        private const val ALPHA = 0.3

        /** Requests an hour the scheduled checks of [mode] make at the seed intervals of [values] with [servers] servers. */
        fun plannedFetchesPerHour(mode: ScreenMode, values: CheckValues, servers: Int): Double {
            val perCheck = values.latencyUrls.size.coerceAtLeast(1)
            return when (mode) {
                ScreenMode.OFF -> 3600.0 / values.screenOffIntervalSec * perCheck
                ScreenMode.ON -> 3600.0 / values.activeIntervalSec * perCheck +
                    3600.0 / values.othersIntervalSec * perCheck * (servers - 1).coerceAtLeast(0) +
                    3600.0 / values.downloadIntervalSec
            }
        }
    }
}

/** Energy budget settings and what the budget learned, in the multi-process settings storage. */
object EnergyStore {
    private const val SCREEN_ON_PERCENT = "tropa_energy_on_percent"
    private const val SCREEN_ON_HOURS = "tropa_energy_on_hours"
    private const val SCREEN_OFF_PERCENT = "tropa_energy_off_percent"
    private const val SCREEN_OFF_HOURS = "tropa_energy_off_hours"
    private const val MODEL = "tropa_energy_model"
    private val gson = Gson()

    fun settings(): EnergySettings {
        val defaults = EnergySettings()
        fun read(key: String, default: Int, range: IntRange) = MmkvManager.decodeSettingsInt(key, default).takeIf { it in range } ?: default
        return EnergySettings(
            screenOnPercent = read(SCREEN_ON_PERCENT, defaults.screenOnPercent, EnergySettings.PERCENT_RANGE),
            screenOnHours = read(SCREEN_ON_HOURS, defaults.screenOnHours, EnergySettings.HOURS_RANGE),
            screenOffPercent = read(SCREEN_OFF_PERCENT, defaults.screenOffPercent, EnergySettings.PERCENT_RANGE),
            screenOffHours = read(SCREEN_OFF_HOURS, defaults.screenOffHours, EnergySettings.HOURS_RANGE),
        )
    }

    fun saveSettings(settings: EnergySettings) {
        MmkvManager.encodeSettings(SCREEN_ON_PERCENT, settings.screenOnPercent)
        MmkvManager.encodeSettings(SCREEN_ON_HOURS, settings.screenOnHours)
        MmkvManager.encodeSettings(SCREEN_OFF_PERCENT, settings.screenOffPercent)
        MmkvManager.encodeSettings(SCREEN_OFF_HOURS, settings.screenOffHours)
    }

    fun model(): EnergyModel =
        MmkvManager.decodeSettingsString(MODEL)?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { gson.fromJson(it, EnergyModel::class.java) }.getOrNull() }
            ?: EnergyModel()

    fun saveModel(model: EnergyModel) {
        MmkvManager.encodeSettings(MODEL, gson.toJson(model))
    }
}
