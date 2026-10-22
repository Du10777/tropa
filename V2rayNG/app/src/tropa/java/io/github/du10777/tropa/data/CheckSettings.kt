package io.github.du10777.tropa.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.handler.MmkvManager
import java.net.InetAddress
import java.net.URI
import java.util.Locale
import kotlin.math.roundToInt

/** The connection check parameters the watchdog runs with, every one of them set. */
data class CheckValues(
    val latencyUrls: List<String>,
    val downloadUrls: List<String>,
    val directUrls: List<String>,
    val activeIntervalSec: Int,
    val othersIntervalSec: Int,
    val downloadIntervalSec: Int,
    val screenOffIntervalSec: Int,
    val latencyTimeoutSec: Int,
    val downloadTimeoutSec: Int,
    val downloadMinKb: Int,
    val failThreshold: Int,
    val betterPercent: Int,
    val betterMinMs: Int,
    val betterRounds: Int,
) {
    val downloadMinBytes: Long get() = downloadMinKb * 1024L

    /** "Noticeably better": a median latency at most this share of the active server's one. */
    val betterRatio: Double get() = 1 - betterPercent / 100.0
}

/** Where a check parameter comes from, for the settings screen. */
enum class CheckSource { DEFAULT, SERVER, USER }

/**
 * One layer of check parameters: the user's settings, or the server's (the `Tropa-Checks` header of
 * the subscription). A field that is not set comes from the layer below; Tropa's defaults are the bottom.
 */
data class CheckParams(
    val latencyUrls: List<String>? = null,
    val downloadUrls: List<String>? = null,
    val directUrls: List<String>? = null,
    val activeIntervalSec: Int? = null,
    val othersIntervalSec: Int? = null,
    val downloadIntervalSec: Int? = null,
    val screenOffIntervalSec: Int? = null,
    val latencyTimeoutSec: Int? = null,
    val downloadTimeoutSec: Int? = null,
    val downloadMinKb: Int? = null,
    val failThreshold: Int? = null,
    val betterPercent: Int? = null,
    val betterMinMs: Int? = null,
    val betterRounds: Int? = null,
) {
    val isEmpty: Boolean get() = this == CheckParams()

    /** This layer on top of [below]: the fields set here win. */
    fun over(below: CheckValues) = CheckValues(
        latencyUrls = latencyUrls ?: below.latencyUrls,
        downloadUrls = downloadUrls ?: below.downloadUrls,
        directUrls = directUrls ?: below.directUrls,
        activeIntervalSec = activeIntervalSec ?: below.activeIntervalSec,
        othersIntervalSec = othersIntervalSec ?: below.othersIntervalSec,
        downloadIntervalSec = downloadIntervalSec ?: below.downloadIntervalSec,
        screenOffIntervalSec = screenOffIntervalSec ?: below.screenOffIntervalSec,
        latencyTimeoutSec = latencyTimeoutSec ?: below.latencyTimeoutSec,
        downloadTimeoutSec = downloadTimeoutSec ?: below.downloadTimeoutSec,
        downloadMinKb = downloadMinKb ?: below.downloadMinKb,
        failThreshold = failThreshold ?: below.failThreshold,
        betterPercent = betterPercent ?: below.betterPercent,
        betterMinMs = betterMinMs ?: below.betterMinMs,
        betterRounds = betterRounds ?: below.betterRounds,
    )

    fun toJson(): String = gson.toJson(this)

    /** For the log: the keys this layer sets, lists by their size ("latencyUrls 2, activeIntervalSec 60"). */
    fun describe(): String = JsonParser.parseString(toJson()).asJsonObject.entrySet().joinToString(", ") { (key, value) ->
        if (value.isJsonArray) "$key ${value.asJsonArray.size()}" else "$key $value"
    }

    companion object {
        private val gson = Gson()

        /** Addresses kept per list from a server. */
        const val MAX_URLS = 5
        private const val MAX_URL_LENGTH = 2048

        /**
         * The range of every number; a server value outside is moved into it. The lower ends keep a
         * server from making the checks heavy on traffic or battery.
         */
        val RANGES: Map<String, IntRange> = mapOf(
            "activeIntervalSec" to 10..600,
            "othersIntervalSec" to 30..3600,
            "downloadIntervalSec" to 300..86400,
            "screenOffIntervalSec" to 60..3600,
            "latencyTimeoutSec" to 2..30,
            "downloadTimeoutSec" to 5..60,
            "downloadMinKb" to 16..512,
            "failThreshold" to 1..10,
            "betterPercent" to 0..90,
            "betterMinMs" to 0..5000,
            "betterRounds" to 1..20,
        )

        /** Private network names: the direct check runs on the user's own network. */
        private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".internal", ".localdomain", ".home.arpa", ".localhost")

        /**
         * Reads the `Tropa-Checks` header: a JSON object, plain or as `base64:<JSON>`. Unknown keys
         * are ignored, numbers moved into [RANGES], addresses other than public http(s) ones dropped,
         * an empty list counts as not set. Null when nothing usable is there.
         */
        fun parse(raw: String?): CheckParams? {
            val json = jsonObject(raw) ?: return null
            return CheckParams(
                latencyUrls = urls(json, "latencyUrls"),
                downloadUrls = urls(json, "downloadUrls"),
                directUrls = urls(json, "directUrls"),
                activeIntervalSec = number(json, "activeIntervalSec"),
                othersIntervalSec = number(json, "othersIntervalSec"),
                downloadIntervalSec = number(json, "downloadIntervalSec"),
                screenOffIntervalSec = number(json, "screenOffIntervalSec"),
                latencyTimeoutSec = number(json, "latencyTimeoutSec"),
                downloadTimeoutSec = number(json, "downloadTimeoutSec"),
                downloadMinKb = number(json, "downloadMinKb"),
                failThreshold = number(json, "failThreshold"),
                betterPercent = number(json, "betterPercent"),
                betterMinMs = number(json, "betterMinMs"),
                betterRounds = number(json, "betterRounds"),
            ).takeIf { !it.isEmpty }
        }

        private fun jsonObject(raw: String?): JsonObject? {
            var text = raw?.trim().orEmpty()
            if (text.startsWith("base64:", ignoreCase = true)) {
                text = Base64Codec.decode(text.substring(7).trim())?.toString(Charsets.UTF_8)?.trim().orEmpty()
            }
            if (!text.startsWith("{")) return null
            return runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        }

        private fun number(json: JsonObject, key: String): Int? {
            val range = RANGES.getValue(key)
            val element = json.get(key)?.takeIf { it.isJsonPrimitive && !it.asJsonPrimitive.isBoolean } ?: return null
            val value = element.asString.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
            return value.coerceIn(range.first.toDouble(), range.last.toDouble()).roundToInt()
        }

        private fun urls(json: JsonObject, key: String): List<String>? {
            val array = json.get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
            return array.mapNotNull { element -> element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim() }
                .filter(::isPublicUrl)
                .distinct()
                .take(MAX_URLS)
                .takeIf { it.isNotEmpty() }
        }

        /** An http(s) address of a host on the internet: no local names, no private or loopback IPs. */
        fun isPublicUrl(url: String): Boolean {
            if (url.length > MAX_URL_LENGTH || url.any { it.isWhitespace() }) return false
            if (!url.startsWith("https://") && !url.startsWith("http://")) return false
            val host = runCatching { URI(url).host }.getOrNull()?.lowercase(Locale.ROOT)?.trimEnd('.')?.removeSurrounding("[", "]")
            if (host.isNullOrEmpty()) return false
            if (':' in host || host.all { it.isDigit() || it == '.' }) return isPublicIp(host)
            return '.' in host && LOCAL_SUFFIXES.none { host.endsWith(it) }
        }

        /** An IP literal (parsed without a DNS lookup) outside the private, loopback, link-local and reserved ranges. */
        private fun isPublicIp(literal: String): Boolean {
            if (':' !in literal && !Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(literal)) return false
            val address = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return false
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress
            ) return false
            val b = address.address.map { it.toInt() and 0xff }
            return if (b.size == 4) {
                // 0/8, CGNAT 100.64/10, 192.0.0/24, benchmarking 198.18/15, 240/4 and broadcast.
                !(b[0] == 0 || (b[0] == 100 && b[1] in 64..127) || (b[0] == 192 && b[1] == 0 && b[2] == 0) ||
                    (b[0] == 198 && b[1] in 18..19) || b[0] >= 240)
            } else {
                // Unique local fc00::/7.
                (b[0] and 0xfe) != 0xfc
            }
        }
    }
}

