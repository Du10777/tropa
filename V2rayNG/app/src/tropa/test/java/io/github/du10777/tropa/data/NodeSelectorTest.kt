package io.github.du10777.tropa.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NodePlannerTest {

    private val balancedConfig = """
        {"outbounds":[
          {"tag":"bal-1-vless","protocol":"vless","settings":{"vnext":[{"address":"entry.example.org","port":443}]},
           "streamSettings":{"network":"xhttp","security":"tls"}},
          {"tag":"bal-1-wireguard","protocol":"wireguard","settings":{"peers":[{"endpoint":"127.0.0.1:9000"}]}},
          {"tag":"direct","protocol":"freedom"},{"tag":"block","protocol":"blackhole"}],
         "routing":{"balancers":[{"tag":"balancer","selector":["bal-1-"],"strategy":{"type":"leastLoad"}}]}}
    """.trimIndent()

    @Test
    fun `server-side balancer config gives a balanced plan`() {
        val plan = NodePlanner.fromConfigJson(balancedConfig)
        assertTrue(plan.isBalanced)
        assertEquals("balancer", plan.balancerTag)
        assertEquals(listOf("bal-1-vless", "bal-1-wireguard"), plan.nodes.map { it.tag })
        assertEquals("VLESS · entry.example.org", plan.nodes[0].name)
        assertEquals("entry.example.org:443", plan.nodes[0].address)
        assertEquals("xhttp, tls", plan.nodes[0].transport)
        assertEquals("WireGuard · 127.0.0.1", plan.nodes[1].name)
    }

    @Test
    fun `single proxy config has one node and no balancer`() {
        val plan = NodePlanner.fromConfigJson(
            """{"outbounds":[{"tag":"proxy","protocol":"trojan","settings":{"servers":[{"address":"s.example.org","port":8443}]}},
               {"tag":"direct","protocol":"freedom"}]}"""
        )
        assertFalse(plan.isBalanced)
        assertNull(plan.balancerTag)
        assertEquals(listOf("proxy"), plan.nodes.map { it.tag })
        assertEquals("s.example.org:8443", plan.nodes[0].address)
    }

    @Test
    fun `upstream policy group members are named after their profiles`() {
        val plan = NodePlanner.fromConfigJson(
            """{"outbounds":[
                 {"tag":"proxy-proxy-1-Kazan","protocol":"vless","settings":{"vnext":[{"address":"a.example","port":443}]}},
                 {"tag":"proxy-proxy-2-Warsaw","protocol":"vless","settings":{"vnext":[{"address":"b.example","port":443}]}},
                 {"tag":"direct","protocol":"freedom"}],
               "routing":{"balancers":[{"tag":"balancer-main","selector":["proxy-proxy-"]}]}}"""
        )
        assertEquals("balancer-main", plan.balancerTag)
        assertEquals(listOf("Kazan", "Warsaw"), plan.nodes.map { it.name })
    }

    @Test
    fun `broken json gives an empty plan`() {
        assertTrue(NodePlanner.fromConfigJson("not json").nodes.isEmpty())
    }

    @Test
    fun `a server keeps its fingerprint when its tag and key order change`() {
        val before = NodePlanner.fromConfigJson(balancedConfig).nodes[0]
        val after = NodePlanner.fromConfigJson(
            """{"outbounds":[
                 {"protocol":"vless","streamSettings":{"security":"tls","network":"xhttp"},"tag":"bal-2-vless",
                  "settings":{"vnext":[{"port":443,"address":"entry.example.org"}]}}],
               "routing":{"balancers":[{"tag":"balancer","selector":["bal-2-"]}]}}"""
        ).nodes[0]
        assertEquals(before.fingerprint, after.fingerprint)
        assertTrue(before.fingerprint.isNotEmpty())
    }

    @Test
    fun `fingerprint ignores what the app adds for resolved server names, not the server settings`() {
        fun node(sockopt: String, port: Int) = NodePlanner.fromConfigJson(
            """{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"a.example","port":$port}]},
               "streamSettings":{"network":"tcp","sockopt":$sockopt}}]}"""
        ).nodes[0].fingerprint
        val plain = node("{}", 443)
        assertEquals(plain, node("""{"domainStrategy":"UseIP","happyEyeballs":{"interleave":2}}""", 443))
        assertFalse(plain == node("{}", 8443))
    }

    @Test
    fun `outbound prepared for the running core gets its tag and the running tags of its references`() {
        val json = NodePlanner.prepareOutbound(
            """{"tag":"proxy-2","protocol":"vless","streamSettings":{"sockopt":{"dialerProxy":"proxy-1"}},"proxySettings":{"tag":"fragment"}}""",
            tag = "proxy-2-2",
            renamed = mapOf("proxy-1" to "proxy-7"),
        )
        val root = com.google.gson.JsonParser.parseString(json).asJsonObject
        assertEquals("proxy-2-2", root.get("tag").asString)
        assertEquals("proxy-7", root.getAsJsonObject("streamSettings").getAsJsonObject("sockopt").get("dialerProxy").asString)
        assertEquals("fragment", root.getAsJsonObject("proxySettings").get("tag").asString)
    }

    @Test
    fun `outbound tags include non-proxy outbounds`() {
        assertEquals(setOf("bal-1-vless", "bal-1-wireguard", "direct", "block"), NodePlanner.outboundTags(balancedConfig))
        assertTrue(NodePlanner.outboundTags("not json").isEmpty())
    }
}

