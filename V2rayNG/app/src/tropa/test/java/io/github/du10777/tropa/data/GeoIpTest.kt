package io.github.du10777.tropa.data

import com.v2ray.ang.dto.entities.RulesetItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File

class GeoIpTest {

    private fun ByteArrayOutputStream.varint(value: Int) {
        var v = value
        while (v >= 0x80) {
            write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        write(v)
    }

    private fun ByteArrayOutputStream.bytesField(field: Int, data: ByteArray) {
        varint(field shl 3 or 2)
        varint(data.size)
        write(data)
    }

    private fun cidr(ip: ByteArray, prefix: Int) = ByteArrayOutputStream().apply {
        bytesField(1, ip)
        varint(2 shl 3)
        varint(prefix)
    }.toByteArray()

    private fun geoip(code: String, vararg cidrs: ByteArray) = ByteArrayOutputStream().apply {
        bytesField(1, code.toByteArray())
        cidrs.forEach { bytesField(2, it) }
    }.toByteArray()

    @Test
    fun `entries and subnets are read from a geoip dat file`(@TempDir dir: File) {
        val file = File(dir, "geoip.dat")
        file.writeBytes(ByteArrayOutputStream().apply {
            bytesField(1, geoip("RU", cidr(byteArrayOf(5, 8, 0, 0), 16), cidr(byteArrayOf(77, 88, 0, 0), 18)))
            bytesField(1, geoip("PRIVATE", cidr(byteArrayOf(10, 0, 0, 0), 8)))
        }.toByteArray())

        assertEquals(listOf(GeoIpEntry("RU", 2), GeoIpEntry("PRIVATE", 1)), GeoIpFile.entries(file))
        assertEquals(listOf("5.8.0.0/16", "77.88.0.0/18"), GeoIpFile.subnets(file, "ru"))
        assertEquals(listOf("10.0.0.0/8"), GeoIpFile.subnets(file, "private"))
    }

    @Test
    fun `routing rules keep their order, apps, ports and the catch-all`() {
        val rules = GeoRouting.rules(
            """{"outbounds":[{"tag":"proxy","protocol":"vless"},{"tag":"direct","protocol":"freedom"},
                 {"tag":"block","protocol":"blackhole"},{"tag":"dns-out","protocol":"dns"}],
               "routing":{"rules":[
                 {"inboundTag":["api"],"outboundTag":"api"},
                 {"port":53,"outboundTag":"dns-out"},
                 {"process":["com.freeturn.app"],"outboundTag":"direct"},
                 {"ip":["geoip:private","ext:x2c/geoip-ru.dat:ru","1.2.3.0/24"],"outboundTag":"direct"},
                 {"domain":["geosite:category-ads"],"port":"443","outboundTag":"block"},
                 {"network":"tcp,udp","balancerTag":"balancer"}]}}"""
        )
        assertEquals(
            listOf(
                RouteRule("direct", apps = listOf("com.freeturn.app")),
                RouteRule("direct", addresses = listOf("geoip:private", "ext:x2c/geoip-ru.dat:ru", "1.2.3.0/24")),
                RouteRule("block", addresses = listOf("geosite:category-ads"), ports = "443"),
                RouteRule("proxy"),
            ),
            rules,
        )
        assertEquals(listOf(false, false, false, true), rules.map { it.catchAll })
    }

    @Test
    fun `upstream rulesets become rules when enabled`() {
        val rules = GeoRouting.fromRulesets(
            listOf(
                RulesetItem(outboundTag = "direct", ip = listOf("geoip:private"), domain = listOf("geosite:private")),
                RulesetItem(outboundTag = "block", domain = listOf("geosite:category-ads-all"), enabled = false),
                RulesetItem(outboundTag = "proxy", port = "0-65535"),
            )
        )
        assertEquals(
            listOf(
                RouteRule("direct", addresses = listOf("geoip:private", "geosite:private")),
                RouteRule("proxy", ports = "0-65535"),
            ),
            rules,
        )
    }

    @Test
    fun `geoip references point to files and codes`() {
        assertEquals(GeoRef("geoip.dat", "ru"), GeoRouting.parseRef("geoip:ru"))
        assertEquals(GeoRef("x2c/geoip-ru.dat", "ru"), GeoRouting.parseRef("ext:x2c/geoip-ru.dat:ru"))
        assertNull(GeoRouting.parseRef("geosite:category-ru"))
        assertNull(GeoRouting.parseRef("1.2.3.0/24"))
    }
}
