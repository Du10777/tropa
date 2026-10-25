package io.github.du10777.tropa.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class CheckParamsTest {
    private val full = """
        {"latencyUrls": ["https://check.example.com/generate_204"], "downloadUrls": ["https://check.example.com/64k"],
         "directUrls": ["https://ya.ru"], "activeIntervalSec": 45, "othersIntervalSec": 240, "downloadIntervalSec": 1800,
         "screenOffIntervalSec": 600, "latencyTimeoutSec": 8, "downloadTimeoutSec": 20, "downloadMinKb": 128,
         "failThreshold": 3, "betterPercent": 20, "betterMinMs": 80, "betterRounds": 4}
    """.trimIndent()

    @Test
    fun `plain and base64 headers give the same parameters`() {
        val plain = CheckParams.parse(full)!!
        val encoded = CheckParams.parse("base64:" + Base64.getEncoder().encodeToString(full.toByteArray()))
        assertEquals(plain, encoded)
        assertEquals(listOf("https://check.example.com/generate_204"), plain.latencyUrls)
        assertEquals(45, plain.activeIntervalSec)
        assertEquals(128, plain.downloadMinKb)
        assertEquals(4, plain.betterRounds)
    }

    @Test
    fun `numbers are moved into their ranges, junk is ignored`() {
        val p = CheckParams.parse(
            """{"activeIntervalSec": 1, "othersIntervalSec": 99999, "betterPercent": "25", "failThreshold": true,
               "latencyTimeoutSec": 2.6, "downloadMinKb": 1e30, "betterRounds": null, "somethingNew": 5}""",
        )!!
        assertEquals(10, p.activeIntervalSec)
        assertEquals(3600, p.othersIntervalSec)
        assertEquals(25, p.betterPercent)
        assertNull(p.failThreshold)
        assertEquals(3, p.latencyTimeoutSec)
        assertEquals(512, p.downloadMinKb)
        assertNull(p.betterRounds)
    }

    @Test
    fun `only public http addresses are kept, five at most`() {
        val p = CheckParams.parse(
            """{"directUrls": ["ftp://example.com/x", "http://192.168.1.1/", "http://localhost/", "https://router.lan/",
               "https://router/", "https://10.0.0.1/x", "https://[::1]/", "https://[fd00::1]/", "http://100.64.1.1/",
               "https://8.8.8.8/x", "https://example.com", "https://example.com", "https://a.example.com",
               "https://b.example.com", "https://c.example.com", "https://d.example.com", 7]}""",
        )!!
        assertEquals(
            listOf("https://8.8.8.8/x", "https://example.com", "https://a.example.com", "https://b.example.com", "https://c.example.com"),
            p.directUrls,
        )
        assertTrue(CheckParams.isPublicUrl("https://[2606:4700:4700::1111]/"))
        assertFalse(CheckParams.isPublicUrl("https://printer.local/"))
        assertFalse(CheckParams.isPublicUrl("https://example.com/a b"))
    }

    @Test
    fun `nothing usable gives null`() {
        listOf(null, "", "not json", "[1, 2]", "{}", """{"latencyUrls": []}""", """{"directUrls": ["http://10.1.1.1/"]}""", "base64:!!!")
            .forEach { assertNull(CheckParams.parse(it), "$it") }
    }

    @Test
    fun `the user wins over the server, the server over the defaults`() {
        val server = CheckParams(latencyUrls = listOf("https://check.example.com/"), activeIntervalSec = 45, othersIntervalSec = 240)
        val user = CheckParams(activeIntervalSec = 60)
        val values = user.over(server.over(CheckSettings.DEFAULTS))
        assertEquals(60, values.activeIntervalSec)
        assertEquals(240, values.othersIntervalSec)
        assertEquals(listOf("https://check.example.com/"), values.latencyUrls)
        assertEquals(CheckSettings.DEFAULTS.downloadUrls, values.downloadUrls)
        assertEquals(CheckSettings.DEFAULTS.failThreshold, values.failThreshold)
        assertEquals(CheckSettings.DEFAULTS, CheckParams().over(CheckSettings.DEFAULTS))
        assertEquals(0.85, CheckSettings.DEFAULTS.betterRatio, 1e-9)
        assertEquals(65536L, CheckSettings.DEFAULTS.downloadMinBytes)
    }

    @Test
    fun `the stored form reads back the same and is described for the log`() {
        val p = CheckParams.parse(full)!!
        assertEquals(p, CheckParams.parse(p.toJson()))
        val small = CheckParams(latencyUrls = listOf("https://a.example.com", "https://b.example.com"), activeIntervalSec = 45)
        assertEquals("latencyUrls 2, activeIntervalSec 45", small.describe())
        assertEquals(CheckSource.USER, CheckSettings.source(60, 45))
        assertEquals(CheckSource.SERVER, CheckSettings.source(null, 45))
        assertEquals(CheckSource.DEFAULT, CheckSettings.source(null, null))
    }

    @Test
    fun `the selector follows new thresholds`() {
        val s = NodeSelector().apply { reset(listOf("a", "b")) }
        s.record("a", true, 100)
        s.record("b", true, 120)
        s.record("a", false, null)
        assertFalse(s.isActiveDead("a"))
        s.tune(CheckParams(failThreshold = 1).over(CheckSettings.DEFAULTS))
        assertTrue(s.isActiveDead("a"))
        assertEquals(NodeSelector.Decision.SwitchTo("b", NodeSelector.Reason.ACTIVE_DEAD), s.evaluate("a"))
    }

    @Test
    fun `the user's numbers are moved into their ranges and the base ones are not kept`() {
        val base = CheckSettings.DEFAULTS
        val kept = CheckSettings.userNumbers(
            mapOf(
                "othersIntervalSec" to 600,      // changed
                "failThreshold" to 50,           // above 1..10: moved to 10
                "betterPercent" to base.betterPercent, // the same as the base: follows the base
                "downloadMinKb" to null,         // left empty: follows the base
                "activeIntervalSec" to 99,       // not one of "More parameters"
            ),
            base,
        )
        assertEquals(600, kept.othersIntervalSec)
        assertEquals(10, kept.failThreshold)
        assertEquals(null, kept.betterPercent)
        assertEquals(null, kept.downloadMinKb)
        assertEquals(null, kept.activeIntervalSec)
        val values = kept.over(base)
        assertEquals(600, values.number("othersIntervalSec"))
        assertEquals(base.betterRounds, values.number("betterRounds"))
    }
}
