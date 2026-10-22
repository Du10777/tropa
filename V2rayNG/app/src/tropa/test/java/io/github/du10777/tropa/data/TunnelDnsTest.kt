package io.github.du10777.tropa.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TunnelDnsTest {
    private val server = "tcp://dns.tropa.internal:53"

    /** Like a 3X-UI JSON subscription: DNS 8.8.8.8 through the proxy, a catch-all to "proxy". */
    private val jsonSub = """{
        "dns":{"servers":[{"address":"8.8.8.8"}],"queryStrategy":"UseIP","tag":"dns_out"},
        "inbounds":[{"tag":"socks","protocol":"socks","port":30000}],
        "outbounds":[{"tag":"proxy","protocol":"vless","settings":{"address":"node.example.com","port":443}},
                     {"tag":"direct","protocol":"freedom"},{"tag":"block","protocol":"blackhole"}],
        "routing":{"rules":[{"type":"field","process":["com.freeturn.app"],"outboundTag":"direct"},
                            {"type":"field","network":"tcp,udp","outboundTag":"proxy"}]}}"""

    private fun patched(config: String, geosite: Boolean = true): JsonObject =
        JsonParser.parseString(TunnelDns.patchConfig(config, server, geosite)).asJsonObject

    @Test
    fun `Russian names and server names go to the local resolver, the rest into the tunnel, the old DNS stays as fallback`() {
        val root = patched(jsonSub)
        val dns = root.getAsJsonObject("dns")
        assertEquals("dns_out", dns.get("tag").asString)
        val servers = dns.getAsJsonArray("servers")
        val local = servers[0].asJsonObject
        assertEquals(TunnelDns.LOCAL_DNS, local.get("address").asString)
        val domains = local.getAsJsonArray("domains").map { it.asString }
        assertTrue(domains.containsAll(listOf("domain:ru", "domain:su", "domain:xn--p1ai", "geosite:category-ru", "full:node.example.com")))
        assertTrue(local.get("skipFallback").asBoolean)
        assertEquals(server, servers[1].asJsonObject.get("address").asString)
        assertEquals("8.8.8.8", servers[2].asJsonObject.get("address").asString)
        assertEquals("UseIP", dns.get("queryStrategy").asString)
    }

    @Test
    fun `the rules send the local resolver direct, the tunnel resolver through the proxy, the apps' DNS into the core`() {
        val root = patched(jsonSub)
        val rules = root.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        assertEquals(listOf("77.88.8.8"), rules[0].getAsJsonArray("ip").map { it.asString })
        assertEquals("direct", rules[0].get("outboundTag").asString)
        assertEquals(listOf("dns_out"), rules[0].getAsJsonArray("inboundTag").map { it.asString })
        assertEquals(listOf("full:dns.tropa.internal"), rules[1].getAsJsonArray("domain").map { it.asString })
        assertEquals("proxy", rules[1].get("outboundTag").asString)
        assertEquals(listOf("socks"), rules[2].getAsJsonArray("inboundTag").map { it.asString })
        assertEquals("53", rules[2].get("port").asString)
        assertEquals(TunnelDns.OUT_TAG, rules[2].get("outboundTag").asString)
        // The old rules follow untouched.
        assertEquals(listOf("com.freeturn.app"), rules[3].getAsJsonArray("process").map { it.asString })
        assertFalse(rules[4].has("inboundTag"))
        val outbounds = root.getAsJsonArray("outbounds").map { it.asJsonObject }
        assertEquals(1, outbounds.count { it.get("tag").asString == TunnelDns.OUT_TAG && it.get("protocol").asString == "dns" })
        assertEquals(1, outbounds.count { it.get("tag").asString == "direct" })
    }

    @Test
    fun `a balancer config routes the tunnel resolver like its own DNS, through the balancer`() {
        val config = """{
            "dns":{"servers":["1.1.1.1"],"tag":"dns-module","enableParallelQuery":true},
            "inbounds":[{"tag":"socks","protocol":"socks"},{"tag":"tropa-local","protocol":"socks"}],
            "outbounds":[{"tag":"proxy-1","protocol":"vless","settings":{"vnext":[{"address":"a.example.com","port":443}]}},
                         {"tag":"proxy-2","protocol":"trojan","settings":{"servers":[{"address":"203.0.113.9","port":443}]}}],
            "routing":{"balancers":[{"tag":"bal","selector":["proxy-"]}],
                       "rules":[{"type":"field","inboundTag":["dns-module"],"balancerTag":"bal"},
                                {"type":"field","port":"0-65535","balancerTag":"bal"}]}}"""
        val root = patched(config, geosite = false)
        val rules = root.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        assertEquals("bal", rules[1].get("balancerTag").asString)
        assertEquals(listOf("socks", "tropa-local"), rules[2].getAsJsonArray("inboundTag").map { it.asString })
        // No freedom outbound in the config: one is added for the local resolver.
        assertEquals(TunnelDns.DIRECT_TAG, rules[0].get("outboundTag").asString)
        val dns = root.getAsJsonObject("dns")
        assertFalse(dns.has("enableParallelQuery"))
        val domains = dns.getAsJsonArray("servers")[0].asJsonObject.getAsJsonArray("domains").map { it.asString }
        assertFalse("geosite:category-ru" in domains) // the file lacks the list: the core must still start
        assertTrue("full:a.example.com" in domains)
        assertFalse(domains.any { "203.0.113.9" in it })
        assertEquals("1.1.1.1", dns.getAsJsonArray("servers")[2].asString)
    }

    @Test
    fun `fake DNS configs and other junk are left alone`() {
        val fake = """{"dns":{"servers":["fakedns","1.1.1.1"]},"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"address":"x.example.com"}}]}"""
        assertEquals(fake, TunnelDns.patchConfig(fake, server, true))
        assertEquals("not json", TunnelDns.patchConfig("not json", server, true))
        val noProxy = """{"outbounds":[{"tag":"direct","protocol":"freedom"}]}"""
        assertEquals(noProxy, TunnelDns.patchConfig(noProxy, server, true))
    }

    @Test
    fun `with the router address as the phone's DNS, DoT to it is refused and plain DNS is captured`() {
        val root = JsonParser.parseString(TunnelDns.patchConfig(jsonSub, server, true, router = "10.10.14.2")).asJsonObject
        val rules = root.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        val dot = rules.first { it.has("ip") && it.getAsJsonArray("ip").map { ip -> ip.asString } == listOf("10.10.14.2") }
        assertEquals("853", dot.get("port").asString)
        assertEquals("block", dot.get("outboundTag").asString) // the config's own blackhole
        assertTrue(rules.indexOf(dot) < rules.indexOfFirst { it.has("process") })
    }

    @Test
    fun `without the server's DNS the capture stays while the VPN still gives the router address`() {
        val root = JsonParser.parseString(TunnelDns.patchConfig(jsonSub, null, false, router = "10.10.14.2")).asJsonObject
        val servers = root.getAsJsonObject("dns").getAsJsonArray("servers")
        assertEquals(1, servers.size())
        assertEquals("8.8.8.8", servers[0].asJsonObject.get("address").asString) // the config's DNS answers
        val rules = root.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        assertEquals(TunnelDns.OUT_TAG, rules[0].get("outboundTag").asString)
        assertEquals("853", rules[1].get("port").asString)
        assertEquals(jsonSub, TunnelDns.patchConfig(jsonSub, null, false, router = null))
        // A config without any DNS gets one, through the tunnel as before.
        val bare = """{"inbounds":[{"tag":"socks","protocol":"socks"}],"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"address":"x.example.com"}}]}"""
        val patchedBare = JsonParser.parseString(TunnelDns.patchConfig(bare, null, false, router = "10.10.14.2")).asJsonObject
        assertEquals("1.1.1.1", patchedBare.getAsJsonObject("dns").getAsJsonArray("servers")[0].asString)
        assertTrue(patchedBare.getAsJsonArray("outbounds").any { it.asJsonObject.get("tag").asString == TunnelDns.BLOCK_TAG })
    }

    @Test
    fun `the VPN's DNS follows the server, and the user's value comes back`() {
        val router = "10.10.14.2"
        assertEquals(router, TunnelDns.vpnDnsFor(server, router, current = "1.1.1.1", saved = null))
        assertNull(TunnelDns.vpnDnsFor(server, router, current = router, saved = "9.9.9.9")) // already set
        assertEquals("9.9.9.9", TunnelDns.vpnDnsFor(null, router, current = router, saved = "9.9.9.9"))
        assertEquals("1.1.1.1", TunnelDns.vpnDnsFor(null, router, current = router, saved = null))
        assertNull(TunnelDns.vpnDnsFor(null, router, current = "8.8.8.8", saved = null)) // the user's own value
    }

    @Test
    fun `only a tcp name and port is taken from the header`() {
        assertEquals("tcp://dns.tropa.internal:53", TunnelDns.parse(" tcp://DNS.tropa.internal:53 "))
        assertNull(TunnelDns.parse("https://dns.example.com/dns-query"))
        assertNull(TunnelDns.parse("tcp://dns.tropa.internal:99999"))
        assertNull(TunnelDns.parse("tcp://evil host:53"))
        assertNull(TunnelDns.parse(""))
        assertNull(TunnelDns.parse(null))
    }

    @Test
    fun `the Russian list is found in geosite dat across buffer edges`(@TempDir dir: File) {
        assertFalse(TunnelDns.hasRussianGeosite(dir))
        val bytes = ByteArray(200_000) { (it % 61 + 1).toByte() } + "CCATEGORY-RU".toByteArray() + ByteArray(10)
        File(dir, "geosite.dat").writeBytes(bytes)
        assertTrue(TunnelDns.hasRussianGeosite(dir))
        File(dir, "geosite.dat").writeBytes(ByteArray(100_000) { 'C'.code.toByte() } + "CATEGORY-R".toByteArray())
        assertFalse(TunnelDns.hasRussianGeosite(dir))
    }
}
