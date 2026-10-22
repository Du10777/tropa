package io.github.du10777.tropa.data

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.Locale

/**
 * DNS through the tunnel. A server that announces it (subscription header
 * `Tropa-Dns: tcp://dns.tropa.internal:53`) runs a resolver on every entry node, reached by that
 * name inside the tunnel. Then the core answers the apps' DNS itself:
 *
 * - Russian names (.ru, .su, .рф, the Russian geosite list) and the names of the servers themselves
 *   from the Russian resolver [LOCAL_DNS], directly: Russian sites go direct and need local answers,
 *   and a server name must resolve before the tunnel is up;
 * - every other name from the resolver of the entry node through the tunnel: the network of the
 *   phone does not see those questions at all;
 * - what the config asked before stays as the fallback, if the node's resolver does not answer.
 *
 * The apps' DNS is taken into the core's DNS module. For that the VPN gives the phone the router address
 * of its own subnet as the DNS server (v2rayNG has this idea commented out), not 1.1.1.1: Android's
 * "Private DNS: automatic" would otherwise talk DNS-over-TLS to 1.1.1.1 through the tunnel, past the
 * core. DNS-over-TLS to the router address is refused at once, so Android asks plainly, and port 53
 * goes to the core's DNS. While the VPN keeps that address the capture stays in every core start,
 * also when the server stopped announcing its DNS (the config's own DNS answers then).
 *
 * A config with fake DNS is left alone. Pure JSON work, unit tested; the header, the VPN's router
 * address and the geosite check come from outside.
 */
object TunnelDns {
    const val HEADER = "tropa-dns"
    const val DNS_TAG = "tropa-dns"
    const val OUT_TAG = "tropa-dns-out"
    const val DIRECT_TAG = "tropa-direct"
    const val BLOCK_TAG = "tropa-block"
    const val LOCAL_DNS = "77.88.8.8"
    const val TIMEOUT_MS = 3000
    val RU_DOMAINS = listOf("domain:ru", "domain:su", "domain:xn--p1ai")
    const val RU_GEOSITE = "geosite:category-ru"
    private val ADDRESS = Regex("""^tcp://([a-z0-9-]+\.)+[a-z0-9-]+:\d{1,5}$""")
    private val APP_INBOUNDS = setOf("socks", "http", "mixed", "tun")
    private val NOT_PROXIES = setOf("freedom", "blackhole", "dns", "loopback")

    /** Rule fields that make a rule specific: a rule without them (but port 0-65535) is the catch-all. */
    private val CONDITIONS = listOf("domain", "ip", "source", "sourcePort", "user", "inboundTag", "protocol", "attrs", "process")

    /** The server address from the header if it is one Tropa can use (`tcp://name:port`), else null. */
    fun parse(header: String?): String? =
        header?.trim()?.lowercase(Locale.ROOT)?.takeIf { ADDRESS.matches(it) && it.substringAfterLast(':').toInt() in 1..65535 }

    /**
     * [config] with the apps' DNS in the core and, with [server], DNS through the tunnel there.
     * [router]: the VPN gives the phone this DNS address (DNS-over-TLS to it is refused). Unchanged
     * when there is nothing to do, the config is not JSON or uses fake DNS.
     */
    fun patchConfig(config: String, server: String?, geositeRu: Boolean, router: String? = null): String {
        if (server == null && router == null) return config
        val root = runCatching { JsonParser.parseString(config).asJsonObject }.getOrNull() ?: return config
        return if (patch(root, server, geositeRu, router)) root.toString() else config
    }

