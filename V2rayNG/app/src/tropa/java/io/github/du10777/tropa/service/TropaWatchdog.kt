package io.github.du10777.tropa.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreConfigManager
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import io.github.du10777.tropa.data.CheckSettings
import io.github.du10777.tropa.data.Connections
import io.github.du10777.tropa.data.Node
import io.github.du10777.tropa.data.NodePlan
import io.github.du10777.tropa.data.NodePlanner
import io.github.du10777.tropa.data.NodeSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Starts and stops the watchdog together with the core (runs in the VPN service process). */
class TropaServiceEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_WAKE) {
            TropaWatchdog.wakeUp()
            return
        }
        when (intent.getIntExtra("key", 0)) {
            AppConfig.MSG_STATE_START_SUCCESS -> TropaWatchdog.start(context)
            AppConfig.MSG_STATE_STOP_SUCCESS -> TropaWatchdog.stop(context)
        }
    }

    companion object {
        const val ACTION_WAKE = "io.github.du10777.tropa.action.WAKE_WATCHDOG"

        /** Lets the watchdog (VPN service process) pick up changed settings right away. */
        fun wakeWatchdog(context: Context) {
            context.sendBroadcast(Intent(ACTION_WAKE).setPackage(context.packageName))
        }
    }
}

/**
 * Keeps an eye on the tunnel while the core runs: "connected" only after data really went through,
 * every server checked separately, new connections steered to the best live one (open ones are
 * left alone), reconnecting without dropping the VPN when all servers are silent.
 */
object TropaWatchdog {
    private const val TAG = "TropaWatchdog"
    private const val LATENCY_TIMEOUT_MS = 6_000L
    private const val LATENCY_MAX_BYTES = 32L * 1024
    private const val THROUGHPUT_TIMEOUT_MS = 15_000L
    private const val THROUGHPUT_MAX_BYTES = 256L * 1024
    private const val RETRY_AFTER_FAILURE_MS = 5_000L
    private const val NO_INTERNET_RECHECK_MS = 30_000L
    private const val CHECKS_OFF_RECHECK_MS = 60_000L
    private const val ROTATE_PROFILE_AFTER = 2

    /** Servers answering within this time after the first one are compared, the best one wins. */
    private const val FIRST_ANSWER_WINDOW_MS = 1_000L

    /** After connecting, the remaining servers get this long to answer, then count as down for now. */
    private const val BACKGROUND_ANSWER_MS = 3_000L

