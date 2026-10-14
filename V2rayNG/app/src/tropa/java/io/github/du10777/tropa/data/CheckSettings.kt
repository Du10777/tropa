package io.github.du10777.tropa.data

import com.v2ray.ang.handler.MmkvManager

/** Connection check settings (Settings → Connection check), shared by the UI and the service. */
object CheckSettings {
    val DEFAULT_LATENCY_URLS = listOf(
        "https://www.gstatic.com/generate_204",
        "https://cp.cloudflare.com/generate_204",
        "https://captive.apple.com/hotspot-detect.html",
    )
    val DEFAULT_THROUGHPUT_URLS = listOf(
        "https://speed.cloudflare.com/__down?bytes=65536",
        "https://cdn.jsdelivr.net/npm/jquery@3.7.1/dist/jquery.min.js",
    )
    val DEFAULT_DIRECT_URLS = listOf(
        "https://ya.ru",
        "https://vk.com",
        "https://www.gosuslugi.ru",
        "https://mail.ru",
    )

    /** Intervals offered for the active server check, seconds. */
    val ACTIVE_INTERVAL_CHOICES = listOf(15, 30, 60, 120)

    const val THROUGHPUT_MIN_BYTES = 64L * 1024
    const val FAIL_THRESHOLD = 2
    const val BETTER_RATIO = 0.7
    const val BETTER_MIN_MS = 50L
    const val BETTER_ROUNDS = 3
    const val CONNECT_TIMEOUT_MS = 15_000L

    private const val ENABLED = "tropa_check_enabled"
    private const val ACTIVE_INTERVAL = "tropa_check_active_interval"
    private const val OTHERS_INTERVAL = "tropa_check_others_interval"
    private const val THROUGHPUT_INTERVAL = "tropa_check_throughput_interval"
    private const val SCREEN_OFF_INTERVAL = "tropa_check_screen_off_interval"
    private const val LATENCY_URLS = "tropa_check_latency_urls"
    private const val THROUGHPUT_URLS = "tropa_check_throughput_urls"
    private const val DIRECT_URLS = "tropa_check_direct_urls"
    private const val KILL_SWITCH = "tropa_kill_switch"

    var enabled: Boolean
        get() = MmkvManager.decodeSettingsBool(ENABLED, true)
        set(value) {
            MmkvManager.encodeSettings(ENABLED, value)
        }

    var activeIntervalSec: Int
        get() = MmkvManager.decodeSettingsInt(ACTIVE_INTERVAL, 30)
        set(value) {
            MmkvManager.encodeSettings(ACTIVE_INTERVAL, value)
        }

    val othersIntervalSec: Int get() = MmkvManager.decodeSettingsInt(OTHERS_INTERVAL, 180)
    val throughputIntervalSec: Int get() = MmkvManager.decodeSettingsInt(THROUGHPUT_INTERVAL, 900)
    val screenOffIntervalSec: Int get() = MmkvManager.decodeSettingsInt(SCREEN_OFF_INTERVAL, 300)

    var latencyUrls: List<String>
        get() = readList(LATENCY_URLS, DEFAULT_LATENCY_URLS)
        set(value) = writeList(LATENCY_URLS, value)

    var throughputUrls: List<String>
        get() = readList(THROUGHPUT_URLS, DEFAULT_THROUGHPUT_URLS)
        set(value) = writeList(THROUGHPUT_URLS, value)

    var directUrls: List<String>
        get() = readList(DIRECT_URLS, DEFAULT_DIRECT_URLS)
        set(value) = writeList(DIRECT_URLS, value)

    /** "Block traffic without VPN": Tropa only guides to the system setting and shows its state. */
    var killSwitch: Boolean
        get() = MmkvManager.decodeSettingsBool(KILL_SWITCH, false)
        set(value) {
            MmkvManager.encodeSettings(KILL_SWITCH, value)
        }

    fun resetUrls() {
        writeList(LATENCY_URLS, emptyList())
        writeList(THROUGHPUT_URLS, emptyList())
        writeList(DIRECT_URLS, emptyList())
    }

    /** Splits user input into addresses: one per line (commas and spaces work too). */
    fun parseUrls(text: String): List<String> =
        text.split('\n', ',', ' ', '\t').map { it.trim() }.filter { it.startsWith("http://") || it.startsWith("https://") }.distinct()

    private fun readList(key: String, default: List<String>): List<String> =
        MmkvManager.decodeSettingsString(key)?.let(::parseUrls)?.takeIf { it.isNotEmpty() } ?: default

    private fun writeList(key: String, value: List<String>) {
        MmkvManager.encodeSettings(key, value.joinToString("\n"))
    }
}
