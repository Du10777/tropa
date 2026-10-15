package io.github.du10777.tropa.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.handler.MmkvManager
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Updates of Tropa itself from the releases of its GitHub repository. Looked for once a day: by
 * the watchdog while the VPN is on (with a notification) and when the main screen opens; offered
 * with a dialog. The APK for this phone's processor is downloaded directly, or through the tunnel
 * when that fails, checked against the size and SHA-256 GitHub publishes for it, and handed to
 * the system installer.
 */
object AppUpdates {
    const val RELEASES_PAGE = "https://github.com/Du10777/tropa/releases"
    private const val LATEST_API = "https://api.github.com/repos/Du10777/tropa/releases/latest"
    private const val CHECK_EVERY_MS = 24 * 3600_000L
    private const val POSTPONE_MS = 24 * 3600_000L

    private const val ENABLED_KEY = "tropa_update_check"
    private const val CHECKED_AT_KEY = "tropa_update_checked_at"
    private const val LATEST_KEY = "tropa_update_latest"
    private const val NOTIFIED_KEY = "tropa_update_notified"
    private const val POSTPONED_VERSION_KEY = "tropa_update_postponed_version"
    private const val POSTPONED_AT_KEY = "tropa_update_postponed_at"
    private const val RESUME_VPN_KEY = "tropa_update_resume_vpn"

    data class Asset(val name: String, val url: String, val size: Long, val sha256: String?)

    data class Release(val version: String, val page: String, val apk: Asset?)

    private val gson = Gson()

    /** Settings → "Check for updates", on by default. */
    var enabled: Boolean
        get() = MmkvManager.decodeSettingsBool(ENABLED_KEY, true)
        set(value) {
            MmkvManager.encodeSettings(ENABLED_KEY, value)
        }

    val checkedAt: Long get() = MmkvManager.decodeSettingsLong(CHECKED_AT_KEY, 0L)

    /**
     * The version being installed while the VPN was on: once the app runs as that version, it turns
     * the VPN back on (TropaUpdateReceiver). Empty when nothing is pending.
     */
    var resumeVpnFor: String
        get() = MmkvManager.decodeSettingsString(RESUME_VPN_KEY).orEmpty()
        set(value) {
            MmkvManager.encodeSettings(RESUME_VPN_KEY, value)
        }

    fun isDue(now: Long = System.currentTimeMillis()): Boolean = enabled && now - checkedAt >= CHECK_EVERY_MS

    /** The newest release found by the last check, if it is newer than this app. */
    fun available(): Release? = latest()?.takeIf { isNewer(it.version, BuildConfig.VERSION_NAME) }