/**
 * Connection check settings (Settings → Connection check), shared by the UI and the service.
 * Three layers: the user's own values on top, then the parameters the server of the active
 * connection sends (`Tropa-Checks`), then Tropa's defaults.
 */
object CheckSettings {
    val DEFAULTS = CheckValues(
        latencyUrls = listOf(
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
            "https://captive.apple.com/hotspot-detect.html",
        ),
        downloadUrls = listOf(
            "https://speed.cloudflare.com/__down?bytes=65536",
            "https://cdn.jsdelivr.net/npm/jquery@3.7.1/dist/jquery.min.js",
        ),
        directUrls = listOf(
            "https://ya.ru",
            "https://vk.com",
            "https://www.gosuslugi.ru",
            "https://mail.ru",
        ),
        activeIntervalSec = 30,
        othersIntervalSec = 180,
        downloadIntervalSec = 900,
        screenOffIntervalSec = 300,
        latencyTimeoutSec = 6,
        downloadTimeoutSec = 15,
        downloadMinKb = 64,
        failThreshold = 2,
        betterPercent = 15,
        betterMinMs = 15,
        betterRounds = 3,
    )

    /** Intervals offered for the active server check, seconds. */
    val ACTIVE_INTERVAL_CHOICES = listOf(15, 30, 60, 120)

