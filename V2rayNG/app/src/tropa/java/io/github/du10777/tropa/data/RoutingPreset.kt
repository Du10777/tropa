package io.github.du10777.tropa.data

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.RulesetItem
import com.v2ray.ang.enums.RoutingType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils

/**
 * Routing of connections made of share links (JSON configs bring their own): Russian sites and
 * addresses go directly, the rest through the VPN. Upstream starts with its Chinese preset; Tropa
 * puts upstream's Russian one in its place once, and only over rules nobody has changed. Torrents
 * stay in the VPN (the Russian preset sends them directly). The DNS for direct sites becomes
 * Yandex instead of AliDNS.
 */
object RoutingPreset {
    private const val APPLIED_KEY = "tropa_routing_ru_applied"
    private const val YANDEX_DNS = "77.88.8.8"

    fun applyOnce(context: Context) {
        if (MmkvManager.decodeSettingsBool(APPLIED_KEY, false)) return
        MmkvManager.encodeSettings(APPLIED_KEY, true)
        val current = MmkvManager.decodeRoutingRulesets()
        val chinese = preset(context, RoutingType.WHITE) ?: return
        if (!current.isNullOrEmpty() && !sameRules(current, chinese)) return
        val russian = preset(context, RoutingType.WHITE_RUSSIA)?.let(::withoutTorrents)?.takeIf { it.isNotEmpty() } ?: return
        MmkvManager.encodeRoutingRulesets(russian.toMutableList())
        val dns = MmkvManager.decodeSettingsString(AppConfig.PREF_DOMESTIC_DNS)
        if (dns.isNullOrEmpty() || dns == AppConfig.DNS_DIRECT) MmkvManager.encodeSettings(AppConfig.PREF_DOMESTIC_DNS, YANDEX_DNS)
    }

    /** Upstream's Russian preset sends BitTorrent directly; Tropa keeps it in the VPN. */
    fun withoutTorrents(rules: List<RulesetItem>): List<RulesetItem> =
        rules.filterNot { rule -> rule.protocol?.contains("bittorrent") == true }

    /** The same rules; the ids the upstream routing screen gives them do not count. */
    fun sameRules(a: List<RulesetItem>, b: List<RulesetItem>): Boolean =
        a.map { it.copy(id = "") } == b.map { it.copy(id = "") }

    private fun preset(context: Context, type: RoutingType): List<RulesetItem>? =
        Utils.readTextFromAssets(context, type.fileName).takeIf { it.isNotEmpty() }
            ?.let { JsonUtil.fromJsonSafe(it, Array<RulesetItem>::class.java)?.toList() }
}
