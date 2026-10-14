package io.github.du10777.tropa.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** One server (outbound) of a running configuration. */
data class Node(
    val tag: String,
    val name: String,
    val protocol: String,
    val address: String?,
    val transport: String?,
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
        return Node(tag, name, displayProtocol(protocol), address, transport)
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
}
