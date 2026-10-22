package io.github.du10777.tropa.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import java.security.SecureRandom

/**
 * Local proxies of the core.
 *
 * The internal SOCKS inbound, which hev-socks5-tunnel feeds the VPN traffic into, gets a random
 * port and a random login and password on every start of the VPN (the upstream settings
 * PREF_DYNAMIC_SOCKS_PORT and PREF_SOCKS_USERNAME/PASSWORD, which hev reads too). Other apps on
 * the phone can then neither use it nor find it on the well-known 10808/10809 to tell that a VPN
 * runs. JSON subscriptions bring their own local inbounds (3X-UI: socks 10808 and http 10809
 * without a password): they are replaced by the internal one.
 *
 * The user's own local proxy (off by default) is one more inbound with the port, login and
 * password from the settings; Xray's socks inbound answers HTTP proxy requests on the same port.
 */
object LocalProxy {
    private const val ENABLED = "tropa_proxy_enabled"
    private const val PORT = "tropa_proxy_port"
    private const val USER = "tropa_proxy_user"
    private const val PASS = "tropa_proxy_pass"
    private const val INTERNAL_PORT = "tropa_internal_port"
    private const val VPN_DNS_SAVED = "tropa_vpn_dns_saved"
    const val USER_TAG = "tropa-local"
    private val LOCAL_PROTOCOLS = setOf("socks", "http", "mixed")
    private const val ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val random = SecureRandom()

    data class UserProxy(val enabled: Boolean, val port: Int, val user: String, val pass: String)

    data class Internal(val port: Int, val user: String, val pass: String)

    var userProxy: UserProxy
        get() {
            val port = MmkvManager.decodeSettingsInt(PORT, 0).takeIf { it in 1024..65535 }
                ?: (20000 + random.nextInt(40000)).also { MmkvManager.encodeSettings(PORT, it) }
            return UserProxy(
                enabled = MmkvManager.decodeSettingsBool(ENABLED, false),
                port = port,
                user = MmkvManager.decodeSettingsString(USER).orEmpty(),
                pass = MmkvManager.decodeSettingsString(PASS).orEmpty(),
            )
        }
        set(value) {
            MmkvManager.encodeSettings(ENABLED, value.enabled)
            MmkvManager.encodeSettings(PORT, value.port)
            MmkvManager.encodeSettings(USER, value.user)
            MmkvManager.encodeSettings(PASS, value.pass)
        }

