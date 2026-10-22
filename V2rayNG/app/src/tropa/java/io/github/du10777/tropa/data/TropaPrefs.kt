package io.github.du10777.tropa.data

import com.v2ray.ang.handler.MmkvManager

/**
 * Tropa settings. They live in the upstream settings storage (multi-process MMKV), so the UI and
 * the VPN service process see the same values; every key carries the `tropa_` prefix.
 */
object TropaPrefs {
    private const val LOCALE_INITIALIZED = "tropa_locale_initialized"
    private const val ADVANCED_MODE = "tropa_advanced_mode"
    private const val ACTIVE_CONNECTION = "tropa_active_connection"
    private const val CONNECTED_SINCE = "tropa_connected_since"
    private const val HANDLED_CLIP = "tropa_handled_clip"
    private const val PERMISSION_ASKED = "tropa_permission_asked_"
    private const val PERMISSION_BLOCKED = "tropa_permission_blocked_"
    private const val SUBSCRIPTION_INFO = "tropa_subscription_info_"
    private const val REPORT_URL = "tropa_report_url_"
    private const val ANNOUNCE = "tropa_announce_"
    private const val ANNOUNCE_DISMISSED = "tropa_announce_dismissed_"
    private const val SUPPORT_URL = "tropa_support_url_"
    private const val WEB_PAGE_URL = "tropa_web_page_url_"
    private const val SUBSCRIPTION_HASH = "tropa_subscription_hash_"
    private const val SERVER_CHECKS = "tropa_server_checks_"
    private const val SERVER_DNS = "tropa_server_dns_"

    var localeInitialized: Boolean
        get() = MmkvManager.decodeSettingsBool(LOCALE_INITIALIZED, false)
        set(value) {
            MmkvManager.encodeSettings(LOCALE_INITIALIZED, value)
        }

    var advancedMode: Boolean
        get() = MmkvManager.decodeSettingsBool(ADVANCED_MODE, false)
        set(value) {
            MmkvManager.encodeSettings(ADVANCED_MODE, value)
        }

    var activeConnectionId: String?
        get() = MmkvManager.decodeSettingsString(ACTIVE_CONNECTION)?.takeIf { it.isNotEmpty() }
        set(value) {
            MmkvManager.encodeSettings(ACTIVE_CONNECTION, value.orEmpty())
        }

    /** Wall-clock time the tunnel came up, 0 while it is down. */
    var connectedSince: Long
        get() = MmkvManager.decodeSettingsLong(CONNECTED_SINCE, 0L)
        set(value) {
            MmkvManager.encodeSettings(CONNECTED_SINCE, value)
        }

    /** Fingerprint of the clipboard text that was already added or dismissed. */
    var handledClip: String
        get() = MmkvManager.decodeSettingsString(HANDLED_CLIP).orEmpty()
        set(value) {
            MmkvManager.encodeSettings(HANDLED_CLIP, value)
        }

    /** Whether the explanation for a permission was already shown automatically (any answer). */
    fun isPermissionAsked(id: String): Boolean = MmkvManager.decodeSettingsBool(PERMISSION_ASKED + id, false)

    fun setPermissionAsked(id: String) {
        MmkvManager.encodeSettings(PERMISSION_ASKED + id, true)
    }

    /** Whether the system stopped showing its own dialog, so only the settings page can grant it. */
    fun isPermissionBlocked(id: String): Boolean = MmkvManager.decodeSettingsBool(PERMISSION_BLOCKED + id, false)

    fun setPermissionBlocked(id: String, blocked: Boolean) {
        MmkvManager.encodeSettings(PERMISSION_BLOCKED + id, blocked)
    }

    /** Raw `Subscription-Userinfo` header of a connection, as received on its last download. */
    fun subscriptionInfo(connectionId: String): String? =
        MmkvManager.decodeSettingsString(SUBSCRIPTION_INFO + connectionId)?.takeIf { it.isNotEmpty() }

    /** Where problem reports of a connection go (`Tropa-Report-Url` subscription header). */
    fun reportUrl(connectionId: String): String? =
        MmkvManager.decodeSettingsString(REPORT_URL + connectionId)?.takeIf { it.startsWith("https://") || it.startsWith("http://") }

    fun setReportUrl(connectionId: String, value: String?) {
        MmkvManager.encodeSettings(REPORT_URL + connectionId, value.orEmpty())
    }

    fun setSubscriptionInfo(connectionId: String, value: String?) {
        MmkvManager.encodeSettings(SUBSCRIPTION_INFO + connectionId, value.orEmpty())
    }

    /** Hash of the last downloaded subscription body: an unchanged one is not imported again. */
    fun subscriptionHash(connectionId: String): String? = MmkvManager.decodeSettingsString(SUBSCRIPTION_HASH + connectionId)

    fun setSubscriptionHash(connectionId: String, value: String) {
        MmkvManager.encodeSettings(SUBSCRIPTION_HASH + connectionId, value)
    }

    /** Check parameters from the `Tropa-Checks` header of a connection, normalized (CheckParams JSON). */
    fun serverChecks(connectionId: String): String? = MmkvManager.decodeSettingsString(SERVER_CHECKS + connectionId)?.takeIf { it.isNotEmpty() }

    fun setServerChecks(connectionId: String, value: String?) {
        MmkvManager.encodeSettings(SERVER_CHECKS + connectionId, value.orEmpty())
    }

    /** The tunnel DNS a connection's server announced (`Tropa-Dns`, a checked `tcp://name:port`), null without it. */
    fun serverDns(connectionId: String): String? = MmkvManager.decodeSettingsString(SERVER_DNS + connectionId)?.takeIf { it.isNotEmpty() }

    fun setServerDns(connectionId: String, value: String?) {
        MmkvManager.encodeSettings(SERVER_DNS + connectionId, value.orEmpty())
    }

    /** `Announce` subscription header (already decoded): a message from the server to its users. */
    fun announce(connectionId: String): String? = MmkvManager.decodeSettingsString(ANNOUNCE + connectionId)?.takeIf { it.isNotBlank() }

    fun setAnnounce(connectionId: String, value: String?) {
        MmkvManager.encodeSettings(ANNOUNCE + connectionId, value.orEmpty())
    }

    /** The announce the user closed (its hash): a new text shows again. */
    fun dismissedAnnounce(connectionId: String): String? = MmkvManager.decodeSettingsString(ANNOUNCE_DISMISSED + connectionId)

    fun setDismissedAnnounce(connectionId: String, value: String?) {
        MmkvManager.encodeSettings(ANNOUNCE_DISMISSED + connectionId, value.orEmpty())
    }

    /** `Support-Url` and `Profile-Web-Page-Url` subscription headers: web, Telegram or mail links only. */
    fun supportUrl(connectionId: String): String? = link(SUPPORT_URL + connectionId)

    fun webPageUrl(connectionId: String): String? = link(WEB_PAGE_URL + connectionId)

    fun setLinks(connectionId: String, support: String?, webPage: String?) {
        MmkvManager.encodeSettings(SUPPORT_URL + connectionId, support?.trim().orEmpty())
        MmkvManager.encodeSettings(WEB_PAGE_URL + connectionId, webPage?.trim().orEmpty())
    }

    private fun link(key: String): String? = MmkvManager.decodeSettingsString(key)
        ?.takeIf { url -> listOf("https://", "http://", "tg://", "mailto:").any { url.startsWith(it, ignoreCase = true) } }
}
