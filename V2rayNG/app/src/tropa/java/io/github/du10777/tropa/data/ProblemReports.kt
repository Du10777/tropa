package io.github.du10777.tropa.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.google.gson.Gson
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import io.github.du10777.tropa.service.ProbeEvent
import io.github.du10777.tropa.service.WatchState
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * Connection problem reports. While Tropa cannot connect, the watchdog saves what it tried and how
 * it failed; once the connection is back the reports go to the server (address from the
 * `Tropa-Report-Url` subscription header). The log screen also sends the whole log by hand.
 * Reports travel gzip'ed: the log shrinks about tenfold.
 */
object ProblemReports {
    private const val TAG = "TropaReports"
    private const val DIR = "tropa_reports"
    private const val MAX_FILES = 20
    private const val FORMAT = 1

    /** Automatic reports carry the newest part of the log: the outage and what led to it. */
    private const val AUTO_LOG_BYTES = 1L * 1024 * 1024

    /** Reports sent by hand carry the whole log, up to its largest size (5 MB). */
    private const val MANUAL_LOG_BYTES = 6L * 1024 * 1024

    /** A connection without the header is asked again at most this often. */
    private const val DISCOVER_EVERY_MS = 60 * 60_000L

    private val gson = Gson()
    private val discovered = ConcurrentHashMap<String, Long>()

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    }

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }

    /** Everything a report holds, without secrets of the subscription itself. */
    fun build(context: Context, state: WatchState?, reason: String = "auto", log: List<ProbeEvent>? = null): Map<String, Any?> {
        val connectionId = Connections.activeId()
        val connection = MmkvManager.decodeSubscriptions().firstOrNull { it.guid == connectionId }?.subscription
        return linkedMapOf(
            "format" to FORMAT,
            "reason" to reason,
            "createdAt" to System.currentTimeMillis(),
            "app" to "Tropa ${BuildConfig.VERSION_NAME} (v2rayNG ${BuildConfig.UPSTREAM_VERSION_NAME})",
            "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "network" to networkType(context),
            "connection" to connection?.remarks,
            "subscriptionHost" to connection?.url?.let { runCatching { java.net.URI(it).host }.getOrNull() },
            "logLevel" to ConnectionLog.level.name,
            "checks" to mapOf(
                "latencyUrls" to CheckSettings.latencyUrls,
                "throughputUrls" to CheckSettings.throughputUrls,
                "directUrls" to CheckSettings.directUrls,
            ),
            "state" to state,
            "log" to log,
        )
    }

    /**
     * The log for a report: the newest entries up to [maxBytes]. Access lines of the Xray core
     * ("accepted tcp:site:443", written at the debug level) name the sites visited; they stay on
     * the phone and add nothing to finding connection problems.
     */
    private fun reportLog(context: Context, maxBytes: Long): List<ProbeEvent> =
        ConnectionLog.readTail(context, maxBytes).filterNot { it.kind == "core" && it.detail?.contains(" accepted ") == true }

    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }.toByteArray()

    /** Saves an automatic report; it goes to the server when the connection is back. */
    fun save(context: Context, state: WatchState): Boolean = try {
        val report = build(context, state, "auto", reportLog(context, AUTO_LOG_BYTES))
        keep(context, gzip(gson.toJson(report)))
        true
    } catch (e: Exception) {
        LogUtil.e(TAG, "Failed to save a problem report", e)
        false
    }

    private fun keep(context: Context, body: ByteArray) {
        val files = dir(context).listFiles()?.sortedBy { it.name }.orEmpty()
        files.dropLast(MAX_FILES - 1).forEach { it.delete() }
        File(dir(context), "report-${System.currentTimeMillis()}.json.gz").writeBytes(body)
    }

    fun hasPending(context: Context): Boolean = dir(context).listFiles()?.isNotEmpty() == true

    sealed interface SendResult {
        data object Sent : SendResult
        data object NoServer : SendResult
        data class Failed(val reason: String) : SendResult
    }

    /** Sends the whole log to the server now (the button on the log screen). Off the main thread. */
    fun sendNow(context: Context, state: WatchState?): SendResult {
        val url = reportUrl(context, force = true) ?: return SendResult.NoServer
        val body = gzip(gson.toJson(build(context, state, "manual", reportLog(context, MANUAL_LOG_BYTES))))
        val error = post(url, body)
        if (error == null) return SendResult.Sent
        if (error.retry) keep(context, body)
        return SendResult.Failed(error.message)
    }

    /** Sends saved reports to the server of the active connection; sent ones are deleted. One at a time. */
    @Synchronized
    fun uploadPending(context: Context) {
        val files = dir(context).listFiles()?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return
        val url = reportUrl(context, force = false) ?: return
        files.forEach { file ->
            val body = if (file.name.endsWith(".gz")) file.readBytes() else gzip(file.readText())
            val error = post(url, body)
            if (error?.retry == true) return
            // Sent, or refused for good (unknown client, too big): either way it is done.
            file.delete()
        }
    }

    private class PostError(val message: String, val retry: Boolean)

    private fun post(url: String, gzipped: ByteArray): PostError? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", SubscriptionFetcher.userAgent)
            .header("Content-Encoding", "gzip")
            .post(gzipped.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> null
                response.code == 408 || response.code == 429 || response.code >= 500 -> PostError("HTTP ${response.code}", retry = true)
                else -> PostError("HTTP ${response.code}", retry = false)
            }
        }
    }.getOrElse { PostError(it.message ?: it.javaClass.simpleName, retry = true) }

    /**
     * The report address of the active connection. Connections added before their server started
     * sending the header learn it from a fresh download of the subscription (at most hourly,
     * unless [force]d by the user).
     */
    private fun reportUrl(context: Context, force: Boolean): String? {
        val id = Connections.activeId() ?: return null
        TropaPrefs.reportUrl(id)?.let { return it }
        val subscription = MmkvManager.decodeSubscriptions().firstOrNull { it.guid == id }?.subscription?.url
            ?.takeIf { it.startsWith("http") } ?: return null
        val last = discovered[id] ?: 0L
        if (!force && System.currentTimeMillis() - last < DISCOVER_EVERY_MS) return null
        discovered[id] = System.currentTimeMillis()
        val header = runCatching { SubscriptionFetcher.fetch(subscription).headers["tropa-report-url"] }
            .onFailure { LogUtil.e(TAG, "Cannot ask the subscription for the report address", it) }
            .getOrNull() ?: return null
        TropaPrefs.setReportUrl(id, header)
        return TropaPrefs.reportUrl(id)
    }

    /** Human-readable report for sharing in a messenger. */
    fun shareText(context: Context, state: WatchState?): String {
        val time = SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT)
        val info = build(context, null)
        return buildString {
            appendLine("Tropa report ${time.format(Date())}")
            listOf("app", "android", "device", "network", "connection", "subscriptionHost", "logLevel").forEach { key ->
                appendLine("$key: ${info[key]}")
            }
            if (state != null) {
                appendLine("phase: ${state.phase}, attempt ${state.attempt}, active: ${state.activeName}")
                state.nodes.forEach { appendLine("node ${it.name}: alive=${it.alive} latency=${it.latencyMs} active=${it.active}") }
            }
            appendLine("--- connection log (oldest first)")
            append(ConnectionLog.text(context))
        }
    }

    @Suppress("DEPRECATION")
    private fun networkType(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "unknown"
        val caps = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
        return buildList {
            if (caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }) add("wifi")
            if (caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) }) add("cellular")
            if (caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) }) add("ethernet")
            if (caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }) add("vpn")
        }.joinToString("+").ifEmpty { "none" }
    }
}
