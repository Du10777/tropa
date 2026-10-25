package io.github.du10777.tropa.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MeteredWifiTest {
    private fun ip(vararg parts: Int) = parts.map { it.toByte() }.toByteArray()

    @Test
    fun `the subnet of an address`() {
        assertEquals("192.168.1.0/24", MeteredWifi.subnet(ip(192, 168, 1, 37), 24))
        assertEquals("172.20.10.0/28", MeteredWifi.subnet(ip(172, 20, 10, 5), 28))
        assertEquals("10.162.233.64/27", MeteredWifi.subnet(ip(10, 162, 233, 77), 27))
        assertEquals("0.0.0.0/0", MeteredWifi.subnet(ip(10, 1, 2, 3), 0))
        assertEquals("10.1.2.3/32", MeteredWifi.subnet(ip(10, 1, 2, 3), 32))
    }

    @Test
    fun `the key does not depend on the order of DNS servers and differs between networks`() {
        val home = MeteredWifi.key("192.168.1.0/24", "192.168.1.1", listOf("192.168.1.1", "8.8.8.8"), "lan")
        assertEquals(home, MeteredWifi.key("192.168.1.0/24", "192.168.1.1", listOf("8.8.8.8", "192.168.1.1"), "lan"))
        assertNotEquals(home, MeteredWifi.key("192.168.1.0/24", "192.168.1.1", listOf("192.168.1.1"), "lan"))
        assertNotEquals(home, MeteredWifi.key("192.168.0.0/24", "192.168.0.1", listOf("192.168.1.1", "8.8.8.8"), "lan"))
        assertEquals(24, home.length)
        assertFalse(home.contains("192"))
    }

    @Test
    fun `a metered Wi-Fi saves traffic until the person answers, then as answered`() {
        val metered = MeteredWifi.Wifi("k", metered = true)
        val plain = MeteredWifi.Wifi("k", metered = false)
        assertTrue(MeteredWifi.economy(metered, null))
        assertTrue(MeteredWifi.shouldAsk(metered, null))
        assertFalse(MeteredWifi.economy(metered, false))
        assertFalse(MeteredWifi.shouldAsk(metered, false))
        assertTrue(MeteredWifi.economy(metered, true))
        assertFalse(MeteredWifi.shouldAsk(metered, true))
        // An unmetered Wi-Fi is not asked about; marked limited by hand, it saves too.
        assertFalse(MeteredWifi.economy(plain, null))
        assertFalse(MeteredWifi.shouldAsk(plain, null))
        assertTrue(MeteredWifi.economy(plain, true))
        // Not a Wi-Fi (mobile data has its own rules).
        assertFalse(MeteredWifi.economy(null, null))
        assertFalse(MeteredWifi.shouldAsk(null, null))
    }

    @Test
    fun `a network is shown by its subnet, an iPhone's hotspot named`() {
        assertEquals("192.168.43.0/24", MeteredWifi.describe("192.168.43.0/24"))
        assertEquals("172.20.10.0/28 (iPhone)", MeteredWifi.describe("172.20.10.0/28"))
        assertEquals(null, MeteredWifi.describe(""))
        assertEquals(null, MeteredWifi.describe(null))
    }
}
