package io.github.du10777.tropa.data

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NodeChecksTest {
    @Test
    fun `a server is alive with 64 KB of the right stream, whatever the speed`() {
        val full = SelfTestResult.parse("""{"status":200,"latencyMs":40,"totalMs":140,"bytes":131072,"verified":true,"complete":true}""")
        assertTrue(full.alive)
        assertEquals(140L, full.fetchMs())
        // Time ran out after 80 KB: alive, and the 128 KB time is estimated from the rate.
        val slow = SelfTestResult(status = 200, latencyMs = 100, totalMs = 5100, bytes = 80 * 1024, verified = true, error = "context deadline exceeded")
        assertTrue(slow.alive)
        assertEquals(100L + 5000L * 128 / 80, slow.fetchMs())
        // Frozen after 16 KB: not alive, however quick the first bytes were.
        val frozen = SelfTestResult(status = 200, latencyMs = 50, totalMs = 5050, bytes = 16 * 1024, verified = true, error = "context deadline exceeded")
        assertFalse(frozen.alive)
        assertNull(frozen.fetchMs())
        assertFalse(frozen.maybeUnsupported) // slow: the server did not deliver
    }

    @Test
    fun `other bytes or a quick failure may be a node without the self-test`() {
        val stub = SelfTestResult(status = 200, latencyMs = 30, totalMs = 35, bytes = 0, verified = false, error = "unexpected content (not the self-test stream)")
        assertFalse(stub.alive)
        assertTrue(stub.wrongContent)
        assertTrue(stub.maybeUnsupported)
        val eof = SelfTestResult(totalMs = 300, error = "Get \"http://selftest.tropa.internal/\": EOF")
        assertTrue(eof.maybeUnsupported)
        val timeout = SelfTestResult(totalMs = 5000, error = "context deadline exceeded")
        assertFalse(timeout.maybeUnsupported)
        assertEquals("empty result", SelfTestResult.parse(null).error)
        assertEquals("empty result", SelfTestResult.parse("not json").error)
    }

    @Test
    fun `speed in kilobits and its label`() {
        val r = SpeedResult.parse("""{"ok":true,"latencyMs":30,"bytes":5000000,"ms":2000}""")
        assertEquals(20_000L, r.kbps)
        assertEquals("20.0 Mbit/s", SpeedResult.label(20_000))
        assertEquals("640 kbit/s", SpeedResult.label(640))
        assertNull(SpeedResult(ok = false, bytes = 10, ms = 5).kbps)
    }

    @Test
    fun `a node without the self-test is asked again after some hours`() {
        var now = 1_000_000L
        val support = SelfTestSupport { now }
        assertEquals(SelfTestSupport.State.UNKNOWN, support.state("a"))
        assertTrue(support.no("a"))
        assertFalse(support.no("a")) // not news
        assertEquals(SelfTestSupport.State.NO, support.state("a"))
        now += SelfTest.RETRY_UNSUPPORTED_MS + 1
        assertEquals(SelfTestSupport.State.UNKNOWN, support.state("a"))
        assertTrue(support.yes("a"))
        assertFalse(support.yes("a"))
        assertEquals(SelfTestSupport.State.YES, support.state("a"))
        // A node that lost its self-test goes back to the classic checks.
        assertTrue(support.no("a"))
        assertEquals(SelfTestSupport.State.NO, support.state("a"))
    }

    @Test
    fun `traffic of the user is what the core counted minus the app's own checks`() {
        val watch = TrafficWatch()
        watch.reset(TrafficWatch.parse("""{"proxy":{"up":1000,"down":10000,"conns":2,"fails":0}}"""), 0)
        watch.ownBytes("proxy", 131072)
        val counters = TrafficWatch.parse("""{"proxy":{"up":5000,"down":400000,"conns":6,"fails":1},"direct":{"down":99}}""")
        val peek = watch.peek(counters, 30_000)
        val delta = peek.getValue("proxy")
        assertEquals(400000L - 10000 - 131072, delta.down)
        assertEquals(4L, delta.conns)
        assertTrue(delta.carries)
        assertEquals((400000L - 10000 - 131072) * 8 / 30_000, delta.kbps)
        // peek does not move the start; look does.
        assertEquals(delta, watch.look(counters, 30_000).getValue("proxy"))
        val quiet = watch.look(counters, 60_000).getValue("proxy")
        assertEquals(0L, quiet.down)
        assertFalse(quiet.carries)
    }

    @Test
    fun `mostly failing connections are worth a check even while data comes`() {
        val failing = TrafficWatch.Delta(down = 500_000, up = 0, conns = 4, fails = 3, ms = 5000)
        assertTrue(failing.failing)
        assertFalse(failing.carries)
        assertFalse(TrafficWatch.Delta(down = 500_000, up = 0, conns = 20, fails = 3, ms = 5000).failing)
        assertFalse(TrafficWatch.Delta(down = 0, up = 0, conns = 2, fails = 2, ms = 5000).failing)
    }

    @Test
    fun `a restarted core counts from zero again`() {
        val watch = TrafficWatch()
        watch.reset(mapOf("proxy" to NodeCounter(down = 900_000, conns = 50)), 0)
        val delta = watch.look(mapOf("proxy" to NodeCounter(down = 70_000, conns = 3)), 10_000).getValue("proxy")
        assertEquals(70_000L, delta.down)
        assertEquals(3L, delta.conns)
    }

    @Test
    fun `stats are switched on and the policy levels stay`() {
        val config = """{"policy":{"levels":{"8":{"handshake":4}}},"outbounds":[]}"""
        val root = JsonParser.parseString(TrafficWatch.enableStats(config)).asJsonObject
        assertTrue(root.get("stats").isJsonObject)
        val policy = root.getAsJsonObject("policy")
        assertEquals(4, policy.getAsJsonObject("levels").getAsJsonObject("8").get("handshake").asInt)
        assertTrue(policy.getAsJsonObject("system").get("statsOutboundDownlink").asBoolean)
        assertTrue(policy.getAsJsonObject("system").get("statsOutboundUplink").asBoolean)
        assertEquals("not json", TrafficWatch.enableStats("not json"))
    }

    @Test
    fun `servers checked by the self-test are compared by fetch time, mixed ones by latency`() {
        val s = NodeSelector()
        s.reset(listOf("a", "b"))
        // a: quick first byte, slow 128 KB; b: slower first byte, fast 128 KB.
        s.record("a", true, 40, 400)
        s.record("b", true, 60, 150)
        assertEquals("b", s.bestAlive())
        assertEquals(150L, s.rankMs("b"))
        // b turns out to have no self-test: like is compared with like, by latency.
        s.record("b", true, 60, null)
        assertEquals("a", s.bestAlive())
        assertEquals(60L, s.rankMs("b"))
    }

    @Test
    fun `the user's traffic keeps a server alive without a time sample`() {
        val s = NodeSelector()
        s.reset(listOf("a"))
        s.record("a", false, null)
        assertTrue(s.stats("a")!!.dead)
        s.record("a", true, null)
        assertTrue(s.stats("a")!!.alive)
        assertNull(s.stats("a")!!.median())
    }
}