    private var scope: CoroutineScope? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var screenOn = true
    private var signalsRegistered = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> screenOn = false
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    screenOn = true
                    wakeUp()
                }
            }
        }
    }

    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        scope?.cancel()
        screenOn = app.getSystemService(PowerManager::class.java)?.isInteractive != false
        registerSignals(app)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { session -> session.launch { Session(app, session).run() } }
        LogUtil.i(TAG, "started")
    }

    @Synchronized
    fun stop(context: Context) {
        val app = context.applicationContext
        scope?.cancel()
        scope = null
        unregisterSignals(app)
        WatchStateStore.clear(app)
        TropaNotifier.cancel(app)
        LogUtil.i(TAG, "stopped")
    }

    /** Check right away (screen turned on, network changed, settings changed). */
    fun wakeUp() {
        wake.trySend(Unit)
    }

    private fun registerSignals(app: Context) {
        if (signalsRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(app, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = wakeUp()
            override fun onLost(network: Network) = wakeUp()
        }
        runCatching { app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
        signalsRegistered = true
    }

    private fun unregisterSignals(app: Context) {
        if (!signalsRegistered) return
        runCatching { app.unregisterReceiver(screenReceiver) }
        networkCallback?.let { callback ->
            runCatching { app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        signalsRegistered = false
    }

    private suspend fun waitForWake(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { wake.receive() }
    }

    private data class ProbeResult(
        val ok: Boolean = false,
        val status: Int = 0,
        val latencyMs: Long = 0,
        val totalMs: Long = 0,
        val bytes: Long = 0,
        val error: String? = null,
    )

    /** One watch session: from the core start until it stops. */
    private class Session(private val context: Context, private val scope: CoroutineScope) {
        private val gson = Gson()
        private val selector = NodeSelector()
        private var plan = NodePlan(null, emptyList())
        private var activeTag: String? = null
        private var lastLatency: Long? = null
        private var checkedAt = 0L
        private var lastOthersAt = 0L
        private var lastThroughputAt = 0L
        private var everConnected = false
        private var lostNotified = false
        private var phase = WatchPhase.CHECKING

        private val direct by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .callTimeout(6, TimeUnit.SECONDS)
                .followRedirects(false)
                .build()
        }

        suspend fun run() {
            loadPlan()
            var attempt = 0
            while (currentCoroutineContext().isActive) {
                if (!CheckSettings.enabled) {
                    publish(WatchPhase.CHECKS_OFF)
                    waitForWake(CHECKS_OFF_RECHECK_MS)
                    continue
                }
                if (attempt == 0) publish(WatchPhase.CHECKING)
                if (establish()) {
                    attempt = 0
                    onConnected()
                    monitor()
                    if (!CheckSettings.enabled) continue
                }
                attempt++
                if (!internetUp()) {
                    publish(WatchPhase.NO_INTERNET)
                    notifyLost()
                    waitForWake(NO_INTERNET_RECHECK_MS)
                    continue
                }
                val pauseMs = NodeSelector.backoffSeconds(attempt) * 1000
                publish(WatchPhase.NO_SERVER, attempt, System.currentTimeMillis() + pauseMs)
                notifyLost()
                waitForWake(pauseMs)
                reconnect(attempt)
                loadPlan()
            }
        }

        /** Reads the servers of the configuration the core runs. */
        private fun loadPlan() {
            val guid = MmkvManager.getSelectServer()
            val config = guid?.let { runCatching { CoreConfigManager.getV2rayConfig(context, it) }.getOrNull() }
            plan = config?.takeIf { it.status }?.let { NodePlanner.fromConfigJson(it.content) } ?: NodePlan(null, emptyList())
            if (plan.nodes.isEmpty()) {
                // Unknown layout: check the path the routing picks.
                plan = NodePlan(null, listOf(Node(tag = "", name = "", protocol = "", address = null, transport = null)))
            }
            selector.reset(plan.nodes.map { it.tag })
            activeTag = null
            lastOthersAt = 0L
            lastThroughputAt = 0L
        }

        /**
         * Checks all servers at once and connects through the first one that answers: servers that
         * answer within [FIRST_ANSWER_WINDOW_MS] of it are compared and the fastest wins, and the
         * winner must also pass the download test. The rest keep being checked in the background
         * for [BACKGROUND_ANSWER_MS] more, so the details screen and failover see them too.
         */
        private suspend fun establish(): Boolean {
            val tags = plan.nodes.map { it.tag }
            val answers = Channel<Pair<String, ProbeResult>>(Channel.UNLIMITED)
            val probes = tags.associateWith { tag -> scope.async { probeLatency(tag).also { answers.trySend(tag to it) } } }
            val answered = LinkedHashMap<String, ProbeResult>()
            var firstOkAt = 0L
            while (answered.size < tags.size) {
                val waitMs = if (firstOkAt == 0L) Long.MAX_VALUE else firstOkAt + FIRST_ANSWER_WINDOW_MS - System.currentTimeMillis()
                if (waitMs <= 0) break
                val (tag, result) = withTimeoutOrNull(waitMs) { answers.receive() } ?: break
                answered[tag] = result
                selector.record(tag, result.ok, result.latencyMs.takeIf { result.ok })
                if (result.ok && firstOkAt == 0L) firstOkAt = System.currentTimeMillis()
            }

            val pending = probes.filterKeys { it !in answered }
            val winner = answered.filterValues { it.ok }.entries.sortedBy { it.value.latencyMs }.map { it.key }
                .firstOrNull { tag -> passesDownload(tag) }
            if (winner != null) {
                if (pending.isNotEmpty()) scope.launch { finishInBackground(pending) }
                return true
            }

            // Nobody in the window passed: give the late ones their full time, then try them.
            pending.forEach { (tag, probe) ->
                val result = probe.await()
                selector.record(tag, result.ok, result.latencyMs.takeIf { result.ok })
                if (result.ok && passesDownload(tag)) return true
            }
            return false
        }

        private fun passesDownload(tag: String): Boolean {
            select(tag)
            if (throughputOk(tag)) {
                lastThroughputAt = System.currentTimeMillis()
                return true
            }
            selector.record(tag, ok = false, latencyMs = null)
            return false
        }

        private suspend fun finishInBackground(pending: Map<String, kotlinx.coroutines.Deferred<ProbeResult>>) {
            val deadline = System.currentTimeMillis() + BACKGROUND_ANSWER_MS
            pending.forEach { (tag, probe) ->
                val result = withTimeoutOrNull((deadline - System.currentTimeMillis()).coerceAtLeast(1)) { probe.await() }
                selector.record(tag, result?.ok == true, result?.latencyMs?.takeIf { result.ok })
            }
            if (phase == WatchPhase.CONNECTED) publish(WatchPhase.CONNECTED)
        }

        /** Watches the active server while things work; returns when every server is dead. */
        private suspend fun monitor() {
            var nextDelay = intervalMs()
            while (currentCoroutineContext().isActive && CheckSettings.enabled) {
                waitForWake(nextDelay)
                ensureOverride()
                val tag = activeTag ?: return
                val result = probeLatency(tag)
                selector.record(tag, result.ok, result.latencyMs.takeIf { result.ok })
                if (result.ok) {
                    lastLatency = result.latencyMs
                    checkedAt = System.currentTimeMillis()
                }
                val now = System.currentTimeMillis()
                // A failed check of the active server needs fresh data about the others to fail over.
                val othersDue = screenOn && now - lastOthersAt >= CheckSettings.othersIntervalSec * 1000L
                if (plan.isBalanced && (othersDue || !result.ok)) {
                    probeAll(plan.nodes.map { it.tag }.filter { it != tag })
                    lastOthersAt = now
                }
                if (screenOn && result.ok && now - lastThroughputAt >= CheckSettings.throughputIntervalSec * 1000L) {
                    if (!throughputOk(tag)) selector.record(tag, ok = false, latencyMs = null)
                    lastThroughputAt = now
                }
                when (val decision = selector.evaluate(tag)) {
                    NodeSelector.Decision.Keep -> publish(WatchPhase.CONNECTED)
                    is NodeSelector.Decision.SwitchTo -> {
                        LogUtil.i(TAG, "switching new connections to ${decision.tag} (${decision.reason})")
                        select(decision.tag)
                        publish(WatchPhase.CONNECTED)
                    }

                    NodeSelector.Decision.AllDead -> return
                }
                nextDelay = if (result.ok) intervalMs() else RETRY_AFTER_FAILURE_MS
            }
        }

        private fun intervalMs(): Long =
            (if (screenOn) CheckSettings.activeIntervalSec else CheckSettings.screenOffIntervalSec) * 1000L

        private fun select(tag: String) {
            activeTag = tag
            if (plan.isBalanced) override(tag)
        }

        private fun override(tag: String) {
            val balancer = plan.balancerTag ?: return
            runCatching { CoreServiceManager.coreController.tropaOverrideBalancer(balancer, tag) }
                .onFailure { LogUtil.e(TAG, "balancer override failed", it) }
        }

        /** The core restarts in place on network handovers, which drops the override. */
        private fun ensureOverride() {
            val balancer = plan.balancerTag ?: return
            val tag = activeTag ?: return
            if (!plan.isBalanced) return
            val current = runCatching { CoreServiceManager.coreController.tropaBalancerTarget(balancer) }.getOrNull()
            if (current != tag) override(tag)
        }

        private suspend fun probeAll(tags: List<String>) = coroutineScope {
            tags.map { tag -> async { tag to probeLatency(tag) } }.awaitAll().forEach { (tag, result) ->
                selector.record(tag, result.ok, result.latencyMs.takeIf { result.ok })
            }
        }

        /** A server is alive when any of the latency addresses answers through it. */
        private fun probeLatency(tag: String): ProbeResult {
            var last = ProbeResult(error = "no addresses")
            for (url in CheckSettings.latencyUrls) {
                last = probe(tag, url, LATENCY_TIMEOUT_MS, LATENCY_MAX_BYTES)
                if (last.ok) return last
            }
            return last
        }

        /** At least 64 KB must come through: catches channels that stall after ~16 KB. */
        private fun throughputOk(tag: String): Boolean =
            CheckSettings.throughputUrls.any { url ->
                val result = probe(tag, url, THROUGHPUT_TIMEOUT_MS, THROUGHPUT_MAX_BYTES)
                result.ok && result.bytes >= CheckSettings.THROUGHPUT_MIN_BYTES
            }

        private fun probe(tag: String, url: String, timeoutMs: Long, maxBytes: Long): ProbeResult = try {
            val json = CoreServiceManager.coreController.tropaProbe(tag, url, timeoutMs, maxBytes)
            gson.fromJson(json, ProbeResult::class.java) ?: ProbeResult(error = "empty result")
        } catch (e: Exception) {
            ProbeResult(error = e.message ?: e.javaClass.simpleName)
        }

        /** Direct request outside the tunnel (the app itself is excluded from its VPN). */
        private fun internetUp(): Boolean = CheckSettings.directUrls.any { url ->
            runCatching {
                direct.newCall(Request.Builder().url(url).build()).execute().use { true }
            }.getOrDefault(false)
        }

        private fun reconnect(attempt: Int) {
            if (!CoreServiceManager.isRunning()) {
                LauncherManager.restartService(context)
                return
            }
            if (!plan.isBalanced && attempt >= ROTATE_PROFILE_AFTER) rotateProfile()
            runCatching { CoreServiceManager.reloadCore() }.onFailure { LogUtil.e(TAG, "core reload failed", it) }
        }

        /** Several single-server configs: try the next one of the same connection. */
        private fun rotateProfile() {
            val current = MmkvManager.getSelectServer() ?: return
            val group = Connections.activeId() ?: return
            val servers = MmkvManager.decodeServerList(group)
            if (servers.size < 2) return
            val next = servers[(servers.indexOf(current) + 1).mod(servers.size)]
            MmkvManager.setSelectServer(next)
            LogUtil.i(TAG, "trying the next config of the connection")
        }

        private fun onConnected() {
            if (lostNotified) TropaNotifier.notifyRestored(context)
            lostNotified = false
            everConnected = true
            checkedAt = System.currentTimeMillis()
            lastLatency = activeTag?.let { selector.stats(it)?.median() }
            publish(WatchPhase.CONNECTED)
        }

        private fun notifyLost() {
            if (everConnected && !lostNotified) {
                TropaNotifier.notifyLost(context)
                lostNotified = true
            }
        }

        private fun publish(phase: WatchPhase, attempt: Int = 0, nextRetryAt: Long = 0L) {
            this.phase = phase
            val vpn = CoreServiceManager.serviceControl?.get()?.getService() as? VpnService
            val q = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            val nodes = plan.nodes.filter { it.tag.isNotEmpty() }.map { node ->
                val stats = selector.stats(node.tag)
                WatchNode(
                    tag = node.tag,
                    name = node.name,
                    alive = stats?.takeIf { it.checks > 0 }?.alive,
                    latencyMs = stats?.median(),
                    active = node.tag == activeTag,
                )
            }
            WatchStateStore.publish(
                context,
                WatchState(
                    phase = phase,
                    activeName = plan.nodes.firstOrNull { it.tag == activeTag }?.name?.takeIf { it.isNotEmpty() },
                    latencyMs = lastLatency,
                    checkedAt = checkedAt,
                    attempt = attempt,
                    nextRetryAt = nextRetryAt,
                    nodes = nodes,
                    alwaysOn = if (q) vpn?.isAlwaysOn else null,
                    lockdown = if (q) vpn?.isLockdownEnabled else null,
                ),
            )
        }
    }
}
