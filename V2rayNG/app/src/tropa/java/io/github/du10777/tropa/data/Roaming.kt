package io.github.du10777.tropa.data

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.handler.MmkvManager

/**
 * Roaming: on mobile data in another country, traffic through Tropa stops until the user agrees,
 * and that agreement holds for that country; there the checks stay off unless asked for. Back on
 * the home network, the agreements are forgotten. The VPN itself stays up the whole time, so the
 * apps never go around it into the roaming network.
 */
object Roaming {
    private const val STOP_KEY = "tropa_roaming_stop"
    private const val CHECKS_KEY = "tropa_roaming_checks"
    private const val ALLOWED_KEY = "tropa_roaming_allowed"
    private const val BLOCKED_KEY = "tropa_roaming_blocked"
    const val BLOCK_TAG = "tropa-roaming-block"
    private const val DAY_MS = 24 * 3600_000L

    /** Settings: stop the traffic in roaming (on by default). */
    var stopTraffic: Boolean
        get() = MmkvManager.decodeSettingsBool(STOP_KEY, true)
        set(value) {
            MmkvManager.encodeSettings(STOP_KEY, value)
        }

    /** Settings: keep checking the connection in roaming (off by default, about 2 MB an hour). */
    var checksInRoaming: Boolean
        get() = MmkvManager.decodeSettingsBool(CHECKS_KEY, false)
        set(value) {
            MmkvManager.encodeSettings(CHECKS_KEY, value)
        }

    /** The country (MCC) where the user agreed to transfer data; empty when none. */
    var allowedMcc: String
        get() = MmkvManager.decodeSettingsString(ALLOWED_KEY).orEmpty()
        set(value) {
            MmkvManager.encodeSettings(ALLOWED_KEY, value)
        }

    /** The core is (to be) started with every connection blocked: read by every core start. */
    var blocked: Boolean
        get() = MmkvManager.decodeSettingsBool(BLOCKED_KEY, false)
        set(value) {
            MmkvManager.encodeSettings(BLOCKED_KEY, value)
        }

    fun mustBlock(state: Cellular.State): Boolean = mustBlock(stopTraffic, state, allowedMcc)

    internal fun mustBlock(stop: Boolean, state: Cellular.State, allowed: String): Boolean =
        stop && state.roaming && state.networkMcc != allowed

    /** Whether the checks run in [state]: roaming without the setting pauses them. */
    fun checksAllowed(state: Cellular.State): Boolean = !state.roaming || checksInRoaming

    /**
     * [config] with every connection sent into a blackhole: the routing has one rule for all, the
     * observatories that ping the servers by themselves are removed.
     */
    fun blockAll(config: String): String {
        val root = runCatching { JsonParser.parseString(config).asJsonObject }.getOrNull() ?: return config
        val outbounds = root.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray().also { root.add("outbounds", it) }
        val blackhole = JsonObject().apply {
            addProperty("tag", BLOCK_TAG)
            addProperty("protocol", "blackhole")
            add("settings", JsonObject())
        }
        // First outbound = the default one for anything no rule takes.
        val rest = outbounds.filterNot { it.isJsonObject && it.asJsonObject.get("tag")?.asString == BLOCK_TAG }
        root.add("outbounds", JsonArray().apply { add(blackhole); rest.forEach(::add) })
        val routing = root.get("routing")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().also { root.add("routing", it) }
        routing.add("rules", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "field")
                addProperty("network", "tcp,udp")
                addProperty("outboundTag", BLOCK_TAG)
            })
        })
        root.remove("observatory")
        root.remove("burstObservatory")
        return root.toString()
    }

    /**
     * Mobile data of Tropa per day (all VPN traffic goes through it) over the last 30 days, or
     * since it was installed. Null when Android does not tell (Android 9 and older without a
     * subscriber id).
     */
    fun averageDailyMobileBytes(context: Context): Long? = runCatching {
        val manager = context.getSystemService(NetworkStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
        val start = maxOf(now - 30 * DAY_MS, installed)
        val days = ((now - start).toDouble() / DAY_MS).coerceAtLeast(1.0)
        val stats = manager.queryDetailsForUid(ConnectivityManager.TYPE_MOBILE, null, start, now, Process.myUid())
        var total = 0L
        try {
            val bucket = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                total += bucket.rxBytes + bucket.txBytes
            }
        } finally {
            stats.close()
        }
        (total / days).toLong()
    }.getOrNull()
}