    /**
     * Every start of the VPN service, before hev and the core read them (they start in that order):
     * a random port (upstream's dynamic SOCKS port) and a new login and password. Core reloads
     * without dropping the VPN keep them, so hev and the core always agree.
     */
    fun refreshCredentials() {
        MmkvManager.encodeSettings(AppConfig.PREF_DYNAMIC_SOCKS_PORT, true)
        SettingsManager.refreshRuntimeSocksPort()
        MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_USERNAME, token(12))
        MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_PASSWORD, token(24))
        // The runtime port lives in this process only; the UI reads it from here (updates through the tunnel).
        MmkvManager.encodeSettings(INTERNAL_PORT, SettingsManager.getSocksPort())
        prepareVpnDns()
    }

    /**
     * With DNS inside the tunnel the VPN gives the phone its router address as the DNS server, so
     * Android does not talk DNS-over-TLS past the core (see [TunnelDns]); otherwise the user's value
     * comes back. Runs before the VPN is built, on every start.
     */
    private fun prepareVpnDns() {
        val current = MmkvManager.decodeSettingsString(AppConfig.PREF_VPN_DNS)
        val saved = MmkvManager.decodeSettingsString(VPN_DNS_SAVED)?.takeIf { it.isNotEmpty() }
        val server = Connections.activeId()?.let(TropaPrefs::serverDns)
        val next = TunnelDns.vpnDnsFor(server, vpnRouter(), current, saved) ?: return
        if (next == vpnRouter()) {
            MmkvManager.encodeSettings(VPN_DNS_SAVED, current ?: TunnelDns.DEFAULT_VPN_DNS)
        } else {
            MmkvManager.encodeSettings(VPN_DNS_SAVED, "")
        }
        MmkvManager.encodeSettings(AppConfig.PREF_VPN_DNS, next)
    }

    private fun vpnRouter(): String = SettingsManager.getCurrentVpnInterfaceAddressConfig().ipv4Router

    /** The internal inbound of the running VPN, from any process (stale while the VPN is off). */
    fun internal(): Internal? {
        val port = MmkvManager.decodeSettingsInt(INTERNAL_PORT, 0).takeIf { it in 1..65535 } ?: return null
        val user = SettingsManager.getSocksUsername() ?: return null
        val pass = SettingsManager.getSocksPassword() ?: return null
        return Internal(port, user, pass)
    }

    /** Whether geosite.dat has the Russian list; the 10 MB file is read again only when it changes. */
    private var geositeChecked: Triple<Long, Long, Boolean>? = null

    @Synchronized
    private fun russianGeosite(): Boolean {
        val dir = java.io.File(com.v2ray.ang.util.Utils.userAssetPath(com.v2ray.ang.AngApplication.application))
        val file = java.io.File(dir, "geosite.dat")
        geositeChecked?.let { (length, modified, has) -> if (length == file.length() && modified == file.lastModified()) return has }
        return TunnelDns.hasRussianGeosite(dir).also { geositeChecked = Triple(file.length(), file.lastModified(), it) }
    }

    fun token(length: Int): String = buildString { repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    /**
     * The config the core starts with: local inbounds replaced as described above, the traffic
     * counters per server on (see [TrafficWatch]), server names connecting to their last proven IP
     * (see [ServerAddresses]), DNS through the tunnel when the server has it (see [TunnelDns]), and in
     * roaming without the user's consent everything blocked (see [Roaming]).
     */
    fun patchConfig(config: String): String {
        val user = SettingsManager.getSocksUsername() ?: return config
        val pass = SettingsManager.getSocksPassword() ?: return config
        var patched = ServerAddresses.pinConfig(
            patch(config, Internal(SettingsManager.getSocksPort(), user, pass), userProxy.takeIf { it.enabled }),
        )
        val server = Connections.activeId()?.let(TropaPrefs::serverDns)
        val router = vpnRouter().takeIf { MmkvManager.decodeSettingsString(AppConfig.PREF_VPN_DNS) == it }
        patched = TunnelDns.patchConfig(patched, server, server != null && russianGeosite(), router)
        return if (Roaming.blocked) Roaming.blockAll(patched) else patched
    }

    internal fun patch(config: String, internal: Internal, user: UserProxy?): String {
        val root = runCatching { JsonParser.parseString(config).asJsonObject }.getOrNull() ?: return config
        val inbounds = root.get("inbounds")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray().also { root.add("inbounds", it) }
        val local = inbounds.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            .filter { it.str("protocol") in LOCAL_PROTOCOLS }
        // The first socks inbound keeps its tag (routing rules may name it), sniffing and user level.
        val template = local.firstOrNull { it.str("protocol") != "http" } ?: local.firstOrNull()
        local.forEach { inbounds.remove(it) }
        val sniffing = template?.get("sniffing")?.takeIf { it.isJsonObject }?.deepCopy()?.asJsonObject ?: JsonObject().apply {
            addProperty("enabled", true)
            add("destOverride", JsonArray().apply { add("http"); add("tls"); add("quic") })
        }
        val level = template?.get("settings")?.takeIf { it.isJsonObject }?.asJsonObject?.get("userLevel")
        inbounds.add(
            inbound(template?.str("tag") ?: "socks", internal.port, internal.user, internal.pass, sniffing, level)
        )
        if (user != null) {
            inbounds.add(inbound(USER_TAG, user.port, user.user, user.pass, sniffing.deepCopy(), level))
        }
        // The watchdog reads the bytes per server (the user's traffic means the server is alive).
        TrafficWatch.enableStats(root)
        return root.toString()
    }

    private fun inbound(tag: String, port: Int, user: String, pass: String, sniffing: JsonObject, level: com.google.gson.JsonElement?) =
        JsonObject().apply {
            addProperty("tag", tag)
            addProperty("listen", AppConfig.LOOPBACK)
            addProperty("port", port)
            addProperty("protocol", "socks")
            add("settings", JsonObject().apply {
                if (user.isNotEmpty() && pass.isNotEmpty()) {
                    addProperty("auth", "password")
                    add("accounts", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("user", user)
                            addProperty("pass", pass)
                        })
                    })
                } else {
                    addProperty("auth", "noauth")
                }
                addProperty("udp", true)
                level?.let { add("userLevel", it.deepCopy()) }
            })
            add("sniffing", sniffing)
        }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
