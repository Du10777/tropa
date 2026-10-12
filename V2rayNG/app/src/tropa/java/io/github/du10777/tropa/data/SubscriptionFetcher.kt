package io.github.du10777.tropa.data

import com.v2ray.ang.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** A downloaded subscription: [finalUrl] is the address after redirects (one-time links redirect). */
class FetchResult(
    val finalUrl: String,
    val body: String,
    /** Response headers with lower-cased names. */
    val headers: Map<String, String>,
)

/**
 * Downloads subscriptions for Tropa. Unlike the upstream updater it keeps the final address after
 * redirects and the response headers (title, traffic and expiry info).
 */
object SubscriptionFetcher {
    private const val MAX_BODY_BYTES = 8L * 1024 * 1024

    /** Panels pick the response format by User-Agent, so the v2rayNG token is kept. */
    val userAgent: String
        get() = "Tropa/${BuildConfig.VERSION_NAME} v2rayNG/${BuildConfig.UPSTREAM_VERSION_NAME}"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    @Throws(IOException::class)
    fun fetch(url: String): FetchResult {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val headers = response.headers.names().associate { name ->
                name.lowercase(Locale.ROOT) to response.headers[name].orEmpty()
            }
            return FetchResult(
                finalUrl = response.request.url.toString(),
                body = response.peekBody(MAX_BODY_BYTES).string(),
                headers = headers,
            )
        }
    }
}