    private fun latest(): Release? =
        MmkvManager.decodeSettingsString(LATEST_KEY)?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { gson.fromJson(it, Release::class.java) }.getOrNull() }

    /**
     * Asks GitHub for the newest release. Blocking. Success: the release if it is newer than this
     * app, null if this app is up to date. Failure: GitHub could not be asked.
     */
    fun check(): Result<Release?> = runCatching {
        val json = fetchText(LATEST_API) ?: throw IOException("GitHub does not answer")
        val release = parse(json, Build.SUPPORTED_ABIS.toList()) ?: throw IOException("unexpected answer")
        MmkvManager.encodeSettings(LATEST_KEY, gson.toJson(release))
        MmkvManager.encodeSettings(CHECKED_AT_KEY, System.currentTimeMillis())
        release.takeIf { isNewer(it.version, BuildConfig.VERSION_NAME) }
    }

    /** "Not now" in the dialog: the same version is offered again in a day. */
    fun postpone(release: Release) {
        MmkvManager.encodeSettings(POSTPONED_VERSION_KEY, release.version)
        MmkvManager.encodeSettings(POSTPONED_AT_KEY, System.currentTimeMillis())
    }

    /** A check asked for by hand shows the dialog even if it was postponed. */
    fun clearPostponed() {
        MmkvManager.encodeSettings(POSTPONED_VERSION_KEY, "")
    }

    fun shouldOffer(release: Release, now: Long = System.currentTimeMillis()): Boolean =
        MmkvManager.decodeSettingsString(POSTPONED_VERSION_KEY) != release.version ||
            now - MmkvManager.decodeSettingsLong(POSTPONED_AT_KEY, 0L) >= POSTPONE_MS

    /** The notification about a version is shown once. */
    fun takeNotification(release: Release): Boolean {
        if (MmkvManager.decodeSettingsString(NOTIFIED_KEY) == release.version) return false
        MmkvManager.encodeSettings(NOTIFIED_KEY, release.version)
        return true
    }

    /** The release of the GitHub API answer, with the APK for [abis] (most preferred first). */
    internal fun parse(json: String, abis: List<String>): Release? {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
        if (root.bool("draft") || root.bool("prerelease")) return null
        val version = root.str("tag_name")?.removePrefix("v")?.takeIf { it.isNotEmpty() } ?: return null
        val assets = root.getAsJsonArray("assets")?.mapNotNull { element ->
            val asset = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            Asset(
                name = asset.str("name") ?: return@mapNotNull null,
                url = asset.str("browser_download_url") ?: return@mapNotNull null,
                size = asset.get("size")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                sha256 = asset.str("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase(),
            )
        }.orEmpty()
        return Release(version, root.str("html_url") ?: RELEASES_PAGE, pickApk(assets, abis))
    }

    /** The build for the first of [abis] that has one (APK names end with _<abi>.apk), else the universal one. */
    internal fun pickApk(assets: List<Asset>, abis: List<String>): Asset? =
        abis.firstNotNullOfOrNull { abi -> assets.firstOrNull { it.name.endsWith("_$abi.apk") } }
            ?: assets.firstOrNull { it.name.endsWith("_universal.apk") }

    /** Version numbers compared part by part: 0.10.0 is newer than 0.9.5. */
    internal fun isNewer(candidate: String, current: String): Boolean {
        fun parts(version: String) = version.split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * Downloads [apk] into the cache (blocking) and checks it; [progress] gets the bytes so far and
     * the total. An earlier download of the same file is reused.
     */
    fun download(context: Context, apk: Asset, progress: (Long, Long) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.filter { it.name != apk.name }?.forEach { it.delete() }
        val target = File(dir, apk.name)
        if (target.exists() && matches(target, apk)) return target
        val part = File(dir, "${apk.name}.part")
        var failure: Exception = IOException("no way to download")
        for (client in clients(DOWNLOAD_READ_TIMEOUT_S)) {
            try {
                fetchFile(client, apk.url, part, apk.size, progress)
                if (!matches(part, apk)) throw IOException("the file does not match the release")
                if (!part.renameTo(target)) throw IOException("cannot save the file")
                return target
            } catch (e: Exception) {
                failure = e
                part.delete()
            }
        }
        throw failure
    }

    /** Hands the APK to the system installer. */
    fun installIntent(context: Context, file: File): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.cache", file), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun matches(file: File, apk: Asset): Boolean =
        (apk.size <= 0 || file.length() == apk.size) && (apk.sha256 == null || sha256(file) == apk.sha256)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fetchText(url: String): String? = clients(API_READ_TIMEOUT_S).firstNotNullOfOrNull { client ->
        runCatching {
            client.newCall(Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.body.string()
            }
        }.getOrNull()
    }

    private fun fetchFile(client: OkHttpClient, url: String, file: File, expected: Long, progress: (Long, Long) -> Unit) {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body
            val total = expected.takeIf { it > 0 } ?: body.contentLength()
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var reported = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (done - reported >= PROGRESS_STEP_BYTES) {
                            reported = done
                            progress(done, total)
                        }
                    }
                    progress(done, total)
                }
            }
        }
    }

    /**
     * Direct first (Tropa itself is not in its VPN), then through the tunnel: the protected
     * internal proxy of the running VPN (a SOCKS inbound that also takes HTTP proxy requests).
     */
    private fun clients(readTimeoutS: Long): List<OkHttpClient> {
        val base = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(readTimeoutS, TimeUnit.SECONDS)
            .build()
        val tunnel = LocalProxy.internal()?.let { proxy ->
            base.newBuilder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port)))
                .proxyAuthenticator { _, response ->
                    if (response.request.header("Proxy-Authorization") != null) null
                    else response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(proxy.user, proxy.pass)).build()
                }
                .build()
        }
        return listOfNotNull(base, tunnel)
    }

    private const val API_READ_TIMEOUT_S = 20L
    private const val DOWNLOAD_READ_TIMEOUT_S = 30L
    private const val PROGRESS_STEP_BYTES = 256L * 1024

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.bool(key: String): Boolean = get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean == true
}
