package io.github.du10777.tropa.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken

/**
 * The self-test endpoint of an entry node: its Xray sends the name selftest.tropa.internal to a
 * local service (the conductor of the node), which answers with a fixed byte stream. One request
 * then checks a server: the node itself answers, so the check measures the first hop only, and
 * the stream is compared byte by byte, so a captive portal or a stub page cannot pass for it. The
 * name is never resolved on the phone: the core hands it to the node inside the tunnel.
 *
 * A node without the endpoint (another panel, a node not set up yet) answers the request with a
 * quick failure; then its servers are checked the classic way (the check addresses through the
 * whole chain) and the self-test is tried again some hours later.
 */
object SelfTest {
    private const val BASE = "http://selftest.tropa.internal/"
    const val UPLOAD_URL = BASE + "upload"

    /** One check downloads this much... */
    const val PROBE_BYTES = 128 * 1024L

    /** ...and the server is alive when at least this much of the stream came in time (a channel that freezes after ~16 KB fails). */
    const val ALIVE_BYTES = 64 * 1024L

    const val TIMEOUT_MS = 5_000L

    /** The speed check moves data for this long and counts what got through. */
    const val SPEED_MS = 2_000L

    /**
     * The most the speed check may move: on a fast channel it ends before its time and the speed is
     * this much over the time it took. Keeps the check light on mobile data (2 MB, not 30 MB in 2 s).
     */
    const val SPEED_BYTES = 2L * 1024 * 1024

    /**
     * A failed self-test this quick is an answer without the stream (the node has no endpoint, or it
     * refused the connection): the classic check then tells which. A slower failure is a server that
     * did not deliver in time.
     */
    const val QUICK_FAILURE_MS = 2_500L

    /** A server without the endpoint is asked for it again after this long. */
    const val RETRY_UNSUPPORTED_MS = 6 * 60 * 60_000L

    fun url(bytes: Long): String = "$BASE?bytes=$bytes"
}

/** What the core's TropaSelfTest returns (JSON). */
data class SelfTestResult(
    val status: Int = 0,
    val latencyMs: Long = 0,
    val totalMs: Long = 0,
    val bytes: Long = 0,
    val verified: Boolean = false,
    val complete: Boolean = false,
    val error: String? = null,
) {
    /** Enough of the right stream came in time. The speed never decides this. */
    val alive: Boolean get() = verified && bytes >= SelfTest.ALIVE_BYTES

    val wrongContent: Boolean get() = error?.contains("unexpected content") == true

    /** Not the stream, or a failure so quick that the node may simply have no self-test. */
    val maybeUnsupported: Boolean get() = !alive && (wrongContent || totalMs < SelfTest.QUICK_FAILURE_MS)

    /**
     * Time to fetch [size] bytes: measured when the whole size came, otherwise estimated from the
     * rate of what came after the first byte. Null for a dead server.
     */
    fun fetchMs(size: Long = SelfTest.PROBE_BYTES): Long? {
        if (!alive) return null
        if (complete && bytes == size) return totalMs
        val body = (totalMs - latencyMs).coerceAtLeast(1)
        return latencyMs + body * size / bytes.coerceAtLeast(1)
    }

    companion object {
        private val gson = Gson()

        fun parse(json: String?): SelfTestResult =
            json?.let { runCatching { gson.fromJson(it, SelfTestResult::class.java) }.getOrNull() } ?: SelfTestResult(error = "empty result")
    }
}

/** What the core's TropaSpeedTest returns (JSON). */
data class SpeedResult(
    val ok: Boolean = false,
    val latencyMs: Long = 0,
    val bytes: Long = 0,
    val ms: Long = 0,
    val error: String? = null,
) {
    /** Kilobits a second, null without a measurement. */
    val kbps: Long? get() = if (ok && ms > 0) bytes * 8 / ms else null

    companion object {
        private val gson = Gson()

        fun parse(json: String?): SpeedResult =
            json?.let { runCatching { gson.fromJson(it, SpeedResult::class.java) }.getOrNull() } ?: SpeedResult(error = "empty result")

        /** "35.2 Mbit/s" style numbers for the log: kilobits under a megabit. */
        fun label(kbps: Long): String =
            if (kbps < 1000) "$kbps kbit/s" else "%.1f Mbit/s".format(java.util.Locale.ROOT, kbps / 1000.0)
    }
}

/**
 * Which servers answer the self-test, by server (its fingerprint, so a new tag after a subscription
 * update keeps it). Unknown servers are asked; a "no" is asked again after [SelfTest.RETRY_UNSUPPORTED_MS].
 */
class SelfTestSupport(private val now: () -> Long = { System.currentTimeMillis() }) {
    enum class State { UNKNOWN, YES, NO }

    private val yes = HashSet<String>()
    private val noUntil = HashMap<String, Long>()

