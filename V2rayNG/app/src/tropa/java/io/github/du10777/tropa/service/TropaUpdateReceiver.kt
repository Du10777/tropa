package io.github.du10777.tropa.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.MmkvManager
import io.github.du10777.tropa.data.AppUpdates

/**
 * The installer stops the old app together with its VPN. When Tropa installed its own update while
 * the VPN was on, the new version turns it back on. With upstream's "start on boot" the upstream
 * boot receiver does that already.
 */
class TropaUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val target = AppUpdates.resumeVpnFor
        if (target.isEmpty()) return
        AppUpdates.resumeVpnFor = ""
        if (target != BuildConfig.VERSION_NAME || MmkvManager.decodeStartOnBoot()) return
        if (MmkvManager.getSelectServer().isNullOrEmpty()) return
        runCatching { LauncherManager.startService(context) }
    }
}
