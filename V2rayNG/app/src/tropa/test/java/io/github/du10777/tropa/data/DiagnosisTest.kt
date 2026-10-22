package io.github.du10777.tropa.data

import io.github.du10777.tropa.data.Diagnosis.Group
import io.github.du10777.tropa.data.Diagnosis.Probe
import io.github.du10777.tropa.data.Diagnosis.Reach
import io.github.du10777.tropa.data.Diagnosis.Resource
import io.github.du10777.tropa.data.Diagnosis.Verdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagnosisTest {
    private val server = Resource(Group.SERVER, "node.example.com", 443, "https://node.example.com:443/", anyCertificate = true)
    private val ya = Resource(Group.WHITELIST, "ya.ru", 443, "https://ya.ru")
    private val google = Resource(Group.FOREIGN, "www.google.com", 443, "https://www.google.com/generate_204")

    private val up = Probe(ping = true, port = true, web = true)
    private val down = Probe(ping = false, port = false, web = null)
    private val limited = Probe(ping = true, port = false, web = null)

    @Test
    fun `a resource is reachable through its port and web, a ping alone is a limit`() {
        assertEquals(Reach.AVAILABLE, up.reach)
        assertEquals(Reach.AVAILABLE, Probe(ping = false, port = true, web = true).reach) // pings are often dropped
        assertEquals(Reach.AVAILABLE, Probe(ping = false, port = true, web = null).reach) // no web to ask
        assertEquals(Reach.RESTRICTED, Probe(ping = true, port = true, web = false).reach)
        assertEquals(Reach.RESTRICTED, limited.reach)
        assertEquals(Reach.UNAVAILABLE, down.reach)
        assertEquals("ping ✓ port ✓ web ✗", Probe(ping = true, port = true, web = false).describe())
    }

    @Test
    fun `verdicts follow what answered, servers first`() {
        assertEquals(Verdict.SERVERS_REACHABLE, Diagnosis.verdict(mapOf(server to up, ya to down, google to down), null))
        assertEquals(Verdict.SERVERS_BLOCKED, Diagnosis.verdict(mapOf(server to limited, ya to up, google to up), null))
        assertEquals(Verdict.WHITELIST, Diagnosis.verdict(mapOf(server to down, ya to up, google to limited), null))
        assertEquals(Verdict.NO_NETWORK, Diagnosis.verdict(mapOf(server to down, ya to down, google to down), gateway = false))
        assertEquals(Verdict.NO_INTERNET, Diagnosis.verdict(mapOf(server to down, ya to down, google to down), gateway = true))
        // A mobile link has no gateway to ask: the network is there, the internet is not.
        assertEquals(Verdict.NO_INTERNET, Diagnosis.verdict(mapOf(server to down, ya to down, google to down), gateway = null))
        assertTrue(Diagnosis.offline(Verdict.NO_NETWORK))
        assertFalse(Diagnosis.offline(Verdict.WHITELIST))
    }

    @Test
    fun `the summary counts each group`() {
        val result = Diagnosis.Result(Verdict.WHITELIST, mapOf(server to limited, ya to up, google to down), gateway = null)
        assertEquals("servers 0/1 (limited 1), white list 1/1, foreign 0/1", result.summary())
    }

    @Test
    fun `servers become resources with their TLS name, local ones are skipped`() {
        val vless = Node(
            tag = "proxy", name = "Kazan", protocol = "VLESS", address = "node.example.com:443", transport = "xhttp, tls",
            outbound = """{"protocol":"vless","settings":{"address":"node.example.com","port":443},
                "streamSettings":{"network":"xhttp","security":"tls","tlsSettings":{"serverName":"front.example.com"}}}""",
        )
        val freeturn = Node(tag = "wg", name = "", protocol = "WireGuard", address = "127.0.0.1:9000", transport = null, outbound = "{}")
        val reality = Node(
            tag = "r", name = "", protocol = "VLESS", address = "203.0.113.5:443", transport = "tcp, reality",
            outbound = """{"streamSettings":{"security":"reality"}}""",
        )
        val resources = Diagnosis.resources(listOf(vless, freeturn, reality), listOf("https://ya.ru", "https://vk.com"))
        val servers = resources.filter { it.group == Group.SERVER }
        assertEquals(2, servers.size)
        assertEquals("https://front.example.com:443/", servers[0].url)
        assertEquals("Kazan", servers[0].label)
        assertTrue(servers[0].anyCertificate)
        assertNull(servers[1].url) // no TLS of its own: ping and port only
        assertEquals(listOf("ya.ru", "vk.com"), resources.filter { it.group == Group.WHITELIST }.map { it.host })
        assertEquals(Diagnosis.FOREIGN_URLS.size, resources.count { it.group == Group.FOREIGN })
    }

    @Test
    fun `addresses split into host and port`() {
        assertEquals("node.example.com" to 443, Diagnosis.splitHostPort("node.example.com:443"))
        assertEquals("2001:db8::1" to 8443, Diagnosis.splitHostPort("[2001:db8::1]:8443"))
        assertEquals("node.example.com" to null, Diagnosis.splitHostPort("node.example.com"))
        assertTrue(Diagnosis.isLocal("127.0.0.1"))
        assertTrue(Diagnosis.isLocal("10.0.10.1"))
        assertTrue(Diagnosis.isLocal("router.lan"))
        assertFalse(Diagnosis.isLocal("203.0.113.5"))
        assertFalse(Diagnosis.isLocal("node.example.com"))
    }

    @Test
    fun `probes run at once except on a weak mobile signal`() {
        assertTrue(Diagnosis.parallel(Cellular.State(cellular = false)))
        assertTrue(Diagnosis.parallel(Cellular.State(cellular = true, signalLevel = 3)))
        assertTrue(Diagnosis.parallel(Cellular.State(cellular = true, signalLevel = null)))
        assertFalse(Diagnosis.parallel(Cellular.State(cellular = true, signalLevel = 2)))
    }
}