    /** Patches [root] in place; true when it changed. */
    fun patch(root: JsonObject, server: String?, geositeRu: Boolean, router: String? = null): Boolean {
        val oldDns = root.obj("dns")
        val oldServers = oldDns?.list("servers") ?: JsonArray()
        if (oldServers.any { (it.takeIf(JsonElement::isJsonPrimitive)?.asString ?: it.obj()?.str("address")) == "fakedns" }) return false
        val outbounds = root.list("outbounds")
        val rules = root.obj("routing")?.list("rules") ?: JsonArray()
        val dnsTag = oldDns?.str("tag")?.takeIf { it.isNotEmpty() } ?: DNS_TAG
        val newOutbounds = JsonArray().apply { outbounds.forEach { add(it) } }
        val top = mutableListOf<JsonObject>()
        val dns = oldDns ?: JsonObject().also { root.add("dns", it) }
        dns.addProperty("tag", dnsTag)

        if (server == null) {
            // Only the capture (the VPN still gives the router address): the config's DNS answers.
            if (oldServers.size() == 0) dns.add("servers", JsonArray().apply { add(FALLBACK_DNS) })
            addCapture(root, top, newOutbounds, outbounds, router)
            finish(root, newOutbounds, top, rules)
            return true
        }

        // Where the tunnel goes: the route the config gives its own DNS, else its catch-all, else the first proxy.
        val proxyRoute = routeOf(rules, dnsTag, outbounds) ?: return false
        val directTag = outbounds.objects().firstOrNull { it.str("protocol") == "freedom" && it.str("tag") == "direct" }?.str("tag")
            ?: outbounds.objects().firstOrNull { it.str("protocol") == "freedom" && it.obj("settings")?.has("redirect") != true }?.str("tag")
            ?: DIRECT_TAG.also { newOutbounds.add(JsonObject().apply { addProperty("tag", it); addProperty("protocol", "freedom") }) }

        val local = JsonObject().apply {
            addProperty("address", LOCAL_DNS)
            add("domains", JsonArray().apply {
                (RU_DOMAINS + listOfNotNull(RU_GEOSITE.takeIf { geositeRu }) + serverNames(outbounds).map { "full:$it" })
                    .distinct().forEach { add(it) }
            })
            addProperty("skipFallback", true)
        }
        val tunnel = JsonObject().apply {
            addProperty("address", server)
            addProperty("timeoutMs", TIMEOUT_MS)
        }
        dns.add("servers", JsonArray().apply {
            add(local)
            add(tunnel)
            oldServers.forEach { add(it) }
        })
        // In order: the tunnel first, the old servers only when it does not answer.
        dns.remove("enableParallelQuery")

        val name = server.substringAfter("://").substringBeforeLast(':')
        top += fromDns(dnsTag).apply {
            add("ip", JsonArray().apply { add(LOCAL_DNS) })
            addProperty("outboundTag", directTag)
        }
        top += fromDns(dnsTag).apply {
            add("domain", JsonArray().apply { add("full:$name") })
            proxyRoute(this)
        }
        addCapture(root, top, newOutbounds, outbounds, router)
        finish(root, newOutbounds, top, rules)
        return true
    }

    /** The apps' DNS (port 53) into the core; DNS-over-TLS to the VPN's router address refused at once. */
    private fun addCapture(root: JsonObject, top: MutableList<JsonObject>, newOutbounds: JsonArray, outbounds: JsonArray, router: String?) {
        val apps = appInbounds(root)
        if (apps.size() == 0) return
        if (outbounds.objects().none { it.str("tag") == OUT_TAG }) {
            newOutbounds.add(JsonObject().apply { addProperty("tag", OUT_TAG); addProperty("protocol", "dns") })
        }
        top += JsonObject().apply {
            addProperty("type", "field")
            add("inboundTag", apps)
            addProperty("port", "53")
            addProperty("outboundTag", OUT_TAG)
        }
        if (router != null) {
            val block = outbounds.objects().firstOrNull { it.str("protocol") == "blackhole" }?.str("tag")
                ?: BLOCK_TAG.also { tag ->
                    if (newOutbounds.objects().none { it.str("tag") == tag }) {
                        newOutbounds.add(JsonObject().apply { addProperty("tag", tag); addProperty("protocol", "blackhole") })
                    }
                }
            top += JsonObject().apply {
                addProperty("type", "field")
                add("inboundTag", JsonArray().apply { apps.forEach { add(it) } })
                add("ip", JsonArray().apply { add(router) })
                addProperty("port", "853")
                addProperty("outboundTag", block)
            }
        }
    }

    private fun finish(root: JsonObject, outbounds: JsonArray, top: List<JsonObject>, rules: JsonArray) {
        root.add("outbounds", outbounds)
        val routing = root.obj("routing") ?: JsonObject().also { root.add("routing", it) }
        routing.add("rules", JsonArray().apply {
            top.forEach { add(it) }
            rules.forEach { add(it) }
        })
    }

    /** Where the config's DNS asks when it had no servers at all (through the tunnel, like before). */
    private const val FALLBACK_DNS = "1.1.1.1"

