package io.github.du10777.tropa.data

import android.content.Context
import com.google.gson.Gson
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager
import io.github.du10777.tropa.service.ProbeEvent
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Connection log: every check, reconnect and session start/stop, one JSON line per event.
 * The VPN service process appends, the UI reads, copies, shares and clears it. It survives
 * disconnects and is trimmed to the size set in the settings (oldest events go first).
 */
object ConnectionLog {
    private const val FILE = "tropa_connection_log.jsonl"
    private const val LOCK = "tropa_connection_log.lock"
    private const val LIMIT_KEY = "tropa_log_limit_kb"
    private const val DEFAULT_LIMIT_KB = 1024

    /** Size limits offered in the settings, KB. */
    val LIMIT_CHOICES_KB = listOf(256, 512, 1024, 2048, 5120)

    private val gson = Gson()
    private val inProcess = Any()

    var limitKb: Int
        get() = MmkvManager.decodeSettingsInt(LIMIT_KEY, DEFAULT_LIMIT_KB)
        set(value) {
            MmkvManager.encodeSettings(LIMIT_KEY, value)
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
                append(time.format(Date(e.at))).append(' ').append(kindLabel(context, e.kind))
                if (e.server.isNotEmpty()) append(" [").append(e.server).append(e.address?.let { " $it" } ?: "").append(']')
                if (e.target.isNotEmpty()) append(' ').append(e.target)
                when {
                    e.kind in SESSION_KINDS -> Unit
                    e.ok && e.kind == "download" -> append(" → OK ${e.latencyMs} ms, ${e.bytes} B")
                    e.ok -> append(" → OK ${e.latencyMs} ms")
                    else -> append(" → ").append(e.error ?: "error")
                }
                append('\n')
            }
        }
    }

    /** Events that mark sessions rather than checks. */
    val SESSION_KINDS = setOf("start", "stop", "connected", "no_server", "no_internet", "reload")

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
            else -> R.string.tropa_log_reload
        }
    )
}
