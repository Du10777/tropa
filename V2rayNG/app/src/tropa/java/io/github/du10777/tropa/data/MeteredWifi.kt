package io.github.du10777.tropa.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.v2ray.ang.handler.MmkvManager
import java.net.Inet4Address
import java.security.MessageDigest

/**
 * Wi-Fi with limited traffic, such as a phone's hotspot. When Android marks the Wi-Fi as metered
 * (Android hotspots say so themselves; the person may mark any network), Tropa asks whether its traffic
 * is limited and remembers the answer for that network. On a limited one the checks spend tens of
 * times less: smaller and rarer requests, no speed tests (see the watchdog). Until the person answers,
 * a metered Wi-Fi counts as limited.
 *
 * A network is known without the location permission (which the name of a Wi-Fi needs): by its
 * subnet, gateway, DNS servers and domain from DHCP. Two routers with the same factory settings look
 * the same, and a hotspot that picks a new subnet each time looks new (then Tropa asks again).
 */
object MeteredWifi {
    /** The network Tropa runs over is a Wi-Fi: its key, and whether it looks metered. */
    data class Wifi(val key: String, val metered: Boolean)

    private data class Entry(val limited: Boolean, val at: Long)

    private const val KEY = "tropa_wifi_answers"
    private const val KEEP_MS = 365L * 24 * 60 * 60_000
    private const val IPHONE_HOTSPOT = "172.20.10.0/28"
    private val gson = Gson()
    private val type = object : TypeToken<Map<String, Entry>>() {}.type

    /** Test bench without Wi-Fi: pretend one (see TropaServiceEventsReceiver.ACTION_TEST_WIFI). */
    @Volatile
    var override: Wifi? = null

    /** The Wi-Fi under the VPN, or null when [network] is not a Wi-Fi. */
    fun of(cm: ConnectivityManager, network: Network?): Wifi? {
        override?.let { return it }
        network ?: return null
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val link = runCatching { cm.getLinkProperties(network) }.getOrNull() ?: return null
        val subnet = subnetOf(link) ?: return null
        val key = key(subnet, gatewayOf(link).orEmpty(), link.dnsServers.mapNotNull { it.hostAddress }, link.domains.orEmpty())
        val systemMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return Wifi(key, systemMetered || subnet == IPHONE_HOTSPOT)
    }

    /** The Wi-Fi the phone is on now, for the settings screen (the VPN network itself is skipped). */
    @Suppress("DEPRECATION")
    fun current(context: Context): Wifi? {
        override?.let { return it }
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.allNetworks.firstOrNull { network ->
            cm.getNetworkCapabilities(network)?.let { caps ->
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true
        }
        return of(cm, network)
    }

    /** The network's key: a hash of what DHCP gave (not reversible to the addresses). */
    fun key(subnet: String, gateway: String, dns: List<String>, domains: String): String =
        MessageDigest.getInstance("SHA-256").digest("$subnet|$gateway|${dns.sorted().joinToString(",")}|$domains".toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }

    /** "192.168.1.0/24" for an address 192.168.1.37/24. */
    fun subnet(address: ByteArray, prefix: Int): String {
        val value = address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
        val mask = if (prefix <= 0) 0L else (0xffffffffL shl (32 - prefix.coerceAtMost(32))) and 0xffffffffL
        val network = value and mask
        return (3 downTo 0).joinToString(".") { ((network shr (it * 8)) and 0xff).toString() } + "/$prefix"
    }

    private fun subnetOf(link: LinkProperties): String? =
        link.linkAddresses.firstOrNull { it.address is Inet4Address }?.let { subnet(it.address.address, it.prefixLength) }

    private fun gatewayOf(link: LinkProperties): String? =
        link.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress

    /** The person's answer for the network: true = limited, false = unlimited, null = not asked yet. */
    fun limited(key: String): Boolean? = load()[key]?.limited

    fun remember(key: String, limited: Boolean, now: Long = System.currentTimeMillis()) {
        save(prune(load(), now) + (key to Entry(limited, now)))
    }

    /** How many networks the person answered about. */
    fun answered(): Int = load().size

    fun forgetAll() {
        MmkvManager.encodeSettings(KEY, "")
    }

    /** The checks save traffic: a limited Wi-Fi, or a metered one the person has not answered about yet. */
    fun economy(wifi: Wifi?, limited: Boolean?): Boolean = wifi != null && (limited ?: wifi.metered)

    /** Ask about a metered Wi-Fi the person has not answered about. */
    fun shouldAsk(wifi: Wifi?, limited: Boolean?): Boolean = wifi != null && wifi.metered && limited == null

    private fun load(): Map<String, Entry> =
        MmkvManager.decodeSettingsString(KEY)?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { gson.fromJson<Map<String, Entry>>(it, type) }.getOrNull() }.orEmpty()

    private fun save(entries: Map<String, Entry>) {
        MmkvManager.encodeSettings(KEY, gson.toJson(entries))
    }

    /** Answers older than a year are dropped (the network may be another one by now). */
    private fun prune(entries: Map<String, Entry>, now: Long): Map<String, Entry> = entries.filterValues { now - it.at < KEEP_MS }
}
