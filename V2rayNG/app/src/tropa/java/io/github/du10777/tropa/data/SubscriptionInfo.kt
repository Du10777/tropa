package io.github.du10777.tropa.data

import java.util.Locale

/** Traffic and expiry from the `Subscription-Userinfo` header (bytes, unix seconds). */
data class SubscriptionInfo(
    val upload: Long?,
    val download: Long?,
    val total: Long?,
    val expire: Long?,
) {
    val used: Long get() = (upload ?: 0L) + (download ?: 0L)

    /** Share of the traffic limit already used, null when there is no limit. */
    val usedFraction: Float?
        get() = total?.takeIf { it > 0 }?.let { (used.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }

    companion object {
        fun parse(value: String?): SubscriptionInfo? {
            if (value.isNullOrBlank()) return null
            val fields = value.split(';').mapNotNull { part ->
                val pair = part.split('=', limit = 2)
                if (pair.size == 2) pair[0].trim().lowercase(Locale.ROOT) to pair[1].trim() else null
            }.toMap()

            fun number(key: String): Long? = fields[key]?.toDoubleOrNull()?.toLong()?.takeIf { it >= 0 }

            val info = SubscriptionInfo(number("upload"), number("download"), number("total"), number("expire"))
            return info.takeIf { it.upload != null || it.download != null || it.total != null || it.expire != null }
        }

        /** `Profile-Title` may be plain text or `base64:<utf-8 text>`. */
        fun decodeTitle(raw: String?): String? {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return null
            if (!value.startsWith("base64:", ignoreCase = true)) return value
            return Base64Codec.decode(value.substring(7))?.toString(Charsets.UTF_8)?.trim()?.ifEmpty { null }
        }
    }
}
