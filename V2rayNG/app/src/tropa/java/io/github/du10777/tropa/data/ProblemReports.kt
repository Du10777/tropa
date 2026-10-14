package io.github.du10777.tropa.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.google.gson.GsonBuilder
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import io.github.du10777.tropa.service.WatchState
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Connection problem reports. While Tropa cannot connect, the watchdog saves what it tried and how
 * it failed; once the connection is back the reports go to the server (address from the
 * `Tropa-Report-Url` subscription header). They can also be shared by hand from the details screen.
 */
object ProblemReports {
    private const val TAG = "TropaReports"
    private const val DIR = "tropa_reports"
    private const val MAX_FILES = 20
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    }

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }

    /** Everything a report holds, without secrets of the subscription itself. */
    fun build(context: Context, state: WatchState?, withLog: Boolean = true): Map<String, Any?> {
        val connectionId = Connections.activeId()
        val connection = MmkvManager.decodeSubscriptions().firstOrNull { it.guid == connectionId }?.subscription
        return linkedMapOf(
            "createdAt" to System.currentTimeMillis(),
            "app" to "Tropa ${BuildConfig.VERSION_NAME} (v2rayNG ${BuildConfig.UPSTREAM_VERSION_NAME})",
            "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "network" to networkType(context),
            "connection" to connection?.remarks,
            "subscriptionHost" to connection?.url?.let { runCatching { java.net.URI(it).host }.getOrNull() },
            "checks" to mapOf(
                "latencyUrls" to CheckSettings.latencyUrls,
                "throughputUrls" to CheckSettings.throughputUrls,
                "directUrls" to CheckSettings.directUrls,
            ),
            "state" to state,
            "log" to if (withLog) ConnectionLog.read(context).takeLast(400) else null,
        )
    }

    fun save(context: Context, state: WatchState): Boolean = try {
        val files = dir(context).listFiles()?.sortedBy { it.name }.orEmpty()
        files.dropLast(MAX_FILES - 1).forEach { it.delete() }
        File(dir(context), "report-${System.currentTimeMillis()}.json").writeText(gson.toJson(build(context, state)))
        true
    } catch (e: Exception) {
        LogUtil.e(TAG, "Failed to save a problem report", e)
        false
    }

    fun hasPending(context: Context): Boolean = dir(context).listFiles()?.isNotEmpty() == true

    /** Sends saved reports to the server of the active connection; sent ones are deleted. */
    fun uploadPending(context: Context) {
        val url = Connections.activeId()?.let(TropaPrefs::reportUrl) ?: return
        dir(context).listFiles()?.sortedBy { it.name }?.forEach { file ->
            val ok = runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", SubscriptionFetcher.userAgent)
                    .post(file.readText().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (!ok) return
            file.delete()
        }
    }

    /** Human-readable report for sharing in a messenger. */
    fun shareText(context: Context, state: WatchState?): String {
        val time = SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT)
        val info = build(context, null, withLog = false)
        return buildString {
            appendLine("Tropa report ${time.format(Date())}")
            listOf("app", "android", "device", "network", "connection", "subscriptionHost").forEach { key ->
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

    private fun networkType(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "unknown"
        val caps = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
        return buildList {
            if (caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }) add("wifi")
            if (caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) }) add("cellular")
            if (caps.any { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }) add("vpn")
        }.joinToString("+").ifEmpty { "none" }
    }
}
