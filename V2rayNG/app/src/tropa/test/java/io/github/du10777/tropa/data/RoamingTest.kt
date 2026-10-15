package io.github.du10777.tropa.data

import android.telephony.TelephonyManager
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CellularTest {
    @Test
    fun `roaming is another country, not another operator of the same country`() {
        assertTrue(Cellular.State(cellular = true, networkMcc = "244", homeMcc = "250").roaming)
        assertFalse(Cellular.State(cellular = true, networkMcc = "250", homeMcc = "250").roaming)
        assertTrue(Cellular.State(cellular = true, networkMcc = "250", homeMcc = "250").home)
        // Wi-Fi abroad is not roaming; unknown countries are not either.
        assertFalse(Cellular.State(cellular = false, networkMcc = "244", homeMcc = "250").roaming)
        assertFalse(Cellular.State(cellular = true, networkMcc = null, homeMcc = "250").roaming)
    }

    @Test
    fun `slower than 4G on mobile data is weak`() {
        assertTrue(Cellular.State(cellular = true, generation = Cellular.Generation.G3).weak)
        assertTrue(Cellular.State(cellular = true, generation = Cellular.Generation.G2).weak)
        assertFalse(Cellular.State(cellular = true, generation = Cellular.Generation.G4).weak)
        assertFalse(Cellular.State(cellular = true, generation = Cellular.Generation.UNKNOWN).weak)
        assertFalse(Cellular.State(cellular = false, generation = Cellular.Generation.G2).weak)
    }

    @Test
    fun `network types map to generations`() {
        assertEquals(Cellular.Generation.G5, Cellular.generationOf(TelephonyManager.NETWORK_TYPE_NR))
        assertEquals(Cellular.Generation.G4, Cellular.generationOf(TelephonyManager.NETWORK_TYPE_LTE))
        assertEquals(Cellular.Generation.G4, Cellular.generationOf(19))
        assertEquals(Cellular.Generation.G3, Cellular.generationOf(TelephonyManager.NETWORK_TYPE_HSPAP))
        assertEquals(Cellular.Generation.G2, Cellular.generationOf(TelephonyManager.NETWORK_TYPE_EDGE))
        assertEquals(Cellular.Generation.UNKNOWN, Cellular.generationOf(TelephonyManager.NETWORK_TYPE_UNKNOWN))
    }

    @Test
    fun `country code is the first three digits of the operator`() {
        assertEquals("250", Cellular.mccOf("25001"))
        assertEquals("310", Cellular.mccOf("310260"))
        assertNull(Cellular.mccOf(""))
        assertNull(Cellular.mccOf(null))
    }
}

class RoamingTest {
    private val abroad = Cellular.State(cellular = true, networkMcc = "244", homeMcc = "250")

    @Test
    fun `traffic stops abroad until the user agrees for that country`() {
        assertTrue(Roaming.mustBlock(stop = true, state = abroad, allowed = ""))
        assertFalse(Roaming.mustBlock(stop = true, state = abroad, allowed = "244"))
        assertTrue(Roaming.mustBlock(stop = true, state = abroad.copy(networkMcc = "246"), allowed = "244"))
        assertFalse(Roaming.mustBlock(stop = false, state = abroad, allowed = ""))
        assertFalse(Roaming.mustBlock(stop = true, state = abroad.copy(networkMcc = "250"), allowed = ""))
    }

    @Test
    fun `blocked config sends everything into a blackhole and pings nothing`() {
        val config = """{"outbounds":[{"tag":"proxy","protocol":"vless"},{"tag":"direct","protocol":"freedom"}],
            "routing":{"rules":[{"type":"field","domain":["geosite:category-ru"],"outboundTag":"direct"}],
            "balancers":[{"tag":"balancer","selector":["proxy"]}]},
            "burstObservatory":{"subjectSelector":["proxy"]}}"""
        val root = JsonParser.parseString(Roaming.blockAll(config)).asJsonObject
        val outbounds = root.getAsJsonArray("outbounds")
        assertEquals(Roaming.BLOCK_TAG, outbounds[0].asJsonObject.get("tag").asString)
        assertEquals("blackhole", outbounds[0].asJsonObject.get("protocol").asString)
        assertEquals(3, outbounds.size())
        val rules = root.getAsJsonObject("routing").getAsJsonArray("rules")
        assertEquals(1, rules.size())
        assertEquals(Roaming.BLOCK_TAG, rules[0].asJsonObject.get("outboundTag").asString)
        assertFalse(root.has("burstObservatory"))
        // Blocking twice does not add a second blackhole.
        assertEquals(3, JsonParser.parseString(Roaming.blockAll(Roaming.blockAll(config))).asJsonObject.getAsJsonArray("outbounds").size())
    }
}
