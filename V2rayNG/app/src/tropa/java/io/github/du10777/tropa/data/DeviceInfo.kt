package io.github.du10777.tropa.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

/** What a subscription server learns about the device (X-HWID, X-Device-OS, X-Ver-OS, X-Device-Model). */
object DeviceInfo {
    /**
     * A stable device ID. Android keeps ANDROID_ID per device, user and signing key, so it survives
     * reinstalling Tropa (not a factory reset); only its hash leaves the phone.
     */
    @SuppressLint("HardwareIds")
    fun hwid(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        return MessageDigest.getInstance("SHA-256").digest("tropa:$androidId".toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }
    }

    const val OS = "Android"

    val osVersion: String get() = ascii(Build.VERSION.RELEASE)

    val model: String get() = ascii("${Build.MANUFACTURER} ${Build.MODEL}".trim())

    /** HTTP headers take printable ASCII only. */
    private fun ascii(value: String): String = value.map { if (it in ' '..'~') it else '?' }.joinToString("").take(64)
}
