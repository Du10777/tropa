package io.github.du10777.tropa.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.InetAddress

/** A country (or list) inside a geoip.dat file. */
data class GeoIpEntry(val code: String, val count: Int)

/**
 * Reads Xray geoip.dat files (protobuf GeoIPList) without extra libraries:
 * GeoIPList { repeated GeoIP entry = 1 }, GeoIP { string country_code = 1; repeated CIDR cidr = 2 },
 * CIDR { bytes ip = 1; uint32 prefix = 2 }.
 */
object GeoIpFile {

    fun entries(file: File): List<GeoIpEntry> {
        val data = file.readBytes()
        val result = ArrayList<GeoIpEntry>()
        forEachEntry(data) { start, end ->
            var code = ""
            var count = 0
            forEachField(data, start, end) { field, valueStart, valueEnd ->
                when (field) {
                    1 -> code = String(data, valueStart, valueEnd - valueStart, Charsets.UTF_8)
                    2 -> count++
                }
            }
            result += GeoIpEntry(code, count)
        }
        return result
    }

    /** Subnets of one code (case-insensitive), as text like 5.8.0.0/16. */
    fun subnets(file: File, code: String): List<String> {
        val data = file.readBytes()
        val result = ArrayList<String>()
        forEachEntry(data) { start, end ->
            var entryCode = ""
            val cidrs = ArrayList<Pair<Int, Int>>()
            forEachField(data, start, end) { field, valueStart, valueEnd ->
                when (field) {
                    1 -> entryCode = String(data, valueStart, valueEnd - valueStart, Charsets.UTF_8)
                    2 -> cidrs += valueStart to valueEnd
                }
            }
            if (entryCode.equals(code, ignoreCase = true)) {
                cidrs.forEach { (cidrStart, cidrEnd) -> result += cidrText(data, cidrStart, cidrEnd) }
            }
        }
        return result
    }

    private fun cidrText(data: ByteArray, start: Int, end: Int): String {
        var ip = ByteArray(0)
        var prefix = 0
        forEachField(data, start, end) { field, valueStart, valueEnd ->
            when (field) {
                1 -> ip = data.copyOfRange(valueStart, valueEnd)
                2 -> prefix = valueStart // varint value is passed as start for varint fields
            }
        }
        val address = runCatching { InetAddress.getByAddress(ip).hostAddress }.getOrDefault("?")
        return "$address/$prefix"
    }

    private inline fun forEachEntry(data: ByteArray, block: (Int, Int) -> Unit) {
        forEachField(data, 0, data.size) { field, start, end -> if (field == 1) block(start, end) }
    }

    /**
     * Walks protobuf fields in [from, to). Length-delimited fields report their byte range; varint
     * fields report the decoded value as the start (and end = -1).
     */
    private inline fun forEachField(data: ByteArray, from: Int, to: Int, block: (Int, Int, Int) -> Unit) {
        var pos = from
        while (pos < to) {
            var key = 0L
            var shift = 0
            while (true) {
                val b = data[pos++].toInt()
                key = key or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            val field = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> {
                    var value = 0L
                    shift = 0
                    while (true) {
                        val b = data[pos++].toInt()
                        value = value or ((b and 0x7F).toLong() shl shift)
                        if (b and 0x80 == 0) break
                        shift += 7
                    }
                    block(field, value.toInt(), -1)
                }

                2 -> {
                    var length = 0
                    shift = 0
                    while (true) {
                        val b = data[pos++].toInt()
                        length = length or ((b and 0x7F) shl shift)
                        if (b and 0x80 == 0) break
                        shift += 7
                    }
                    block(field, pos, pos + length)
                    pos += length
                }

                5 -> pos += 4
                1 -> pos += 8
                else -> return
            }
        }
    }
}

/** Where the routing of a config sends some addresses: "direct", "proxy" or "block". */
data class GeoRule(val target: String, val items: List<String>)

/** A geoip reference resolved to a file and a code: geoip:ru → geoip.dat/ru, ext:a/b.dat:ru. */
data class GeoRef(val file: String, val code: String)

object GeoRouting {
    private val DIRECT = setOf("freedom")
    private val BLOCK = setOf("blackhole")

    /** Address lists (geoip, ext files, subnets, geosite) of the routing rules, grouped by target. */
    fun rules(configJson: String): List<GeoRule> {
        val root = runCatching { JsonParser.parseString(configJson).asJsonObject }.getOrNull() ?: return emptyList()
        val protocols = root.getAsJsonArray("outbounds")
            ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            ?.associate { it.str("tag").orEmpty() to it.str("protocol").orEmpty() }
            .orEmpty()
        val grouped = LinkedHashMap<String, MutableList<String>>()
        root.getAsJsonObject("routing")?.getAsJsonArray("rules")?.forEach { element ->
            val rule = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val outbound = rule.str("outboundTag")
            val target = when {
                rule.str("balancerTag") != null -> "proxy"
                protocols[outbound] in DIRECT -> "direct"
                protocols[outbound] in BLOCK -> "block"
                else -> "proxy"
            }
            val items = listOf("ip", "domain").flatMap { key ->
                rule.getAsJsonArray(key)?.mapNotNull { it.takeIf { v -> v.isJsonPrimitive }?.asString }.orEmpty()
            }
            if (items.isNotEmpty()) grouped.getOrPut(target) { mutableListOf() }.addAll(items)
        }
        return grouped.map { (target, items) -> GeoRule(target, items.distinct()) }
    }

    fun parseRef(item: String): GeoRef? = when {
        item.startsWith("geoip:", ignoreCase = true) -> GeoRef("geoip.dat", item.substringAfter(':'))
        item.startsWith("ext:", ignoreCase = true) -> {
            val rest = item.substring(4)
            val file = rest.substringBeforeLast(':')
            val code = rest.substringAfterLast(':')
            if (file.isNotEmpty() && code.isNotEmpty() && "geoip" in file.lowercase()) GeoRef(file, code) else null
        }

        else -> null
    }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
