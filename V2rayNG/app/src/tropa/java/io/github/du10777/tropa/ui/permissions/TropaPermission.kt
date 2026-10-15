package io.github.du10777.tropa.ui.permissions

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.v2ray.ang.R
import io.github.du10777.tropa.data.CheckSettings
import io.github.du10777.tropa.data.TropaPrefs
import io.github.du10777.tropa.service.WatchStateStore

/** Permissions Tropa explains before asking for them (see the permissions screen). */
enum class TropaPermission(
    val id: String,
    @StringRes val title: Int,
    @StringRes val reason: Int,
) {
    VPN("vpn", R.string.tropa_perm_vpn, R.string.tropa_perm_vpn_reason),
    NOTIFICATIONS("notifications", R.string.tropa_perm_notifications, R.string.tropa_perm_notifications_reason),
    BATTERY("battery", R.string.tropa_perm_battery, R.string.tropa_perm_battery_reason),
    CAMERA("camera", R.string.tropa_perm_camera, R.string.tropa_perm_camera_reason),
    PHONE_STATE("phone_state", R.string.tropa_perm_phone, R.string.tropa_perm_phone_reason),

    /** Installing Tropa's own updates (Android 8+ asks once per app); asked at the first update. */
    INSTALL("install", R.string.tropa_perm_install, R.string.tropa_perm_install_reason),

    /** System "always-on VPN" with "block connections without VPN"; listed when the kill switch is on. */
    LOCKDOWN("lockdown", R.string.tropa_perm_lockdown, R.string.tropa_kill_switch_explain);

    /** Whether the permission exists on this Android version at all. */
    fun isApplicable(): Boolean = when (this) {
        NOTIFICATIONS -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        // Since Android 13 the network type comes with READ_BASIC_PHONE_STATE, granted at install.
        PHONE_STATE -> Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        INSTALL -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        LOCKDOWN -> CheckSettings.killSwitch
        else -> true
    }

    fun isGranted(context: Context): Boolean = when (this) {
        VPN -> VpnService.prepare(context) == null
        NOTIFICATIONS -> !isApplicable() || granted(context, Manifest.permission.POST_NOTIFICATIONS)
        BATTERY -> context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
        CAMERA -> granted(context, Manifest.permission.CAMERA)
        PHONE_STATE -> !isApplicable() || granted(context, Manifest.permission.READ_PHONE_STATE)
        INSTALL -> Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()
        // Known only while the VPN runs (Android 10+); reported by the watchdog.
        LOCKDOWN -> WatchStateStore.read()?.lockdown == true
    }

    /** Runtime permission behind this item, null when it is granted through an intent instead. */
    fun runtimePermission(): String? = when (this) {
        NOTIFICATIONS -> if (isApplicable()) Manifest.permission.POST_NOTIFICATIONS else null
        CAMERA -> Manifest.permission.CAMERA
        PHONE_STATE -> Manifest.permission.READ_PHONE_STATE
        else -> null
    }

    /** True when Tropa should still offer the explanation on its own (never after any answer). */
    fun shouldAutoAsk(context: Context): Boolean =
        this != LOCKDOWN && isApplicable() && !isGranted(context) && !TropaPrefs.isPermissionAsked(id)

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/**
 * Asks the system for a [TropaPermission] after the user agreed in the explanation dialog.
 * When Android no longer shows its own dialog for a runtime permission, the app settings open.
 * Must be created before the activity is started (it registers result launchers).
 */
class TropaPermissionRequester(private val activity: ComponentActivity) {
    private var onFinished: (() -> Unit)? = null
    private var pendingPermission: TropaPermission? = null

    private val runtimeLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val permission = pendingPermission
            val runtime = permission?.runtimePermission()
            if (!granted && permission != null && runtime != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, runtime)
            ) {
                TropaPrefs.setPermissionBlocked(permission.id, true)
            }
            finish()
        }

    private val intentLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { finish() }

    fun request(permission: TropaPermission, onDone: () -> Unit = {}) {
        TropaPrefs.setPermissionAsked(permission.id)
        if (permission.isGranted(activity) || !permission.isApplicable()) {
            onDone()
            return
        }
        pendingPermission = permission
        onFinished = onDone
        when (permission) {
            TropaPermission.VPN -> VpnService.prepare(activity)?.let(intentLauncher::launch) ?: finish()
            TropaPermission.LOCKDOWN -> launchIntent(Intent(Settings.ACTION_VPN_SETTINGS), fallback = Intent(Settings.ACTION_SETTINGS))
            TropaPermission.INSTALL -> launchIntent(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
                fallback = appDetailsIntent(),
            )
            TropaPermission.BATTERY -> launchIntent(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}")),
                fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )

            else -> {
                val runtime = permission.runtimePermission() ?: return finish()
                if (TropaPrefs.isPermissionBlocked(permission.id)) {
                    launchIntent(appSettingsIntent(permission), fallback = appDetailsIntent())
                } else {
                    runtimeLauncher.launch(runtime)
                }
            }
        }
    }

    private fun launchIntent(intent: Intent, fallback: Intent) {
        try {
            intentLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            try {
                intentLauncher.launch(fallback)
            } catch (e: ActivityNotFoundException) {
                finish()
            }
        }
    }

    private fun appSettingsIntent(permission: TropaPermission): Intent =
        if (permission == TropaPermission.NOTIFICATIONS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
        } else {
            appDetailsIntent()
        }

    private fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))

    private fun finish() {
        val callback = onFinished
        onFinished = null
        pendingPermission?.let { if (it.isGranted(activity)) TropaPrefs.setPermissionBlocked(it.id, false) }
        pendingPermission = null
        callback?.invoke()
    }
}
