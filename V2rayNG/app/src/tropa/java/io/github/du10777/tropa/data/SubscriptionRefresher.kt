package io.github.du10777.tropa.data

import android.content.Context
import androidx.work.WorkManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.util.LogUtil

/**
 * Tropa updates its subscriptions itself, and only while the user keeps the VPN on: nothing goes
 * to the subscription server while Tropa is off. The watchdog asks for the due ones when the
 * connection comes up and then every half an hour; a subscription is due after its
 * Profile-Update-Interval (12 h by default). Its own requests carry the device headers (X-HWID)
 * and keep the response headers (traffic and expiry, title, announce, support and report addresses).
 */
object SubscriptionRefresher {
    private const val TAG = "TropaSubscriptions"

    /** 0.8.0 updated subscriptions from WorkManager, in the background, even with the VPN off. */
    private const val OLD_WORK = "tropa-subscriptions"

    /** Takes the subscriptions over from upstream's updater (and drops the 0.8.0 background job). */
    fun takeOver(context: Context) {
        MmkvManager.decodeSubscriptions()
            .filter { it.subscription.autoUpdate && it.subscription.url.isNotEmpty() }
            .forEach { cache ->
                cache.subscription.autoUpdate = false
                MmkvManager.encodeSubscription(cache.guid, cache.subscription)
                SubscriptionUpdater.syncOne(subId = cache.guid)
            }
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(OLD_WORK) }
    }

    fun isDue(lastUpdated: Long, intervalMinutes: Long, now: Long): Boolean =
        now - lastUpdated >= intervalMinutes.coerceAtLeast(15) * 60_000L

    /** Refreshes the subscriptions whose time has come; returns how each went. Blocking. */
    fun refreshDue(now: Long = System.currentTimeMillis()): Map<String, Connections.Refresh> = MmkvManager.decodeSubscriptions()
        .filter { it.subscription.url.isNotEmpty() && isDue(it.subscription.lastUpdated, it.subscription.updateInterval, now) }
        .associate { it.guid to refresh(it.guid) }

    /** The active connection right now, whatever its interval (servers silent: maybe they moved). */
    fun refreshActive(): Connections.Refresh =
        Connections.activeId()?.let(::refresh) ?: Connections.Refresh.FAILED

    /**
     * Refreshes one subscription; an unchanged one keeps its profiles. A changed one gets new
     * profile ids, so for the active connection the profile to start is picked again: a later
     * reconnect must not look for one that is gone. The running core is not restarted: the
     * watchdog adds the new servers to it (TropaWatchdog.onSubscriptionChanged).
     */
    fun refresh(id: String): Connections.Refresh {
        val result = Connections.refresh(id, keepIfSame = true)
        LogUtil.i(TAG, "subscription $id: $result")
        if (result == Connections.Refresh.UPDATED && id == Connections.activeId()) {
            Connections.serverToStart(id)?.let(MmkvManager::setSelectServer)
        }
        return result
    }
}
