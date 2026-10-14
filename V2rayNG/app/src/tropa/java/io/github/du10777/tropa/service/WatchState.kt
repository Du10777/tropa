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
}

data class WatchNode(
    val tag: String,
    val name: String,
    val alive: Boolean?,
    val latencyMs: Long?,
    val active: Boolean,
)

/** One entry of the connection log: a check made by the watchdog or a session event. */
data class ProbeEvent(
    val at: Long,
    /**
     * Checks: latency | download | direct. Session: start | stop | connected | no_server |
     * no_internet | reload | switch. Details: network (target = wifi | cellular | ethernet | none |
     * other, ok = validated by Android) | servers | screen_on | screen_off | core (Xray message).
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
