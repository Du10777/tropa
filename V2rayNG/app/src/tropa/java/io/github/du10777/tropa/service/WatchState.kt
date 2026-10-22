package io.github.du10777.tropa.service

import android.content.Context
import android.content.Intent
import com.google.gson.Gson
import com.v2ray.ang.handler.MmkvManager

/** What the watchdog knows about the tunnel. */
enum class WatchPhase {
    /** The tunnel is up, the first check has not passed yet. */
    CHECKING,

    /** Data really goes through (latency and a 64 KB download passed). */
    CONNECTED,

    /** The phone is online but no server answers: reconnecting. */
    NO_SERVER,

    /** Not even direct sites answer: waiting for the network. */
    NO_INTERNET,

    /** Checks are turned off in the settings. */
    CHECKS_OFF,

    /** The checks pause by themselves (WatchState.pause): mobile data slower than 4G, or roaming. */
    PAUSED,

    /** Roaming in a country the user has not agreed to: traffic through Tropa is stopped, the VPN stays up. */
    ROAMING,
}

data class WatchNode(
    val tag: String,
    val name: String,
    val alive: Boolean?,
    /** Median latency until the first byte. */
    val latencyMs: Long?,
    val active: Boolean,
    /** The last speed check, or else the user's traffic seen through it, kbit/s. */
    val speedKbps: Long? = null,
    val uploadKbps: Long? = null,
    /** Checked by the self-test of its node (null before any check). */
    val selftest: Boolean? = null,
)

/** One entry of the connection log: a check made by the watchdog or a session event. */
data class ProbeEvent(
    val at: Long,
    /**
     * Checks: selftest (one request to the self-test of the node: latencyMs = time for 128 KB) |
     * latency | download | direct | speed | upload (time-boxed speed: latencyMs = measured time,
     * detail = the speed). Session: start | stop | connected | no_server |
     * no_internet | reload | switch (detail: why) | subscription (the active one changed, detail:
     * what was done with it) | roaming (target = network MCC, ok = traffic allowed) | paused
     * (target = weak | roaming) | checks (server = connection, detail: the check parameters its
     * server sent; ok = false when its Tropa-Checks header was unusable).
     * Details: network (target = wifi | cellular | ethernet | none | other, ok = validated by
     * Android) | servers | screen_on | screen_off | core (Xray message) | energy (the battery
     * budget: a quiet window, what it measured, how it moved the intervals) | mode (a server is
     * checked by the self-test of its node, or the classic way) | flow (the core's counters of the
     * current server: the user's traffic, failing connections) | diagnosis (no server answers:
     * target = the verdict, detail = what answered) | reach (debug: one resource of the diagnosis,
     * server = its name, address = host:port, target = its group, detail = ping, port, web) |
     * address (the IP a server name connects to: the DNS answer, the last proven IP, the winner) |
     * dns (session: the connection's server announced DNS inside the tunnel, or stopped; server = connection).
     */
    val kind: String,
    val server: String,
    val address: String?,
    val target: String,
    val ok: Boolean,
    val latencyMs: Long = 0,
    val bytes: Long = 0,
    val error: String? = null,
    /** Extra facts: HTTP status, network details, the Xray message. */
    val detail: String? = null,
)

data class WatchState(
    val phase: WatchPhase,
    val activeName: String? = null,
    val latencyMs: Long? = null,
    val checkedAt: Long = 0L,
    val attempt: Int = 0,
    val nextRetryAt: Long = 0L,
    val nodes: List<WatchNode> = emptyList(),
    /** System "always-on VPN" and "block connections without VPN" (Android 10+, null if unknown). */
    val alwaysOn: Boolean? = null,
    val lockdown: Boolean? = null,
    /** When the channel was confirmed this time (0 while there is no connection). */
    val connectedAt: Long = 0L,
    /** Bytes of the app (which carries all VPN traffic) when the session started. */
    val trafficBaseRx: Long = 0L,
    val trafficBaseTx: Long = 0L,
    /** Why the checks pause (TropaWatchdog.PAUSE_*), in the PAUSED phase. */
    val pause: String? = null,
    /** The country (MCC) of the roaming network, in the ROAMING phase. */
    val roamingMcc: String? = null,
    /** What the diagnosis found (Diagnosis.Verdict name), in the NO_SERVER and NO_INTERNET phases. */
    val diagnosis: String? = null,
)

/**
 * Hands the watchdog state from the VPN service process to the UI: stored in the multi-process
 * settings storage and announced with a package-local broadcast.
 */
object WatchStateStore {
    const val ACTION = "io.github.du10777.tropa.action.WATCH_STATE"
    private const val KEY = "tropa_watch_state"
    private val gson = Gson()

    fun publish(context: Context, state: WatchState) {
        MmkvManager.encodeSettings(KEY, gson.toJson(state))
        announce(context)
    }

    fun clear(context: Context) {
        MmkvManager.encodeSettings(KEY, "")
        announce(context)
    }

    fun read(): WatchState? =
        MmkvManager.decodeSettingsString(KEY)
            ?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { gson.fromJson(it, WatchState::class.java) }.getOrNull() }

    private fun announce(context: Context) {
        context.sendBroadcast(Intent(ACTION).setPackage(context.packageName))
    }
}
