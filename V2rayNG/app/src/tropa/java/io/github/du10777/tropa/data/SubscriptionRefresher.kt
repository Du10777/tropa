package io.github.du10777.tropa.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.util.LogUtil
import java.util.concurrent.TimeUnit

/**
 * Tropa updates its subscriptions itself, instead of upstream's updater: the device headers go out
 * (X-HWID for panels that limit devices) and the response headers are kept (traffic and expiry,
 * title, announce, support and report addresses). Each subscription is due after its
 * Profile-Update-Interval (12 h by default); an hourly job and every start of Tropa refresh the due ones.
 */
object SubscriptionRefresher {
    private const val TAG = "TropaSubscriptions"
    private const val WORK = "tropa-subscriptions"

    fun schedule(context: Context) {
        takeOverFromUpstream()
        val request = PeriodicWorkRequestBuilder<TropaSubscriptionWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Connections added before 0.8 were left to upstream's updater; its jobs are cancelled. */
    private fun takeOverFromUpstream() {
        MmkvManager.decodeSubscriptions()
            .filter { it.subscription.autoUpdate && it.subscription.url.isNotEmpty() }
            .forEach { cache ->
                cache.subscription.autoUpdate = false
                MmkvManager.encodeSubscription(cache.guid, cache.subscription)
                SubscriptionUpdater.syncOne(subId = cache.guid)
            }
    }

    fun isDue(lastUpdated: Long, intervalMinutes: Long, now: Long): Boolean =
        now - lastUpdated >= intervalMinutes.coerceAtLeast(15) * 60_000L

    /** Refreshes the subscriptions whose time has come; returns how many were updated. Blocking. */
    fun refreshDue(now: Long = System.currentTimeMillis()): Int = MmkvManager.decodeSubscriptions()
        .filter { it.subscription.url.isNotEmpty() && isDue(it.subscription.lastUpdated, it.subscription.updateInterval, now) }
        .count { cache ->
            Connections.refresh(cache.guid).also { ok ->
                LogUtil.i(TAG, "subscription ${cache.subscription.remarks}: ${if (ok) "updated" else "update failed"}")
            }
        }
}

class TropaSubscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        SubscriptionRefresher.refreshDue()
        return Result.success()
    }
}