    private const val ENABLED = "tropa_check_enabled"
    private const val ACTIVE_INTERVAL = "tropa_check_active_interval"
    private const val LATENCY_URLS = "tropa_check_latency_urls"
    private const val THROUGHPUT_URLS = "tropa_check_throughput_urls"
    private const val DIRECT_URLS = "tropa_check_direct_urls"
    private const val USER_LAYER = "tropa_check_user_layer"
    private const val KILL_SWITCH = "tropa_kill_switch"
    private const val CHECK_UPLOAD = "tropa_check_upload"

    var enabled: Boolean
        get() = MmkvManager.decodeSettingsBool(ENABLED, true)
        set(value) {
            MmkvManager.encodeSettings(ENABLED, value)
        }

    /**
     * "Check the upload speed too": off by default. The download check is usually enough; turning
     * this on doubles the test traffic to measure the upstream of the first hop as well.
     */
    var checkUpload: Boolean
        get() = MmkvManager.decodeSettingsBool(CHECK_UPLOAD, false)
        set(value) {
            MmkvManager.encodeSettings(CHECK_UPLOAD, value)
        }

    /** What the checks run with: the user's values, then the server's of the connection, then the defaults. */
    fun current(connectionId: String? = Connections.activeId()): CheckValues = user().over(base(connectionId))

    /** The server's parameters of a connection over the defaults: what the user's values change. */
    fun base(connectionId: String?): CheckValues = (server(connectionId) ?: CheckParams()).over(DEFAULTS)

    /** What the server of a connection sent (`Tropa-Checks`), null when it sent nothing. */
    fun server(connectionId: String?): CheckParams? = connectionId?.let { CheckParams.parse(TropaPrefs.serverChecks(it)) }

    /** The user's own values from the settings screen; the ones not set follow the server and the defaults. */
    fun user(): CheckParams {
        migrateUserLayer()
        return CheckParams(
            latencyUrls = readList(LATENCY_URLS),
            downloadUrls = readList(THROUGHPUT_URLS),
            directUrls = readList(DIRECT_URLS),
            activeIntervalSec = MmkvManager.decodeSettingsInt(ACTIVE_INTERVAL, 0).takeIf { it > 0 },
        )
    }

    fun source(user: Any?, server: Any?): CheckSource = when {
        user != null -> CheckSource.USER
        server != null -> CheckSource.SERVER
        else -> CheckSource.DEFAULT
    }

    /** The interval the user picked; the one of [base] is not kept, so later server changes apply. */
    fun setActiveInterval(seconds: Int, base: CheckValues) {
        MmkvManager.encodeSettings(ACTIVE_INTERVAL, seconds.takeIf { it != base.activeIntervalSec } ?: 0)
    }

    /** The user's address lists; a list equal to the one of [base] or an empty one follows [base]. */
    fun setUrls(latency: List<String>, download: List<String>, direct: List<String>, base: CheckValues) {
        writeList(LATENCY_URLS, latency.takeIf { it.isNotEmpty() && it != base.latencyUrls })
        writeList(THROUGHPUT_URLS, download.takeIf { it.isNotEmpty() && it != base.downloadUrls })
        writeList(DIRECT_URLS, direct.takeIf { it.isNotEmpty() && it != base.directUrls })
    }

    /** Forgets the user's values: the server's or the default ones apply again. */
    fun resetUser() {
        MmkvManager.encodeSettings(ACTIVE_INTERVAL, 0)
        listOf(LATENCY_URLS, THROUGHPUT_URLS, DIRECT_URLS).forEach { writeList(it, null) }
    }

    /**
     * Up to 0.11 the screen stored whatever it saved, defaults too, and there was no server layer.
     * A stored default would now hide the server's value, so it becomes "not set".
     */
    private fun migrateUserLayer() {
        if (MmkvManager.decodeSettingsBool(USER_LAYER, false)) return
        if (MmkvManager.decodeSettingsInt(ACTIVE_INTERVAL, 0) == DEFAULTS.activeIntervalSec) MmkvManager.encodeSettings(ACTIVE_INTERVAL, 0)
        listOf(
            LATENCY_URLS to DEFAULTS.latencyUrls,
            THROUGHPUT_URLS to DEFAULTS.downloadUrls,
            DIRECT_URLS to DEFAULTS.directUrls,
        ).forEach { (key, default) -> if (readList(key) == default) writeList(key, null) }
        MmkvManager.encodeSettings(USER_LAYER, true)
    }

    /** "Block traffic without VPN": Tropa only guides to the system setting and shows its state. */
    var killSwitch: Boolean
        get() = MmkvManager.decodeSettingsBool(KILL_SWITCH, false)
        set(value) {
            MmkvManager.encodeSettings(KILL_SWITCH, value)
        }

    /** Splits user input into addresses: one per line (commas and spaces work too). */
    fun parseUrls(text: String): List<String> =
        text.split('\n', ',', ' ', '\t').map { it.trim() }.filter { it.startsWith("http://") || it.startsWith("https://") }.distinct()

    private fun readList(key: String): List<String>? =
        MmkvManager.decodeSettingsString(key)?.let(::parseUrls)?.takeIf { it.isNotEmpty() }

    private fun writeList(key: String, value: List<String>?) {
        MmkvManager.encodeSettings(key, value.orEmpty().joinToString("\n"))
    }
}
