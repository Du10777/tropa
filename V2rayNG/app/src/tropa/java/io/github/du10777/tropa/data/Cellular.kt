package io.github.du10777.tropa.data

import android.content.Context
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/**
 * The mobile side of the network under the VPN, read without permissions where Android allows it:
 * the countries (MCC) of the network and of the SIM, and the generation of the mobile data network
 * (the exact type on Android 13+, or on 7–12 with the phone permission; otherwise the system's
 * speed estimate).
 */
object Cellular {
    enum class Generation { G2, G3, G4, G5, UNKNOWN }

    data class State(
        val cellular: Boolean,
        val networkMcc: String? = null,
        val homeMcc: String? = null,
        val generation: Generation = Generation.UNKNOWN,
        /** Signal bars 0–4 (Android 9+), null when unknown. */
        val signalLevel: Int? = null,
    ) {
        /** Roaming is another country: a foreign network inside the home country does not count. */
        val roaming: Boolean get() = cellular && networkMcc != null && homeMcc != null && networkMcc != homeMcc

        /** Mobile data in the home country. */
        val home: Boolean get() = cellular && networkMcc != null && networkMcc == homeMcc

        /** Slower than 4G: the checks pause. */
        val weak: Boolean get() = cellular && (generation == Generation.G2 || generation == Generation.G3)
    }

    /** Below this system estimate (kbit/s) the network counts as slower than 4G (LTE is estimated ~30000, HSPA+ ~13000). */
    private const val LTE_ESTIMATE_KBPS = 15_000

    /** Set on the test bench, which has no SIM: a root-only broadcast to TropaServiceEventsReceiver. */
    @Volatile
    var override: State? = null

    fun read(context: Context, caps: NetworkCapabilities?): State {
        override?.let { return it }
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) != true) return State(cellular = false)
        val telephony = dataTelephony(context)
        val generation = telephony?.let { tm -> runCatching { generationOf(tm.dataNetworkType) }.getOrNull() }
            ?.takeIf { it != Generation.UNKNOWN }
            ?: caps.linkDownstreamBandwidthKbps.takeIf { it > 0 }?.let { if (it >= LTE_ESTIMATE_KBPS) Generation.G4 else Generation.G3 }
            ?: Generation.UNKNOWN
        return State(
            cellular = true,
            networkMcc = mccOf(telephony?.networkOperator),
            homeMcc = mccOf(telephony?.simOperator),
            generation = generation,
            signalLevel = telephony?.let { tm ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) runCatching { tm.signalStrength?.level }.getOrNull() else null
            },
        )
    }

    /** The first three digits of an operator code (MCC + MNC). */
    internal fun mccOf(operator: String?): String? = operator?.takeIf { it.length >= 5 && it.all(Char::isDigit) }?.substring(0, 3)

    internal fun generationOf(type: Int): Generation = when (type) {
        TelephonyManager.NETWORK_TYPE_NR -> Generation.G5
        // LTE_CA (19) is hidden in the SDK; IWLAN is mobile data over Wi-Fi.
        TelephonyManager.NETWORK_TYPE_LTE, TelephonyManager.NETWORK_TYPE_IWLAN, 19 -> Generation.G4
        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_EHRPD, TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> Generation.G3

        TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT, TelephonyManager.NETWORK_TYPE_IDEN, TelephonyManager.NETWORK_TYPE_GSM -> Generation.G2

        else -> Generation.UNKNOWN
    }

    /** The SIM that carries mobile data (dual-SIM phones). */
    private fun dataTelephony(context: Context): TelephonyManager? {
        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return null
        val id = SubscriptionManager.getDefaultDataSubscriptionId()
        return if (id != SubscriptionManager.INVALID_SUBSCRIPTION_ID) telephony.createForSubscriptionId(id) else telephony
    }
}
