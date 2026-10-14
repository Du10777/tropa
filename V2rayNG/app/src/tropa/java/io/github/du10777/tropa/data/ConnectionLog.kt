package io.github.du10777.tropa.data

import android.content.Context
import com.google.gson.Gson
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import io.github.du10777.tropa.service.ProbeEvent
import java.io.File
import java.io.RandomAccessFile
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

/** How much goes into the connection log, like the verbosity of command line tools. */
enum class LogLevel(val title: Int, val hint: Int) {
    /** Failed checks and connection events. */
    ERRORS(R.string.tropa_log_level_errors, R.string.tropa_log_level_errors_hint),

    /** Plus successful checks; while nothing changes, a server is written once in 10 minutes. */
    NORMAL(R.string.tropa_log_level_normal, R.string.tropa_log_level_normal_hint),

    /** Every check of every server, network details and the server list. */
    VERBOSE(R.string.tropa_log_level_verbose, R.string.tropa_log_level_verbose_hint),

    /** Everything: each check address with its HTTP status, screen on and off, Xray core messages. */
    DEBUG(R.string.tropa_log_level_debug, R.string.tropa_log_level_debug_hint),
}

/**
 * Connection log: checks, reconnects and session events, one JSON line per event.
 * The VPN service process appends, the UI reads, copies, shares and clears it. It survives
 * disconnects and is trimmed to the size set on the log screen (oldest events go first).
 */
object ConnectionLog {
    private const val FILE = "tropa_connection_log.jsonl"
    private const val LOCK = "tropa_connection_log.lock"
    private const val LIMIT_KEY = "tropa_log_limit_kb"
    private const val LEVEL_KEY = "tropa_log_level"
    private const val DEFAULT_LIMIT_KB = 1024

    /** Size limits offered on the log screen, KB. */
    val LIMIT_CHOICES_KB = listOf(256, 512, 1024, 2048, 5120)

    private val gson = Gson()
    private val inProcess = Any()

    var limitKb: Int
        get() = MmkvManager.decodeSettingsInt(LIMIT_KEY, DEFAULT_LIMIT_KB)
        set(value) {
            MmkvManager.encodeSettings(LIMIT_KEY, value)
        }

    var level: LogLevel
        get() = MmkvManager.decodeSettingsString(LEVEL_KEY)?.let { name -> LogLevel.entries.firstOrNull { it.name == name } }
            ?: LogLevel.NORMAL
        set(value) {
            MmkvManager.encodeSettings(LEVEL_KEY, value.name)
        }

    /**
     * Whether a server check is written at [level]. [steady]: connected, and the check ended like
     * the previous one while the last written check of this server is recent.
     */
    fun writesCheck(level: LogLevel, ok: Boolean, steady: Boolean): Boolean = when (level) {
        LogLevel.ERRORS -> !ok && !steady
        LogLevel.NORMAL -> !steady
        LogLevel.VERBOSE, LogLevel.DEBUG -> true
    }

    private fun file(context: Context) = File(context.filesDir, FILE)

    /** Runs [block] holding both an in-process and a cross-process (file) lock. */
    private fun <T> locked(context: Context, block: (File) -> T): T = synchronized(inProcess) {
        RandomAccessFile(File(context.filesDir, LOCK), "rw").use { raf ->
            raf.channel.lock().use { block(file(context)) }
        }
    }

    fun append(context: Context, event: ProbeEvent) {
        runCatching {
            locked(context) { f ->
                f.appendText(gson.toJson(event) + "\n")
                val limit = limitKb * 1024L
                if (f.length() > limit) trim(f, limit * 3 / 4)
            }
        }
    }

    /** Applies a lowered limit right away instead of on the next entry. */
    fun trimToLimit(context: Context) {
        runCatching {
            locked(context) { f ->
                val limit = limitKb * 1024L
                if (f.exists() && f.length() > limit) trim(f, limit * 3 / 4)
            }
        }
    }