class NodeSelectorTest {

    private fun selector() = NodeSelector().apply { reset(listOf("a", "b", "c")) }

    @Test
    fun `retain keeps known servers, also under a new tag, and forgets the gone ones`() {
        val s = selector()
        s.record("a", true, 100)
        s.record("b", true, 200)
        s.record("c", true, 50)
        s.retain(listOf("a", "x", "d"), previous = mapOf("x" to "b"))
        assertEquals(100L, s.stats("a")?.median())
        assertEquals(200L, s.stats("x")?.median())
        assertEquals(0, s.stats("d")?.checks)
        assertNull(s.stats("b"))
        assertNull(s.stats("c"))
        assertEquals("a", s.bestAlive())
    }

    @Test
    fun `best alive server has the lowest median latency`() {
        val s = selector()
        s.record("a", true, 300)
        s.record("b", true, 120)
        s.record("c", false, null)
        assertEquals("b", s.bestAlive())
    }

    @Test
    fun `dead active server is replaced at once`() {
        val s = selector()
        s.record("a", true, 100)
        s.record("b", true, 200)
        assertEquals(NodeSelector.Decision.Keep, s.evaluate("a"))
        s.record("a", false, null)
        assertEquals(NodeSelector.Decision.Keep, s.evaluate("a"), "one failure is not enough")
        s.record("a", false, null)
        assertEquals(NodeSelector.Decision.SwitchTo("b", NodeSelector.Reason.ACTIVE_DEAD), s.evaluate("a"))
    }

    @Test
    fun `everything dead is reported`() {
        val s = selector()
        repeat(2) { listOf("a", "b", "c").forEach { tag -> s.record(tag, false, null) } }
        assertEquals(NodeSelector.Decision.AllDead, s.evaluate("a"))
    }

    @Test
    fun `noticeably better server wins only after three rounds in a row`() {
        val s = selector()
        s.record("a", true, 400)
        s.record("b", true, 150)
        assertEquals(NodeSelector.Decision.Keep, s.evaluate("a"))
        assertEquals(NodeSelector.Decision.Keep, s.evaluate("a"))
        assertEquals(NodeSelector.Decision.SwitchTo("b", NodeSelector.Reason.BETTER), s.evaluate("a"))
    }

    @Test
    fun `small gains do not cause switching`() {
        val s = selector()
        s.record("a", true, 100)
        s.record("b", true, 60) // 40 % faster but only 40 ms
        repeat(5) { assertEquals(NodeSelector.Decision.Keep, s.evaluate("a")) }
    }

    @Test
    fun `a streak is broken by a slow round`() {
        val s = selector()
        s.record("a", true, 400)
        s.record("b", true, 150)
        s.evaluate("a")
        s.evaluate("a")
        repeat(5) { s.record("b", true, 900) } // b got slow: median 900
        assertEquals(NodeSelector.Decision.Keep, s.evaluate("a"))
    }

    @Test
    fun `reconnect pauses grow and stay at 30 seconds`() {
        assertEquals(listOf(3L, 5L, 10L, 20L, 30L, 30L, 30L), (1..7).map(NodeSelector::backoffSeconds))
    }
}
