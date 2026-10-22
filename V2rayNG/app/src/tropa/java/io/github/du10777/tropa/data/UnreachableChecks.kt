package io.github.du10777.tropa.data

import com.v2ray.ang.handler.MmkvManager
import java.security.MessageDigest

/**
 * When a server that stays down was last probed directly for a block (see the watchdog): once a day per
 * server and the IP its name connects to; a new LastIP may be worth a look at once.
 */
object UnreachableChecks {
    const val EVERY_MS = 24 * 60 * 60_000L
    private const val PREFIX = "tropa_unreachable_"

    fun key(fingerprint: String, ip: String): String =
        MessageDigest.getInstance("SHA-256").digest("$fingerprint|$ip".toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    /** Due, and marked as done now when it is. */
    @Synchronized
    fun take(key: String, now: Long = System.currentTimeMillis()): Boolean {
        val last = MmkvManager.decodeSettingsString(PREFIX + key)?.toLongOrNull() ?: 0L
        if (now - last < EVERY_MS && now >= last) return false
        MmkvManager.encodeSettings(PREFIX + key, now.toString())
        return true
    }
}