    private fun trim(f: File, keepBytes: Long) {
        val kept = keepNewest(f.readLines(), keepBytes)
        f.writeText(kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n"))
    }

    /** The newest lines that fit into [keepBytes] (each line counts with its newline). */
    internal fun keepNewest(lines: List<String>, keepBytes: Long): List<String> {
        var size = 0L
        val kept = ArrayDeque<String>()
        for (line in lines.asReversed()) {
            size += line.toByteArray().size + 1
            if (size > keepBytes) break
            kept.addFirst(line)
        }
        return kept
    }

    /** Events, oldest first. */
    fun read(context: Context): List<ProbeEvent> = runCatching {
        locked(context) { f ->
            if (!f.exists()) emptyList()
            else f.readLines().mapNotNull { line -> runCatching { gson.fromJson(line, ProbeEvent::class.java) }.getOrNull() }
        }
    }.getOrDefault(emptyList())

    /** The newest events that fit into [maxBytes] of the file, oldest first (for reports). */
    fun readTail(context: Context, maxBytes: Long): List<ProbeEvent> = runCatching {
        locked(context) { f ->
            if (!f.exists()) return@locked emptyList()
            RandomAccessFile(f, "r").use { raf ->
                val start = (raf.length() - maxBytes).coerceAtLeast(0)
                val bytes = ByteArray((raf.length() - start).toInt())
                raf.seek(start)
                raf.readFully(bytes)
                // A cut line at the start is dropped; the JSON parser would refuse it anyway.
                bytes.toString(Charsets.UTF_8).split('\n').drop(if (start > 0) 1 else 0)
                    .mapNotNull { line -> line.takeIf { it.isNotBlank() }?.let { runCatching { gson.fromJson(it, ProbeEvent::class.java) }.getOrNull() } }
            }
        }
    }.getOrDefault(emptyList())

    /** Sizes in the binary units the limits are set in: 47 KB, 1 MB, 1.5 MB. */
    fun sizeLabel(context: Context, bytes: Long): String {
        val kb = bytes / 1024.0
        return if (kb < 1024) context.getString(R.string.tropa_size_kb, kb.roundToLong().toString())
        else context.getString(R.string.tropa_size_mb, DecimalFormat("0.#").format(kb / 1024))
    }

    /** Size and modification time, to notice new entries without reading the file. */
    fun stamp(context: Context): Pair<Long, Long> = file(context).let { f ->
        if (f.exists()) f.length() to f.lastModified() else 0L to 0L
    }

    fun clear(context: Context) {
        runCatching { locked(context) { f -> f.writeText("") } }
    }

    /** Readable text of the whole log, oldest first, for copying and sharing. */
    fun text(context: Context, events: List<ProbeEvent> = read(context)): String {
        val time = SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT)
        return buildString {
            events.forEach { e ->
                append(time.format(Date(e.at))).append(' ').append(title(context, e))
                if (e.server.isNotEmpty()) append(" [").append(e.server).append(e.address?.let { " $it" } ?: "").append(']')
                if (e.target.isNotEmpty() && e.kind !in TARGET_IN_TITLE) append(' ').append(e.target)
                when {
                    e.kind in SESSION_KINDS || e.kind in NOTE_KINDS -> Unit
                    e.ok && e.kind == "download" -> append(" → OK ${e.latencyMs} ms, ${e.bytes} B")
                    e.ok -> append(" → OK ${e.latencyMs} ms")
                    else -> append(" → ").append(e.error ?: "error")
                }
                if (!e.detail.isNullOrEmpty()) append(" · ").append(e.detail)
                append('\n')
            }
        }
    }

    /** Events that mark sessions and changes rather than checks: shown as chips. */
    val SESSION_KINDS = setOf("start", "stop", "connected", "no_server", "no_internet", "reload", "switch", "network", "servers", "level")

    /** Small notes: shown as plain grey lines. */
    val NOTE_KINDS = setOf("screen_on", "screen_off", "core")

    /** Kinds whose target is part of the title (network type, log level). */
    private val TARGET_IN_TITLE = setOf("network", "level")

    /** The headline of an event: "Network: Wi-Fi", "Log level: Debug", "Latency". */
    fun title(context: Context, e: ProbeEvent): String = when (e.kind) {
        "network" -> context.getString(R.string.tropa_log_network, networkLabel(context, e.target))
        "level" -> context.getString(
            R.string.tropa_log_level_changed,
            LogLevel.entries.firstOrNull { it.name == e.target }?.let { context.getString(it.title) } ?: e.target,
        )
        else -> kindLabel(context, e.kind)
    }

    fun networkLabel(context: Context, type: String): String = context.getString(
        when (type) {
            "wifi" -> R.string.tropa_network_wifi
            "cellular" -> R.string.tropa_network_cellular
            "ethernet" -> R.string.tropa_network_ethernet
            "none" -> R.string.tropa_network_none
            else -> R.string.tropa_network_other
        }
    )

    fun kindLabel(context: Context, kind: String): String = context.getString(
        when (kind) {
            "latency" -> R.string.tropa_log_latency
            "download" -> R.string.tropa_log_download
            "direct" -> R.string.tropa_log_direct
            "start" -> R.string.tropa_log_start
            "stop" -> R.string.tropa_log_stop
            "connected" -> R.string.tropa_log_connected
            "no_server" -> R.string.tropa_log_no_server
            "no_internet" -> R.string.tropa_log_no_internet
            "switch" -> R.string.tropa_log_switch
            "servers" -> R.string.tropa_log_servers
            "screen_on" -> R.string.tropa_log_screen_on
            "screen_off" -> R.string.tropa_log_screen_off
            "core" -> R.string.tropa_log_core
            else -> R.string.tropa_log_reload
        }
    )
}
