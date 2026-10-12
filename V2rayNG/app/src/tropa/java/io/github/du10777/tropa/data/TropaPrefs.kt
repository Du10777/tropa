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

    fun setSubscriptionInfo(connectionId: String, value: String?) {
        MmkvManager.encodeSettings(SUBSCRIPTION_INFO + connectionId, value.orEmpty())
    }
}