    @Synchronized
    fun state(key: String): State = when {
        key in yes -> State.YES
        (noUntil[key] ?: 0L) > now() -> State.NO
        else -> State.UNKNOWN
    }

    /** The server answered the self-test. True when that is news (for the log). */
    @Synchronized
    fun yes(key: String): Boolean {
        noUntil.remove(key)
        return yes.add(key)
    }

    /** The server answers, but not the self-test. True when that is news. */
    @Synchronized
    fun no(key: String): Boolean {
        val news = key in yes || (noUntil[key] ?: 0L) <= now()
        yes.remove(key)
        noUntil[key] = now() + SelfTest.RETRY_UNSUPPORTED_MS
        return news
    }
}

/** What the core counted for one outbound since it started (TropaNodeCounters). */
data class NodeCounter(val up: Long = 0, val down: Long = 0, val conns: Long = 0, val fails: Long = 0)

/**
 * The core's own view of the servers: bytes and connections per outbound, failed connections too
 * (read from its messages at any log level). Between two looks it tells whether the current server
 * carries the user's traffic — then it is alive and needs no test request, and the test would only
 * compete with that traffic — and whether new connections through it keep failing.
 */
class TrafficWatch {
    data class Delta(val down: Long, val up: Long, val conns: Long, val fails: Long, val ms: Long) {
        /** The user's data comes through (the app's own checks taken out) and connections do not keep failing. */
        val carries: Boolean get() = down >= FLOW_BYTES && !failing

        /** Most new connections fail: worth a check right away. */
        val failing: Boolean get() = fails >= FAILS_TO_WORRY && fails * 2 >= conns

        /** The rate of the user's data in kilobits a second (a lower bound of the speed). */
        val kbps: Long? get() = if (ms > 0 && down >= FLOW_BYTES) down * 8 / ms else null
    }

    private var last: Map<String, NodeCounter> = emptyMap()
    private var lastAt = 0L
    private val own = HashMap<String, Long>()

    /** Bytes the app's own checks moved through [tag]: they are not the user's traffic. */
    @Synchronized
    fun ownBytes(tag: String, bytes: Long) {
        if (bytes > 0) own[tag] = (own[tag] ?: 0L) + bytes
    }

    /** Starts over from [counters] (a new core, a new plan). */
    @Synchronized
    fun reset(counters: Map<String, NodeCounter>, now: Long) {
        last = counters
        lastAt = now
        own.clear()
    }

    /** What changed since the last look, without moving it (between the checks). */
    @Synchronized
    fun peek(counters: Map<String, NodeCounter>, now: Long): Map<String, Delta> = deltas(counters, now)

    /** What changed since the last look; the next look starts from here. */
    @Synchronized
    fun look(counters: Map<String, NodeCounter>, now: Long): Map<String, Delta> {
        val result = deltas(counters, now)
        last = counters
        lastAt = now
        own.clear()
        return result
    }

    private fun deltas(counters: Map<String, NodeCounter>, now: Long): Map<String, Delta> =
        counters.mapValues { (tag, c) ->
            val before = last[tag] ?: NodeCounter()
            // A core restarted under the same tags counts from zero again.
            fun diff(a: Long, b: Long) = if (a >= b) a - b else a
            Delta(
                down = (diff(c.down, before.down) - (own[tag] ?: 0L)).coerceAtLeast(0),
                up = diff(c.up, before.up),
                conns = diff(c.conns, before.conns),
                fails = diff(c.fails, before.fails),
                ms = (now - lastAt).coerceAtLeast(0),
            )
        }

    companion object {
        /** Less than this between two looks is not traffic (keep-alives, a DNS answer). */
        const val FLOW_BYTES = 32 * 1024L
        const val FAILS_TO_WORRY = 3L
        private val gson = Gson()
        private val type = object : TypeToken<Map<String, NodeCounter>>() {}.type

        fun parse(json: String?): Map<String, NodeCounter> =
            json?.let { runCatching { gson.fromJson<Map<String, NodeCounter>>(it, type) }.getOrNull() }.orEmpty()

        /**
         * Lets the core count bytes per outbound: the stats feature and the outbound flags of the
         * system policy (v2rayNG adds them only for the speed notification). Policy levels of the
         * config stay as they are.
         */
        fun enableStats(root: JsonObject) {
            if (root.get("stats")?.isJsonObject != true) root.add("stats", JsonObject())
            val policy = root.get("policy")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().also { root.add("policy", it) }
            val system = policy.get("system")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().also { policy.add("system", it) }
            system.addProperty("statsOutboundUplink", true)
            system.addProperty("statsOutboundDownlink", true)
        }

        fun enableStats(config: String): String {
            val root = runCatching { JsonParser.parseString(config).asJsonObject }.getOrNull() ?: return config
            enableStats(root)
            return root.toString()
        }
    }
}
