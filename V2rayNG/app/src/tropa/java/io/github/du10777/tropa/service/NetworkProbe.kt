package io.github.du10777.tropa.service

import android.net.ConnectivityManager
import android.net.Network
import io.github.du10777.tropa.data.Diagnosis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * The probes of the diagnosis ([Diagnosis]) on the phone's own network: the app is excluded from
 * its VPN, and the sockets are bound to the physical network as well. A ping is the system ping
 * (ICMP echo sockets need no root on Android), a port is a TCP connect, the web is one HTTP request
 * where any answer counts.
 */
class NetworkProbe(private val network: Network?) {
    private val timeoutMs = Diagnosis.TIMEOUT_MS

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs + 1_000, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .apply {
                network?.let { n ->
                    socketFactory(n.socketFactory)
                    dns { host -> n.getAllByName(host).sortedBy { if (it is Inet4Address) 0 else 1 } }
                }
            }
            .build()
    }

    /** Every resource, all at once or one by one (a weak mobile network). */
    suspend fun probeAll(resources: List<Diagnosis.Resource>, parallel: Boolean): Map<Diagnosis.Resource, Diagnosis.Probe> =
        if (parallel) {
            coroutineScope { resources.map { r -> async(Dispatchers.IO) { r to probe(r) } }.awaitAll().toMap() }
        } else {
            resources.associateWith { probe(it) }
        }

    /** Ping and port at the same time; the web only through an open port. */
    suspend fun probe(resource: Diagnosis.Resource): Diagnosis.Probe = coroutineScope {
        val ping = async(Dispatchers.IO) { ping(resource.host) }
        val port = connect(resource.host, resource.port)
        val web = if (port && resource.url != null) web(resource.url, resource.anyCertificate) else null
        Diagnosis.Probe(ping = ping.await(), port = port, web = web)
    }

    /** Whether the gateway of the network answers a ping; null without an IPv4 gateway (point-to-point mobile links). */
    suspend fun gatewayAnswers(cm: ConnectivityManager): Boolean? {
        val gateway = network?.let { runCatching { cm.getLinkProperties(it) }.getOrNull() }
            ?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address && !it.gateway!!.isAnyLocalAddress }
            ?.gateway ?: return null
        return ping(gateway.hostAddress ?: return null)
    }

    /** One ICMP echo through the system ping; its process is stopped if it outlives the timeout (slow DNS). */
    suspend fun ping(host: String): Boolean = withContext(Dispatchers.IO) {
        val seconds = (timeoutMs / 1000).coerceAtLeast(1)
        val process = runCatching {
            ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "$seconds", "-n", host).redirectErrorStream(true).start()
        }.getOrNull() ?: return@withContext false
        val stopper = launch {
            delay(timeoutMs + 1_500)
            process.destroy()
        }
        try {
            process.inputStream.use { it.readBytes() }
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        } finally {
            stopper.cancel()
            process.destroy()
        }
    }

    private suspend fun connect(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        withTimeoutOrNull(timeoutMs + 1_000) {
            runCatching {
                runInterruptible {
                    val address = resolve(host) ?: return@runInterruptible false
                    Socket().use { socket ->
                        network?.bindSocket(socket)
                        socket.connect(InetSocketAddress(address, port), timeoutMs.toInt())
                        true
                    }
                }
            }.getOrDefault(false)
        } ?: false
    }

    private fun resolve(host: String): InetAddress? =
        (network?.getAllByName(host) ?: InetAddress.getAllByName(host)).sortedBy { if (it is Inet4Address) 0 else 1 }.firstOrNull()

    /**
     * Any HTTP answer counts. With [anyCertificate] (a server's front page) a certificate the phone
     * does not trust counts too: the server answered TLS, which is all the diagnosis asks.
     */
    private suspend fun web(url: String, anyCertificate: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use { true }
        } catch (e: SSLException) {
            // Untrusted chain (CertificateException inside) or a name the certificate does not cover.
            anyCertificate && (e is SSLPeerUnverifiedException || generateSequence<Throwable>(e) { it.cause }.any { it is CertificateException })
        } catch (_: Exception) {
            false
        }
    }
}
