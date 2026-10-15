package io.github.du10777.tropa.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.security.MessageDigest

/** One server (outbound) of a running configuration. */
data class Node(
    val tag: String,
    val name: String,
    val protocol: String,
    val address: String?,
    val transport: String?,
    /** The outbound without its tag: the same server in two versions of a subscription. */
    val fingerprint: String = "",
    /** The outbound as Xray JSON, to add it to a running core. */
    val outbound: String? = null,
)

/**
 * Servers of a configuration and how traffic reaches them. With a [balancerTag] and two or more
 * [nodes], Tropa picks the server for new connections by overriding the balancer target.
 */
data class NodePlan(
    val balancerTag: String?,
    val nodes: List<Node>,
    /** Every proxy outbound of the configuration, for the details screen. */
    val proxies: List<Node> = nodes,
) {
    val isBalanced: Boolean get() = balancerTag != null && nodes.size >= 2
}

/** Reads servers out of an Xray JSON configuration (pure, no Android). */
object NodePlanner {
    private val NON_PROXY = setOf("freedom", "blackhole", "dns", "loopback")
    private val POLICY_MEMBER = Regex("""^proxy-proxy-\d+-(.*)$""")

    fun fromConfigJson(json: String): NodePlan {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return NodePlan(null, emptyList())
        val proxies = root.getAsJsonArray("outbounds")
            ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.let(::toNode) }
            .orEmpty()

        val balancers = root.getAsJsonObject("routing")?.getAsJsonArray("balancers")
            ?.mapNotNull { element ->
                val balancer = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val tag = balancer.string("tag") ?: return@mapNotNull null
                val selectors = balancer.getAsJsonArray("selector")?.mapNotNull { it.takeIf { s -> s.isJsonPrimitive }?.asString }.orEmpty()
                tag to proxies.filter { node -> selectors.any { node.tag.startsWith(it) } }
            }
            .orEmpty()

        val main = balancers.maxByOrNull { it.second.size }
        return if (main != null && main.second.size >= 2) {
            NodePlan(main.first, main.second, proxies)
        } else {
            NodePlan(null, listOfNotNull(proxies.firstOrNull()), proxies)
        }
    }

    private fun toNode(outbound: JsonObject): Node? {
        val tag = outbound.string("tag")?.takeIf { it.isNotEmpty() } ?: return null
        val protocol = outbound.string("protocol") ?: return null
        if (protocol in NON_PROXY) return null
        val address = address(outbound.getAsJsonObject("settings"))
        val stream = outbound.getAsJsonObject("streamSettings")
        val transport = listOfNotNull(stream?.string("network"), stream?.string("security")?.takeIf { it != "none" })
            .joinToString(", ")
            .ifEmpty { null }
        val name = POLICY_MEMBER.find(tag)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(displayProtocol(protocol), address?.substringBeforeLast(':')).joinToString(" · ")
        val content = outbound.deepCopy().apply {
            remove("tag")
            // Set by the app when the server name was resolved while building the config: not the server's.
            obj("streamSettings")?.obj("sockopt")?.let { it.remove("domainStrategy"); it.remove("happyEyeballs") }
        }
        return Node(tag, name, displayProtocol(protocol), address, transport, fingerprint(content), outbound.toString())
    }

    /** A stable digest of a JSON value: object keys sorted, so the order in the file does not matter. */
    fun fingerprint(value: JsonElement): String =
        MessageDigest.getInstance("SHA-256").digest(canonical(value).toByteArray()).take(16).joinToString("") { "%02x".format(it) }

    private fun canonical(value: JsonElement): String = when {
        value.isJsonObject -> value.asJsonObject.entrySet().sortedBy { it.key }
            .joinToString(",", "{", "}") { (key, item) -> JsonPrimitive(key).toString() + ":" + canonical(item) }
        value.isJsonArray -> value.asJsonArray.joinToString(",", "[", "]") { canonical(it) }
        else -> value.toString()
    }

    /** Tags of all outbounds of a configuration, proxies or not. */
    fun outboundTags(json: String): Set<String> =
        runCatching { JsonParser.parseString(json).asJsonObject.getAsJsonArray("outbounds") }.getOrNull()
            ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.string("tag")?.takeIf { tag -> tag.isNotEmpty() } }
            ?.toSet()
            .orEmpty()

    /**
     * [outbound] ready to be added to a running core under [tag]: its references to other
     * outbounds (dialer proxy, proxy chain) are pointed at the tags those have there, [renamed].
     */
    fun prepareOutbound(outbound: String, tag: String, renamed: Map<String, String>): String {
        val root = JsonParser.parseString(outbound).asJsonObject
        root.addProperty("tag", tag)
        fun remap(obj: JsonObject?, key: String) {
            val target = obj?.string(key) ?: return
            renamed[target]?.let { obj.addProperty(key, it) }
        }
        remap(root.obj("streamSettings")?.obj("sockopt"), "dialerProxy")
        remap(root.obj("proxySettings"), "tag")
        return root.toString()
    }

    private fun address(settings: JsonObject?): String? {
        settings ?: return null
        fun firstOf(array: String): JsonObject? = settings.getAsJsonArray(array)?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        firstOf("vnext")?.let { return hostPort(it) }
        firstOf("servers")?.let { return hostPort(it) }
        firstOf("peers")?.string("endpoint")?.let { return it }
        return hostPort(settings)
    }

    private fun hostPort(obj: JsonObject): String? {
        val host = obj.string("address") ?: return null
        val port = obj.get("port")?.takeIf { it.isJsonPrimitive }?.asString
        return if (port.isNullOrEmpty()) host else "$host:$port"
    }

    fun displayProtocol(protocol: String): String = when (protocol.lowercase()) {
        "vless" -> "VLESS"
        "vmess" -> "VMess"
        "trojan" -> "Trojan"
        "shadowsocks" -> "Shadowsocks"
        "wireguard" -> "WireGuard"
        "hysteria2", "hysteria" -> "Hysteria2"
        "socks" -> "SOCKS"
        "http" -> "HTTP"
        else -> protocol
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject
}
