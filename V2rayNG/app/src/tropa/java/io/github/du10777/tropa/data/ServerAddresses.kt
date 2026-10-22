package io.github.du10777.tropa.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.handler.MmkvManager
import java.util.Locale

/**
 * The last IP every server name was proven to work at (LastIP). The core connects to a server by
 * its LastIP while TLS and the HTTP Host keep the name: a DNS answer that is blocked, wrong or slow
 * does not stop the connection, and nothing waits for a dead address. The watchdog checks the names
 * in the background (a fresh DNS answer and the LastIP are tried at the same time, the first that
 * answers TLS for the name wins and becomes the LastIP).
 */
object ServerAddresses {
    private const val PREFIX = "tropa_last_ip_"
    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val PROXY_PROTOCOLS = setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http", "hysteria2", "hysteria")

    /** An address worth pinning: a name, not an IP literal or a local name. */
    fun isName(address: String): Boolean {
        val a = address.lowercase(Locale.ROOT)
        return a.isNotEmpty() && ':' !in a && !IPV4.matches(a) && '.' in a && !Diagnosis.isLocal(a)
    }

    fun lastIp(name: String): String? =
        MmkvManager.decodeSettingsString(PREFIX + name.lowercase(Locale.ROOT))?.takeIf { IPV4.matches(it) }

    fun setLastIp(name: String, ip: String) {
        if (IPV4.matches(ip)) MmkvManager.encodeSettings(PREFIX + name.lowercase(Locale.ROOT), ip)
    }

    /** The core's config with every server name that has a LastIP connecting to that IP. */
    fun pinConfig(config: String, lastIp: (String) -> String? = ::lastIp): String {
        val root = runCatching { JsonParser.parseString(config).asJsonObject }.getOrNull() ?: return config
        return if (pin(root, lastIp)) root.toString() else config
    }

    /**
     * Pins the proxy outbounds of [root] in place: the server address becomes its LastIP, and the
     * name moves to where the transport needs it, unless the config set it already: the TLS server
     * name, the HTTP Host of XHTTP, WebSocket and HTTPUpgrade, the authority of gRPC. Returns
     * whether anything changed.
     */
    fun pin(root: JsonObject, lastIp: (String) -> String?): Boolean {
        var changed = false
        root.get("outbounds")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { element ->
            val outbound = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            if (outbound.str("protocol")?.lowercase(Locale.ROOT) !in PROXY_PROTOCOLS) return@forEach
            val server = serverObject(outbound.obj("settings")) ?: return@forEach
            val name = server.str("address")?.trim()?.takeIf(::isName) ?: return@forEach
            val ip = lastIp(name) ?: return@forEach
            server.addProperty("address", ip)
            keepName(outbound, name)
            changed = true
        }
        return changed
    }

    /** The object that holds the server address: vnext[0], servers[0] or the settings themselves (the flat form). */
    private fun serverObject(settings: JsonObject?): JsonObject? {
        settings ?: return null
        fun first(key: String): JsonObject? = settings.get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        return first("vnext") ?: first("servers") ?: settings.takeIf { it.has("address") }
    }

    private fun keepName(outbound: JsonObject, name: String) {
        val stream = outbound.obj("streamSettings") ?: JsonObject().also { outbound.add("streamSettings", it) }
        when (stream.str("security")) {
            "tls" -> setIfEmpty(stream.objOrNew("tlsSettings"), "serverName", name)
            // REALITY carries the name of the site it borrows, not the server's: nothing to keep.
        }
        when (stream.str("network")) {
            "xhttp", "splithttp" -> setIfEmpty(stream.objOrNew(if (stream.has("splithttpSettings")) "splithttpSettings" else "xhttpSettings"), "host", name)
            "ws" -> {
                val ws = stream.objOrNew("wsSettings")
                val headerHost = ws.obj("headers")?.str("Host")
                if (headerHost.isNullOrEmpty()) setIfEmpty(ws, "host", name)
            }
            "httpupgrade" -> setIfEmpty(stream.objOrNew("httpupgradeSettings"), "host", name)
            "grpc" -> setIfEmpty(stream.objOrNew("grpcSettings"), "authority", name)
        }
    }

    private fun setIfEmpty(obj: JsonObject, key: String, value: String) {
        if (obj.str(key).isNullOrEmpty()) obj.addProperty(key, value)
    }

    private fun JsonObject.objOrNew(key: String): JsonObject = obj(key) ?: JsonObject().also { add(key, it) }

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf(JsonElement::isJsonPrimitive)?.asString
}
