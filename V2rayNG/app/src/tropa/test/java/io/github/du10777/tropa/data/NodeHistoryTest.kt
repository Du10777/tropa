package io.github.du10777.tropa.data

import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NodeHistoryTest {
    private var now = 1_000_000_000L
    private fun selector() = NodeSelector(now = { now })

    @Test
    fun `history goes into the next session and ranks, but does not make a server alive`() {
        val first = selector()
        first.reset(listOf("a"))
        first.record("a", true, 40, 150)
        first.record("a", true, 50, 160)
        first.addConnections("a", conns = 10, fails = 1)
        first.stats("a")!!.speedKbps = 20_000
        val saved = Gson().fromJson(Gson().toJson(first.snapshot("a")), NodeHistory::class.java)

        now += 60_000
        val next = selector()
        next.reset(listOf("b-new-tag"))
        next.restore("b-new-tag", saved)
        val stats = next.stats("b-new-tag")!!
        assertFalse(stats.alive) // a cold start always checks
        assertEquals(50L, stats.median())
        assertEquals(160L, stats.fetchMedian())
        assertEquals(20_000L, stats.speedKbps)
        assertEquals(10L to 1L, stats.connections())
        // 2 answered checks + 9 good connections of 12.
        assertEquals(11.0 / 12, stats.reliability(), 1e-9)
    }

    @Test
    fun `history older than the week window is gone`() {
        val first = selector()
        first.reset(listOf("a"))
        first.record("a", true, 40, null)
        first.addConnections("a", conns = 5, fails = 0)
        val saved = first.snapshot("a")!!
        now += 8 * 24 * 60 * 60_000L
        val next = selector()
        next.reset(listOf("a"))
        next.restore("a", saved)
        assertTrue(next.snapshot("a")!!.isEmpty)
    }

    @Test
    fun `new connections do not move to a server that fails noticeably more often`() {
        val s = NodeSelector(betterRounds = 1, now = { now })
        s.reset(listOf("active", "fast"))
        s.record("active", true, 200)
        s.record("fast", true, 100)
        s.addConnections("fast", conns = 20, fails = 8) // 60 % of its connections fail
        assertEquals(NodeSelector.Decision.Keep, s.evaluate("active"))
        // Once it is as reliable as the active one, it wins.
        s.addConnections("fast", conns = 200, fails = 0)
        assertEquals(NodeSelector.Decision.SwitchTo("fast", NodeSelector.Reason.BETTER), s.evaluate("active"))
    }
}
