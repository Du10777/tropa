package io.github.du10777.tropa.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.InetAddress
import java.net.URI
import java.util.Locale

/**
 * What the network lets through when no server answers through the tunnel (pure logic, unit
 * tested; the probes themselves are in the service). Three groups of resources are probed directly,
 * outside the tunnel: the servers of the connection, Russian sites that stay open under "white
 * lists" of mobile networks, and foreign sites that do not. Each gets an ICMP ping, a TCP connect
 * to its port and, when the port opens, an HTTP request (for a server: its TLS front page).
 *
 * The verdict, in this order:
 * - a server is reachable → the tunnel itself fails: servers reachable, reconnecting;
 * - a foreign site is reachable → the internet works, the servers are blocked;
 * - only white-list sites are reachable → the phone is in a white-list zone;
 * - nothing → the gateway of the network decides: no answer = no network, an answer = no internet.
 */
object Diagnosis {
    enum class Group { SERVER, WHITELIST, FOREIGN }

    enum class Reach {
        /** The port opens and the web answers (a ping is not needed: many hosts drop it). */
        AVAILABLE,

        /** Something answers (ping or port), but not the web: the network limits it. */
        RESTRICTED,

        /** Neither ping nor port. */
        UNAVAILABLE,
    }

    enum class Verdict { NO_NETWORK, NO_INTERNET, WHITELIST, SERVERS_BLOCKED, SERVERS_REACHABLE }

    /** One resource to probe. [url] null: only ping and port (a server without TLS). */
    data class Resource(
        val group: Group,
        val host: String,
        val port: Int,
        val url: String?,
        /** A server's front page is checked for an answer only: its certificate is not the point. */
        val anyCertificate: Boolean = false,
        val label: String = host,
    )

    data class Probe(val ping: Boolean, val port: Boolean, val web: Boolean?) {
        val reach: Reach
            get() = when {
                port && web != false -> Reach.AVAILABLE
                ping || port -> Reach.RESTRICTED
                else -> Reach.UNAVAILABLE
            }

        /** "ping ✓ port ✓ web ✗" for the log. */
        fun describe(): String = "ping ${mark(ping)} port ${mark(port)} web ${web?.let(::mark) ?: "-"}"

        private fun mark(ok: Boolean) = if (ok) "✓" else "✗"
    }

    data class Result(val verdict: Verdict, val probes: Map<Resource, Probe>, val gateway: Boolean?) {
        /** "servers 0/1, white list 2/4, foreign 0/3, gateway answers" for the log. */
        fun summary(): String = buildList {
            Group.entries.forEach { group ->
                val all = probes.filterKeys { it.group == group }.values
                if (all.isNotEmpty()) {
                    val restricted = all.count { it.reach == Reach.RESTRICTED }
                    add(
                        "${groupName(group)} ${all.count { it.reach == Reach.AVAILABLE }}/${all.size}" +
                            if (restricted > 0) " (limited $restricted)" else "",
                    )
                }
            }
            gateway?.let { add(if (it) "gateway answers" else "gateway silent") }
        }.joinToString(", ")

        private fun groupName(group: Group) = when (group) {
            Group.SERVER -> "servers"
            Group.WHITELIST -> "white list"
            Group.FOREIGN -> "foreign"
        }
    }

    /** Foreign sites that white lists do not open; any answer counts. */
    val FOREIGN_URLS = listOf(
        "https://www.google.com/generate_204",
        "https://www.cloudflare.com/cdn-cgi/trace",
        "https://www.wikipedia.org/",
    )

    /** Probe timeout of each signal, and of the whole resource when probed one by one. */
    const val TIMEOUT_MS = 3_000L

    /** A diagnosis holds this long for the same network; a network change asks again. */
    const val TTL_MS = 5 * 60_000L

    /**
     * Probes all resources at once on Wi-Fi, Ethernet and good mobile signal (3–4 bars of 4); on a
     * weak mobile network one by one, so they do not choke each other.
     */
    fun parallel(cell: Cellular.State): Boolean = !cell.cellular || (cell.signalLevel ?: 4) >= 3

    /** [gateway]: whether the gateway of the network answers a ping (null: not asked, or no gateway known). */
    fun verdict(probes: Map<Resource, Probe>, gateway: Boolean?): Verdict {
        fun any(group: Group) = probes.any { (resource, probe) -> resource.group == group && probe.reach == Reach.AVAILABLE }
        return when {
            any(Group.SERVER) -> Verdict.SERVERS_REACHABLE
            any(Group.FOREIGN) -> Verdict.SERVERS_BLOCKED
            any(Group.WHITELIST) -> Verdict.WHITELIST
            gateway == false -> Verdict.NO_NETWORK
            else -> Verdict.NO_INTERNET
        }
    }

    /** The nothing-works verdicts: the phone's own network is the problem, not the servers. */
    fun offline(verdict: Verdict): Boolean = verdict == Verdict.NO_NETWORK || verdict == Verdict.NO_INTERNET

    /** The resources to probe: the servers of the plan (remote ones only), white-list sites, foreign sites. */
    fun resources(nodes: List<Node>, whitelistUrls: List<String>, foreignUrls: List<String> = FOREIGN_URLS): List<Resource> =
        nodes.mapNotNull(::serverResource).distinctBy { it.host to it.port } +
            whitelistUrls.mapNotNull { urlResource(Group.WHITELIST, it) } +
            foreignUrls.mapNotNull { urlResource(Group.FOREIGN, it) }

    /** A server as a resource: its address and port; with TLS its front page under the TLS name. */
    fun serverResource(node: Node): Resource? {
        val outbound = node.outbound?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
        val (host, port) = node.address?.let(::splitHostPort) ?: return null
        if (port == null || isLocal(host)) return null
        val stream = outbound?.obj("streamSettings")
        val security = stream?.str("security")
        val tlsName = stream?.obj("tlsSettings")?.str("serverName")?.takeIf { it.isNotBlank() }
        val url = if (security == "tls") "https://${tlsName ?: bracket(host)}:$port/" else null
        return Resource(Group.SERVER, host, port, url, anyCertificate = true, label = node.name.ifEmpty { host })
    }

    private fun urlResource(group: Group, url: String): Resource? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val port = if (uri.port > 0) uri.port else if (uri.scheme == "http") 80 else 443
        return Resource(group, host, port, url)
    }

    /** "host:443", "[2001:db8::1]:443", "1.2.3.4:443" → host and port. */
    internal fun splitHostPort(address: String): Pair<String, Int?>? {
        val text = address.trim()
        if (text.startsWith("[")) {
            val end = text.indexOf(']')
            if (end < 0) return null
            return text.substring(1, end) to text.substring(end + 1).removePrefix(":").toIntOrNull()
        }
        val colon = text.lastIndexOf(':')
        if (colon < 0 || text.count { it == ':' } > 1) return text to null
        return text.substring(0, colon) to text.substring(colon + 1).toIntOrNull()
    }

    /** Loopback, private and link-local literals, and local names: nothing to diagnose on the way out. */
    internal fun isLocal(host: String): Boolean {
        val h = host.lowercase(Locale.ROOT)
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".internal")) return true
        if (!h.all { it.isDigit() || it == '.' } && ':' !in h) return false
        val address = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return false
        return address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress || address.isAnyLocalAddress
    }

    private fun bracket(host: String) = if (':' in host) "[$host]" else host

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
