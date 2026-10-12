package io.github.du10777.tropa.data

/**
 * Base64 that accepts both the standard and the URL-safe alphabet, with or without padding.
 * Pure Kotlin, so link parsing stays testable on the JVM and works on every Android version.
 */
object Base64Codec {
    private const val URL_SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val decodeTable = IntArray(128) { -1 }.also { table ->
        URL_SAFE.forEachIndexed { index, c -> table[c.code] = index }
        table['+'.code] = 62
        table['/'.code] = 63
    }

    /** Decodes [input]; whitespace and padding are ignored. Returns null for invalid input. */
    fun decode(input: String): ByteArray? {
        val clean = input.filterNot { it.isWhitespace() }.trimEnd('=')
        if (clean.isEmpty() || clean.length % 4 == 1) return null
        val out = ArrayList<Byte>(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val value = if (c.code < 128) decodeTable[c.code] else -1
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add((buffer shr bits and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    /** URL-safe encoding without padding. */
    fun encodeUrlSafe(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
            sb.append(URL_SAFE[b0 shr 2])
            sb.append(URL_SAFE[((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            if (b1 >= 0) sb.append(URL_SAFE[((b1 and 0x0F) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)])
            if (b2 >= 0) sb.append(URL_SAFE[b2 and 0x3F])
            i += 3
        }
        return sb.toString()
    }
}
