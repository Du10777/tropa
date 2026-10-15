package io.github.du10777.tropa.data

import com.v2ray.ang.dto.entities.RulesetItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoutingPresetTest {
    private val torrents = RulesetItem(remarks = "Bypass bittorrent", outboundTag = "direct", protocol = listOf("bittorrent"))
    private val russia = RulesetItem(remarks = "Bypass Russia IP", outboundTag = "direct", ip = listOf("geoip:ru"))

    @Test
    fun `torrents stay in the vpn`() {
        assertEquals(listOf(russia), RoutingPreset.withoutTorrents(listOf(torrents, russia)))
    }

    @Test
    fun `rules are the same whatever ids the routing screen gave them`() {
        assertTrue(RoutingPreset.sameRules(listOf(russia.copy(id = "6f1c")), listOf(russia)))
        assertFalse(RoutingPreset.sameRules(listOf(russia.copy(enabled = false)), listOf(russia)))
        assertFalse(RoutingPreset.sameRules(listOf(russia), listOf(russia, torrents)))
    }
}

class BypassAppsTest {
    @Test
    fun `preset adds the installed apps that are not in the list yet`() {
        val add = BypassApps.toAdd(
            installed = setOf("ru.rostel", "ru.sberbankmobile", "org.telegram.messenger"),
            current = setOf("ru.rostel"),
        )
        assertEquals(listOf("ru.sberbankmobile"), add)
    }

    @Test
    fun `preset has no duplicates`() {
        assertEquals(BypassApps.BANKS_AND_STATE.size, BypassApps.BANKS_AND_STATE.toSet().size)
    }
}
