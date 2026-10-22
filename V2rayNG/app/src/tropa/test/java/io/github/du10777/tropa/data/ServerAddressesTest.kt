package io.github.du10777.tropa.data

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerAddressesTest {
    private val lastIps = mapOf("node.example.com" to "203.0.113.7")
    private fun pinned(config: String) = JsonParser.parseString(ServerAddresses.pinConfig(config) { lastIps[it] }).asJsonObject

    @Test
    fun `a name with a LastIP connects to the IP, TLS and the HTTP host keep the name`() {
        val root = pinned(
            """{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"address":"node.example.com","port":443},
            "streamSettings":{"network":"xhttp","security":"tls","tlsSettings":{},"xhttpSettings":{"path":"/xh","host":""}}},
            {"tag":"direct","protocol":"freedom","settings":{}}]}""",
        )
        val proxy = root.getAsJsonArray("outbounds")[0].asJsonObject
        assertEquals("203.0.113.7", proxy.getAsJsonObject("settings").get("address").asString)
        val stream = proxy.getAsJsonObject("streamSettings")
        assertEquals("node.example.com", stream.getAsJsonObject("tlsSettings").get("serverName").asString)
        assertEquals("node.example.com", stream.getAsJsonObject("xhttpSettings").get("host").asString)
    }

    @Test
    fun `names the config sets itself stay, vnext and websocket too`() {
        val root = pinned(
            """{"outbounds":[{"tag":"p","protocol":"vless","settings":{"vnext":[{"address":"node.example.com","port":443,"users":[]}]},
            "streamSettings":{"network":"ws","security":"tls","tlsSettings":{"serverName":"front.example.com"},"wsSettings":{"path":"/w"}}}]}""",
        )
        val proxy = root.getAsJsonArray("outbounds")[0].asJsonObject
        assertEquals("203.0.113.7", proxy.getAsJsonObject("settings").getAsJsonArray("vnext")[0].asJsonObject.get("address").asString)
        val stream = proxy.getAsJsonObject("streamSettings")
        assertEquals("front.example.com", stream.getAsJsonObject("tlsSettings").get("serverName").asString)
        assertEquals("node.example.com", stream.getAsJsonObject("wsSettings").get("host").asString)
    }

    @Test
    fun `IPs, unknown names and other outbounds are left alone`() {
        val config = """{"outbounds":[{"tag":"a","protocol":"vless","settings":{"address":"198.51.100.1","port":443}},
            {"tag":"b","protocol":"vless","settings":{"address":"other.example.com","port":443}},
            {"tag":"wg","protocol":"wireguard","settings":{"peers":[{"endpoint":"node.example.com:51820"}]}}]}"""
        assertEquals(config, ServerAddresses.pinConfig(config) { lastIps[it] })
        assertEquals("not json", ServerAddresses.pinConfig("not json") { lastIps[it] })
    }

    @Test
    fun `only real names are pinned`() {
        assertTrue(ServerAddresses.isName("node.example.com"))
        assertFalse(ServerAddresses.isName("203.0.113.7"))
        assertFalse(ServerAddresses.isName("2001:db8::1"))
        assertFalse(ServerAddresses.isName("localhost"))
        assertFalse(ServerAddresses.isName("router.lan"))
    }
}