    /**
     * What the VPN should give the phone as its DNS server at this start: [router] while the active
     * connection's server has DNS inside the tunnel, otherwise what the user had (the value Tropa
     * replaced, [saved]); null keeps [current] as it is.
     */
    fun vpnDnsFor(serverDns: String?, router: String, current: String?, saved: String?): String? = when {
        serverDns != null -> router.takeIf { current != router }
        current == router -> saved ?: DEFAULT_VPN_DNS
        else -> null
    }

    const val DEFAULT_VPN_DNS = "1.1.1.1"

    private fun fromDns(dnsTag: String) = JsonObject().apply {
        addProperty("type", "field")
        add("inboundTag", JsonArray().apply { add(dnsTag) })
    }

    /** The route for the tunnel resolver, as a setter of outboundTag or balancerTag. */
    private fun routeOf(rules: JsonArray, dnsTag: String, outbounds: JsonArray): ((JsonObject) -> Unit)? {
        val objects = rules.objects()
        val ownDns = objects.firstOrNull { r ->
            r.list("inboundTag").any { it.isJsonPrimitive && it.asString == dnsTag } && !r.has("ip") && !r.has("domain")
        }
        val catchAll = objects.lastOrNull { r -> CONDITIONS.none { r.has(it) } && (r.str("port") == null || r.str("port") == "0-65535") }
        listOfNotNull(ownDns, catchAll).forEach { r ->
            r.str("balancerTag")?.let { tag -> return { it.addProperty("balancerTag", tag) } }
            r.str("outboundTag")?.takeIf { tag -> isProxy(outbounds, tag) }?.let { tag -> return { it.addProperty("outboundTag", tag) } }
        }
        val first = outbounds.objects().firstOrNull()?.takeIf { it.str("protocol") !in NOT_PROXIES }?.str("tag") ?: return null
        return { it.addProperty("outboundTag", first) }
    }

    private fun isProxy(outbounds: JsonArray, tag: String): Boolean =
        outbounds.objects().any { it.str("tag") == tag && it.str("protocol") !in NOT_PROXIES }

    /** The inbounds the apps' traffic arrives at (the tunnel's SOCKS, the user's proxy, a TUN inbound). */
    private fun appInbounds(root: JsonObject): JsonArray = JsonArray().apply {
        root.list("inbounds").objects().filter { it.str("protocol") in APP_INBOUNDS }.mapNotNull { it.str("tag") }.distinct().forEach { add(it) }
    }

    /** Names (not IPs) of the proxy servers: they must resolve without the tunnel. */
    internal fun serverNames(outbounds: JsonArray): List<String> = outbounds.objects()
        .filter { it.str("protocol") !in NOT_PROXIES }
        .flatMap { outbound ->
            val settings = outbound.obj("settings") ?: return@flatMap emptyList()
            val servers = (settings.list("vnext").objects() + settings.list("servers").objects()).ifEmpty { listOf(settings) }
            servers.mapNotNull { it.str("address") } + settings.list("peers").objects().mapNotNull { it.str("endpoint")?.substringBeforeLast(':') }
        }
        .map { it.lowercase(Locale.ROOT) }
        .filter(ServerAddresses::isName)
        .distinct()

    /** Whether the geosite file the core reads has the Russian list (a missing list would stop the core). */
    fun hasRussianGeosite(assetDir: File): Boolean {
        val file = File(assetDir, "geosite.dat")
        if (!file.isFile) return false
        val needle = "CATEGORY-RU".toByteArray()
        return runCatching {
            file.inputStream().buffered(1 shl 16).use { input ->
                // The needle has no repeated prefix: on a mismatch the scan restarts at the current byte.
                var matched = 0
                var b = input.read()
                while (b >= 0) {
                    matched = when {
                        b.toByte() == needle[matched] -> matched + 1
                        b.toByte() == needle[0] -> 1
                        else -> 0
                    }
                    if (matched == needle.size) return@use true
                    b = input.read()
                }
                false
            }
        }.getOrDefault(false)
    }

    private fun JsonElement.obj(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.obj()

    /** A read-only view: an absent or non-array value is an empty array (nothing is added to the object). */
    private fun JsonObject.list(key: String): JsonArray = get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonArray.objects(): List<JsonObject> = mapNotNull { it.obj() }
}
