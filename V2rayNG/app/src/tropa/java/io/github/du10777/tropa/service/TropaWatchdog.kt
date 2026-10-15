package io.github.du10777.tropa.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Process
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
import io.github.du10777.tropa.data.ConnectionLog
import io.github.du10777.tropa.data.Connections
import io.github.du10777.tropa.data.LogLevel
import io.github.du10777.tropa.data.Node
import io.github.du10777.tropa.data.NodePlan
import io.github.du10777.tropa.data.NodePlanner
import io.github.du10777.tropa.data.NodeSelector
import io.github.du10777.tropa.data.ProblemReports
import io.github.du10777.tropa.data.SubscriptionRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet6Address
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Starts and stops the watchdog together with the core (runs in the VPN service process). */
class TropaServiceEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_WAKE -> return TropaWatchdog.wakeUp()
            ACTION_SUBSCRIPTION_CHANGED -> return TropaWatchdog.onSubscriptionChanged()
        }
        when (intent.getIntExtra("key", 0)) {
            AppConfig.MSG_STATE_START_SUCCESS -> TropaWatchdog.start(context)
            AppConfig.MSG_STATE_STOP_SUCCESS -> TropaWatchdog.stop(context)
        }
    }

    companion object {
        const val ACTION_WAKE = "io.github.du10777.tropa.action.WAKE_WATCHDOG"
        const val ACTION_SUBSCRIPTION_CHANGED = "io.github.du10777.tropa.action.SUBSCRIPTION_CHANGED"

        /** Lets the watchdog (VPN service process) pick up changed settings right away. */
        fun wakeWatchdog(context: Context) {
            context.sendBroadcast(Intent(ACTION_WAKE).setPackage(context.packageName))
        }

        /** The active subscription was updated in the UI: the running tunnel takes its servers over. */
        fun subscriptionChanged(context: Context) {
            context.sendBroadcast(Intent(ACTION_SUBSCRIPTION_CHANGED).setPackage(context.packageName))
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

    /** A problem report is saved once an outage lasts this many reconnect attempts. */
    private const val REPORT_AFTER_ATTEMPTS = 3

    /** Servers answering within this time after the first one are compared, the best one wins. */
    private const val FIRST_ANSWER_WINDOW_MS = 1_000L

    /** After connecting, the remaining servers get this long to answer, then count as down for now. */
    private const val BACKGROUND_ANSWER_MS = 3_000L

    /** While connected, a server check with the same outcome as before is logged once in this time. */
    private const val QUIET_LOG_MS = 10 * 60_000L

    /** How often the core log reader follows log level changes. */
    private const val CORE_LOG_POLL_MS = 3_000L

    /** While connected, due subscriptions are looked for this often (and when the connection comes up). */
    private const val SUBSCRIPTION_CHECK_MS = 30 * 60_000L

    private var scope: CoroutineScope? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** The active subscription changed: the watch session applies it to the running core. */
    private val subscriptionChanged = AtomicBoolean(false)

    @Volatile
    private var screenOn = true
    private var signalsRegistered = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** The last network written to the log, so repeated callbacks do not repeat it. */
    @Volatile
    private var lastNetwork: String? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    context?.let { logScreen(it, on = false) }
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    if (!screenOn) context?.let { logScreen(it, on = true) }
                    screenOn = true
                    wakeUp()
                }
            }
        }
    }

    private fun logScreen(context: Context, on: Boolean) {
        if (ConnectionLog.level != LogLevel.DEBUG) return
        val app = context.applicationContext
        scope?.launch { ConnectionLog.append(app, ProbeEvent(System.currentTimeMillis(), if (on) "screen_on" else "screen_off", "", null, "", ok = true)) }
    }

    /** Writes the network Tropa runs over when it changes: its type, and details from the verbose level. */
    private fun logNetwork(app: Context) {
        val level = ConnectionLog.level
        if (level == LogLevel.ERRORS) return
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        val network = runCatching { underlyingNetwork(cm) }.getOrNull()
        val caps = network?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }
        val type = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val detail = if (level >= LogLevel.VERBOSE && network != null) networkDetails(cm, network, caps) else null
        val key = "$type|$detail"
        if (key == lastNetwork) return
        lastNetwork = key
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        ConnectionLog.append(app, ProbeEvent(System.currentTimeMillis(), "network", "", null, type, ok = validated, detail = detail))
    }

    /**
     * The physical network under the VPN. Android may report the VPN itself as the default network
     * even to the app it excludes, so then the best other network with internet is taken.
     */
    @Suppress("DEPRECATION")
    private fun underlyingNetwork(cm: ConnectivityManager): Network? {
        val active = cm.activeNetwork
        if (active != null && cm.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == false) return active
        return cm.allNetworks
            .mapNotNull { network -> cm.getNetworkCapabilities(network)?.let { network to it } }
            .filter { (_, caps) -> !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
            .sortedWith(
                compareByDescending<Pair<Network, NetworkCapabilities>> { it.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
                    .thenByDescending { it.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) }
            )
            .firstOrNull()?.first
    }

    private fun networkDetails(cm: ConnectivityManager, network: Network, caps: NetworkCapabilities?): String {
        val link = runCatching { cm.getLinkProperties(network) }.getOrNull()
        return buildList {
            if (caps != null) {
                add(if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "validated" else "not validated")
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) add("captive portal")
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) add("metered")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)) add("roaming")
            }
            link?.interfaceName?.let { add(it) }
            link?.dnsServers?.takeIf { it.isNotEmpty() }?.let { dns -> add("DNS " + dns.joinToString(" ") { it.hostAddress ?: "?" }) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && link?.isPrivateDnsActive == true) add("private DNS " + (link.privateDnsServerName ?: "auto"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) link?.mtu?.takeIf { it > 0 }?.let { add("MTU $it") }
            link?.linkAddresses?.let { addresses ->
                add(if (addresses.any { it.address is Inet6Address && !it.address.isLinkLocalAddress }) "IPv6" else "no IPv6")
            }
        }.joinToString(" · ")
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
        if (scope != null) ConnectionLog.append(app, ProbeEvent(System.currentTimeMillis(), "stop", "", null, "", ok = true))
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

    /** The active subscription has new servers: they go into the running core, nothing restarts. */
    fun onSubscriptionChanged() {
        subscriptionChanged.set(true)
        wakeUp()
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
            override fun onAvailable(network: Network) {
                logNetwork(app)
                wakeUp()
            }

            override fun onLost(network: Network) {
                logNetwork(app)
                wakeUp()
            }

            // A VPN default network changes its capabilities when the network under it changes.
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = logNetwork(app)
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
        private var connectedAt = 0L
        private var reportSaved = false

        /** The active subscription was downloaded again during this outage (servers may have moved). */
        private var subscriptionRetried = false

        /** Tags of all outbounds in the running core, so servers added on the fly get free ones. */
        private val coreTags = HashSet<String>()

        /** The active server left the subscription: new connections move on once another one answers. */
        private var leaving: String? = null
        /** Last outcome and last written check of each server, to keep steady periods short in the log. */
        private val lastOutcome = ConcurrentHashMap<String, Boolean>()
        private val lastWrittenAt = ConcurrentHashMap<String, Long>()
        private val trafficBaseRx = TrafficStats.getUidRxBytes(Process.myUid()).coerceAtLeast(0)
        private val trafficBaseTx = TrafficStats.getUidTxBytes(Process.myUid()).coerceAtLeast(0)

        private val direct by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .callTimeout(6, TimeUnit.SECONDS)
                .followRedirects(false)
                .build()
        }

        suspend fun run() {
            record(ProbeEvent(System.currentTimeMillis(), "start", "", null, "", ok = true))
            lastNetwork = null
            logNetwork(context)
            scope.launch { captureCore() }
            scope.launch { refreshSubscriptions() }
            loadPlan()
            var attempt = 0
            while (currentCoroutineContext().isActive) {
                if (!CheckSettings.enabled) {
                    if (connectedAt == 0L) {
                        connectedAt = System.currentTimeMillis()
                        scope.launch { refreshDueSubscriptions() }
                    }
                    if (subscriptionChanged.getAndSet(false)) applyUnchecked()
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
                    connectedAt = 0L
                    publish(WatchPhase.NO_INTERNET)
                    notifyLost()
                    waitForWake(NO_INTERNET_RECHECK_MS)
                    continue
                }
                val pauseMs = NodeSelector.backoffSeconds(attempt) * 1000
                publish(WatchPhase.NO_SERVER, attempt, System.currentTimeMillis() + pauseMs)
                notifyLost()
                if (attempt >= REPORT_AFTER_ATTEMPTS && !reportSaved) {
                    reportSaved = ProblemReports.save(context, currentState(WatchPhase.NO_SERVER, attempt, 0L))
                }
                var updated = false
                if (attempt >= REPORT_AFTER_ATTEMPTS && !subscriptionRetried) {
                    subscriptionRetried = true
                    // The servers may have moved: then the reconnect right below starts on the new list.
                    updated = SubscriptionRefresher.refreshActive() == Connections.Refresh.UPDATED
                    if (updated) record(subscriptionEvent("no server answers: reconnecting on the new list"))
                }
                if (!updated) waitForWake(pauseMs)
                record(ProbeEvent(System.currentTimeMillis(), "reload", "", null, "", ok = true))
                connectedAt = 0L
                reconnect(attempt, rotate = !updated)
                loadPlan()
            }
        }

        /** Reads the servers of the configuration the core runs (the saved one, the core starts from it). */
        private fun loadPlan() {
            subscriptionChanged.set(false)
            val config = savedConfig()
            plan = config?.let(NodePlanner::fromConfigJson) ?: NodePlan(null, emptyList())
            coreTags.clear()
            config?.let { coreTags += NodePlanner.outboundTags(it) }
            leaving = null
            if (plan.nodes.isEmpty()) {
                // Unknown layout: check the path the routing picks.
                plan = NodePlan(null, listOf(Node(tag = "", name = "", protocol = "", address = null, transport = null)))
            }
            selector.reset(plan.nodes.map { it.tag })
            activeTag = null
            lastOthersAt = 0L
            lastThroughputAt = 0L
            if (ConnectionLog.level >= LogLevel.VERBOSE) {
                val names = plan.nodes.joinToString(", ") { it.name.ifEmpty { it.tag.ifEmpty { "?" } } }
                record(ProbeEvent(System.currentTimeMillis(), "servers", "", null, "", ok = true, detail = names + (if (plan.balancerTag != null) " · balancer" else "")))
            }
        }

        private fun savedConfig(): String? {
            val guid = MmkvManager.getSelectServer() ?: return null
            return runCatching { CoreConfigManager.getV2rayConfig(context, guid) }.getOrNull()?.takeIf { it.status }?.content
        }

        /** Subscriptions are updated only while the VPN is on: every half an hour, when connected. */
        private suspend fun refreshSubscriptions() {
            while (currentCoroutineContext().isActive) {
                delay(SUBSCRIPTION_CHECK_MS)
                if (phase == WatchPhase.CONNECTED || phase == WatchPhase.CHECKS_OFF) refreshDueSubscriptions()
            }
        }

        /** Downloads the due subscriptions; new servers of the active one go into the running core. */
        private fun refreshDueSubscriptions() {
            val results = runCatching { SubscriptionRefresher.refreshDue() }.getOrNull() ?: return
            if (results[Connections.activeId()] == Connections.Refresh.UPDATED) onSubscriptionChanged()
        }

        /**
         * The active subscription changed while connected. Servers that stayed keep their place in
         * the running core with their open connections; new ones are added to it and checked, and
         * get new connections when noticeably better (the usual rule). If the active server is gone,
         * new connections move to the best one that answers while its open ones live out.
         * Servers are matched by their settings: a new config may give them other tags.
         */
        private suspend fun applySubscription() {
            val config = savedConfig() ?: return
            val fresh = NodePlanner.fromConfigJson(config)
            if (plan.balancerTag == null) return applyWithoutBalancer(fresh)
            val running = plan.nodes.associateBy { it.fingerprint }
            val freshNodes = fresh.nodes.distinctBy { it.fingerprint }
            // Tags in the running core by tags in the new config, for references between outbounds.
            val renamed = HashMap<String, String>()
            freshNodes.forEach { node -> running[node.fingerprint]?.let { renamed[node.tag] = it.tag } }
            val nodes = freshNodes.mapNotNull { node ->
                running[node.fingerprint] ?: addToCore(node, renamed)?.also { renamed[node.tag] = it.tag }
            }
            val added = nodes.filter { it.fingerprint !in running }
            val removed = plan.nodes.filter { node -> freshNodes.none { it.fingerprint == node.fingerprint } }
            if (added.isEmpty() && removed.isEmpty()) return
            record(
                subscriptionEvent(
                    listOfNotNull(
                        added.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "added: ") { it.name },
                        removed.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "removed: ") { it.name },
                    ).joinToString(" · "),
                ),
            )
            // A server that is gone may still carry connections: while active it stays watched.
            val gone = removed.firstOrNull { it.tag == activeTag }
            val watched = nodes + listOfNotNull(gone)
            plan = NodePlan(plan.balancerTag, watched, watched)
            selector.retain(watched.map { it.tag })
            leaving = gone?.tag
            if (added.isNotEmpty()) probeAll(added.map { it.tag })
            gone?.let { moveOff(it.tag) }
            publish(WatchPhase.CONNECTED)
        }

        /**
         * No balancer in the running core, so new connections cannot be steered. While the active
         * server is still in the subscription, the new list waits for the next start. If it is gone,
         * the new server is checked through the running core first, and only if it answers the core
         * restarts in place on the new list.
         */
        private suspend fun applyWithoutBalancer(fresh: NodePlan) {
            val active = plan.nodes.firstOrNull { it.tag == activeTag }?.takeIf { it.fingerprint.isNotEmpty() } ?: return
            if (fresh.proxies.any { it.fingerprint == active.fingerprint }) {
                record(subscriptionEvent("current server stays: the new list applies at the next start"))
                return
            }
            val candidate = fresh.nodes.firstOrNull()?.let { addToCore(it, emptyMap()) }
            if (candidate == null || !probeLatency(candidate.tag).ok || !throughputOk(candidate.tag)) {
                record(subscriptionEvent("new server does not answer: staying on the current one"))
                return
            }
            record(subscriptionEvent("server replaced: reconnecting"))
            restartInPlace()
        }

        /** Checks are off, nothing steers: the core restarts on the new list only if none of its servers stayed. */
        private fun applyUnchecked() {
            val fresh = savedConfig()?.let(NodePlanner::fromConfigJson) ?: return
            val known = plan.nodes.map { it.fingerprint }.filter { it.isNotEmpty() }
            if (known.isEmpty() || fresh.proxies.any { it.fingerprint in known }) {
                record(subscriptionEvent("current servers stay: the new list applies at the next start"))
                return
            }
            record(subscriptionEvent("servers replaced: reconnecting"))
            restartInPlace()
        }

        private fun restartInPlace() {
            reloadCore()
            loadPlan()
            plan.nodes.firstOrNull()?.let { select(it.tag) }
        }

        /**
         * Restarts the core without dropping the VPN. Every reload wakes the watchdog (for the ones
         * after network handovers); after its own reload that wake-up would cut the pause before
         * the next attempt, so it is dropped.
         */
        private fun reloadCore() {
            runCatching { CoreServiceManager.reloadCore() }.onFailure { LogUtil.e(TAG, "core reload failed", it) }
            wake.tryReceive()
        }

        /** Adds a server to the running core under a tag that is free there; null if the core refuses it. */
        private fun addToCore(node: Node, renamed: Map<String, String>): Node? {
            val outbound = node.outbound ?: return null
            repeat(3) {
                val tag = freeTag(node.tag)
                coreTags += tag
                val result = runCatching {
                    CoreServiceManager.coreController.tropaAddOutbound(NodePlanner.prepareOutbound(outbound, tag, renamed))
                }
                val error = result.exceptionOrNull() ?: return node.copy(tag = tag)
                LogUtil.e(TAG, "cannot add server ${node.name}", error)
                if (error.message?.contains("existing tag") != true) return null
            }
            return null
        }

        private fun freeTag(base: String): String =
            (sequenceOf(base) + generateSequence(2) { it + 1 }.map { "$base-$it" }).first { it !in coreTags }

        /** The active server left the subscription: new connections go elsewhere once another one answers. */
        private fun moveOff(gone: String) {
            if (activeTag == gone) {
                val next = selector.bestAlive(except = gone) ?: return
                if (!throughputOk(next)) {
                    selector.record(next, ok = false, latencyMs = null)
                    return
                }
                switchTo(next, "previous one left the subscription")
            }
            // Not used for new connections any more; its open ones live out in the core.
            leaving = null
            val watched = plan.nodes.filter { it.tag != gone }
            plan = NodePlan(plan.balancerTag, watched, watched)
            selector.retain(watched.map { it.tag })
        }

        private fun subscriptionEvent(detail: String) =
            ProbeEvent(System.currentTimeMillis(), "subscription", "", null, "", ok = true, detail = detail.ifEmpty { null })

        /** At the debug level, copies the Xray core messages (logcat tag GoLog of this app) into the log. */
        private suspend fun captureCore() {
            var reader: java.lang.Process? = null
            try {
                while (currentCoroutineContext().isActive) {
                    val wanted = ConnectionLog.level == LogLevel.DEBUG
                    if (wanted && (reader == null || !alive(reader))) {
                        reader?.destroy()
                        reader = startCoreReader()
                    } else if (!wanted && reader != null) {
                        reader.destroy()
                        reader = null
                    }
                    delay(CORE_LOG_POLL_MS)
                }
            } finally {
                reader?.destroy()
            }
        }

        private fun alive(process: java.lang.Process): Boolean = runCatching { process.exitValue() }.isFailure

        private fun startCoreReader(): java.lang.Process? = runCatching {
            val since = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            ProcessBuilder("logcat", "-v", "brief", "-T", since, "-s", "GoLog").redirectErrorStream(true).start()
        }.onSuccess { process ->
            scope.launch {
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        // "W/GoLog  ( 1694): [Warning] core: Xray 26.10.10 started"
                        val text = line.substringAfter("): ", "").trim()
                        if (text.isNotEmpty()) record(ProbeEvent(System.currentTimeMillis(), "core", "", null, "", ok = !text.startsWith("[Error]"), detail = text))
                    }
                }
            }
        }.onFailure { LogUtil.e(TAG, "cannot read the core log", it) }.getOrNull()

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
                if (subscriptionChanged.getAndSet(false)) applySubscription()
                val tag = activeTag ?: return
                val othersDue = plan.isBalanced && screenOn &&
                    System.currentTimeMillis() - lastOthersAt >= CheckSettings.othersIntervalSec * 1000L
                // All servers are checked at the same time, the active one included.
                val tags = if (othersDue) plan.nodes.map { it.tag } else listOf(tag)
                var results = probeAll(tags)
                var result = results.getValue(tag)
                if (othersDue) lastOthersAt = System.currentTimeMillis()
                // A failed check of the active server needs fresh data about the others to fail over.
                if (!result.ok && plan.isBalanced && !othersDue) {
                    results = probeAll(plan.nodes.map { it.tag }.filter { it != tag })
                    lastOthersAt = System.currentTimeMillis()
                }
                if (result.ok) {
                    lastLatency = result.latencyMs
                    checkedAt = System.currentTimeMillis()
                }
                val now = System.currentTimeMillis()
                if (screenOn && result.ok && now - lastThroughputAt >= CheckSettings.throughputIntervalSec * 1000L) {
                    if (!throughputOk(tag)) selector.record(tag, ok = false, latencyMs = null)
                    lastThroughputAt = now
                }
                when (val decision = selector.evaluate(tag)) {
                    NodeSelector.Decision.Keep -> publish(WatchPhase.CONNECTED)
                    is NodeSelector.Decision.SwitchTo -> switchTo(
                        decision.tag,
                        if (decision.reason == NodeSelector.Reason.BETTER) "faster" else "previous one is down",
                    )

                    NodeSelector.Decision.AllDead -> return
                }
                leaving?.let { gone ->
                    moveOff(gone)
                    publish(WatchPhase.CONNECTED)
                }
                nextDelay = if (result.ok) intervalMs() else RETRY_AFTER_FAILURE_MS
            }
        }

        private fun intervalMs(): Long =
            (if (screenOn) CheckSettings.activeIntervalSec else CheckSettings.screenOffIntervalSec) * 1000L

        private fun switchTo(tag: String, reason: String) {
            LogUtil.i(TAG, "switching new connections to $tag ($reason)")
            record(event(tag, "switch", "", ok = true, ProbeResult(ok = true), error = null, detail = reason))
            select(tag)
            publish(WatchPhase.CONNECTED)
        }

        private fun select(tag: String) {
            activeTag = tag
            if (plan.balancerTag != null) override(tag)
        }

        private fun override(tag: String) {
            val balancer = plan.balancerTag ?: return
            runCatching { CoreServiceManager.coreController.tropaOverrideBalancer(balancer, tag) }
                .onFailure { LogUtil.e(TAG, "balancer override failed", it) }
        }

        /**
         * The core restarts in place on network handovers: that drops the override, and the core
         * then runs the saved config, which is not the plan any more once a changed subscription
         * was applied on the fly. Then the plan follows the core.
         */
        private fun ensureOverride() {
            val balancer = plan.balancerTag ?: return
            val tag = activeTag ?: return
            val current = runCatching { CoreServiceManager.coreController.tropaBalancerTarget(balancer) }.getOrNull()
            if (current != tag && !followSavedConfig()) override(tag)
        }

        /** Takes the plan from the saved config if its servers differ, keeping what is known about them. */
        private fun followSavedConfig(): Boolean {
            val config = savedConfig() ?: return false
            val fresh = NodePlanner.fromConfigJson(config)
            if (fresh.nodes.isEmpty()) return false
            if (fresh.balancerTag == plan.balancerTag && fresh.nodes.map { it.tag to it.fingerprint } == plan.nodes.map { it.tag to it.fingerprint }) return false
            val known = plan.nodes.associateBy { it.fingerprint }
            val previous = fresh.nodes.mapNotNull { node -> known[node.fingerprint]?.let { node.tag to it.tag } }.toMap()
            val active = plan.nodes.firstOrNull { it.tag == activeTag }?.fingerprint
            plan = fresh
            coreTags.clear()
            coreTags += NodePlanner.outboundTags(config)
            leaving = null
            selector.retain(fresh.nodes.map { it.tag }, previous)
            select(fresh.nodes.firstOrNull { it.fingerprint == active }?.tag ?: selector.bestAlive() ?: fresh.nodes.first().tag)
            return true
        }

        /** Checks the servers in parallel and records the results. */
        private suspend fun probeAll(tags: List<String>): Map<String, ProbeResult> = coroutineScope {
            tags.map { tag -> async { tag to probeLatency(tag) } }.awaitAll().onEach { (tag, result) ->
                selector.record(tag, result.ok, result.latencyMs.takeIf { result.ok })
            }.toMap()
        }

        /**
         * A server is alive when any of the latency addresses answers through it. All addresses are
         * asked at once and the first success counts; the others finish in the background.
         * The log gets one line per server: the address that answered, or the errors of all of them.
         */
        private suspend fun probeLatency(tag: String): ProbeResult {
            val urls = CheckSettings.latencyUrls
            if (urls.isEmpty()) return ProbeResult(error = "no addresses")
            // At the debug level every address is written by itself, the late ones too.
            val perAddress = ConnectionLog.level == LogLevel.DEBUG
            val answers = Channel<Pair<String, ProbeResult>>(Channel.UNLIMITED)
            urls.forEach { url ->
                scope.launch {
                    val result = probe(tag, url, LATENCY_TIMEOUT_MS, LATENCY_MAX_BYTES)
                    if (perAddress) record(event(tag, "latency", url, result.ok, result, detail = httpStatus(result)))
                    answers.trySend(url to result)
                }
            }
            val errors = LinkedHashMap<String, String>()
            var last = ProbeResult(error = "no answer")
            repeat(urls.size) {
                val (url, result) = answers.receive()
                last = result
                if (result.ok) {
                    recordCheck(tag, event(tag, "latency", url, ok = true, result), write = !perAddress)
                    return result
                }
                errors[url] = result.error ?: "error"
            }
            val details = errors.entries.joinToString("; ") { (url, error) -> "${hostOf(url)}: ${shortError(error)}" }
            recordCheck(tag, event(tag, "latency", "", ok = false, last, error = details), write = !perAddress)
            return last
        }

        private fun httpStatus(result: ProbeResult): String? = result.status.takeIf { it > 0 }?.let { "HTTP $it" }

        private fun hostOf(url: String): String = runCatching { java.net.URI(url).host }.getOrNull() ?: url

        /** Go errors repeat the address (`Get "https://…": context deadline exceeded`); the reason is enough. */
        private fun shortError(error: String): String = error.substringAfterLast("\": ").removePrefix("net/http: ")

        /**
         * Writes a server check as the log level asks. While connected, a check that ends like the
         * previous one is written once in [QUIET_LOG_MS] (normal and errors-only levels).
         */
        private fun recordCheck(tag: String, event: ProbeEvent, write: Boolean = true) {
            val previous = lastOutcome.put(tag, event.ok)
            if (!write) return
            val steady = phase == WatchPhase.CONNECTED && previous == event.ok &&
                event.at - (lastWrittenAt[tag] ?: 0L) < QUIET_LOG_MS
            if (!ConnectionLog.writesCheck(ConnectionLog.level, event.ok, steady)) return
            lastWrittenAt[tag] = event.at
            record(event)
        }

        /** Download and direct checks: at the errors-only level just the failed ones. */
        private fun recordResult(event: ProbeEvent) {
            if (event.ok && ConnectionLog.level == LogLevel.ERRORS) return
            record(event)
        }

        /** At least 64 KB must come through: catches channels that stall after ~16 KB. */
        private fun throughputOk(tag: String): Boolean =
            CheckSettings.throughputUrls.any { url ->
                val result = probe(tag, url, THROUGHPUT_TIMEOUT_MS, THROUGHPUT_MAX_BYTES)
                val ok = result.ok && result.bytes >= CheckSettings.THROUGHPUT_MIN_BYTES
                val detail = if (ConnectionLog.level == LogLevel.DEBUG) httpStatus(result) else null
                recordResult(event(tag, "download", url, ok, result, error = result.error ?: if (!ok) "only ${result.bytes} bytes" else null, detail = detail))
                ok
            }

        private fun probe(tag: String, url: String, timeoutMs: Long, maxBytes: Long): ProbeResult = try {
            val json = CoreServiceManager.coreController.tropaProbe(tag, url, timeoutMs, maxBytes)
            gson.fromJson(json, ProbeResult::class.java) ?: ProbeResult(error = "empty result")
        } catch (e: Exception) {
            ProbeResult(error = e.message ?: e.javaClass.simpleName)
        }

        private fun event(
            tag: String,
            kind: String,
            target: String,
            ok: Boolean,
            result: ProbeResult,
            error: String? = result.error,
            detail: String? = null,
        ): ProbeEvent {
            val node = plan.nodes.firstOrNull { it.tag == tag }
            return ProbeEvent(
                at = System.currentTimeMillis(),
                kind = kind,
                server = node?.name?.takeIf { it.isNotEmpty() } ?: tag,
                address = node?.address,
                target = target,
                ok = ok,
                latencyMs = result.latencyMs,
                bytes = result.bytes,
                error = error,
                detail = detail,
            )
        }

        private fun record(event: ProbeEvent) = ConnectionLog.append(context, event)

        /** Direct request outside the tunnel (the app itself is excluded from its VPN). */
        private fun internetUp(): Boolean = CheckSettings.directUrls.any { url ->
            val started = System.currentTimeMillis()
            var status = 0
            val error = runCatching { direct.newCall(Request.Builder().url(url).build()).execute().use { status = it.code }; null }
                .getOrElse { it.message ?: it.javaClass.simpleName }
            val detail = if (ConnectionLog.level == LogLevel.DEBUG && status > 0) "HTTP $status" else null
            recordResult(ProbeEvent(started, "direct", "", null, url, ok = error == null, latencyMs = System.currentTimeMillis() - started, error = error, detail = detail))
            error == null
        }

        private fun reconnect(attempt: Int, rotate: Boolean) {
            if (!CoreServiceManager.isRunning()) {
                LauncherManager.restartService(context)
                return
            }
            if (rotate && !plan.isBalanced && attempt >= ROTATE_PROFILE_AFTER) rotateProfile()
            reloadCore()
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
            if (connectedAt == 0L) connectedAt = System.currentTimeMillis()
            subscriptionRetried = false
            scope.launch { refreshDueSubscriptions() }
            if (reportSaved || ProblemReports.hasPending(context)) {
                reportSaved = false
                scope.launch { ProblemReports.uploadPending(context) }
            }
            record(
                ProbeEvent(
                    System.currentTimeMillis(), "connected",
                    activeTag?.let { tag -> plan.nodes.firstOrNull { it.tag == tag }?.name }.orEmpty(),
                    activeTag?.let { tag -> plan.nodes.firstOrNull { it.tag == tag }?.address },
                    "", ok = true, latencyMs = activeTag?.let { selector.stats(it)?.median() } ?: 0,
                ),
            )
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
            if (phase != this.phase && (phase == WatchPhase.NO_SERVER || phase == WatchPhase.NO_INTERNET)) {
                record(ProbeEvent(System.currentTimeMillis(), if (phase == WatchPhase.NO_SERVER) "no_server" else "no_internet", "", null, "", ok = false))
            }
            this.phase = phase
            WatchStateStore.publish(context, currentState(phase, attempt, nextRetryAt))
        }

        private fun currentState(phase: WatchPhase, attempt: Int, nextRetryAt: Long): WatchState {
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
            return WatchState(
                    phase = phase,
                    activeName = plan.nodes.firstOrNull { it.tag == activeTag }?.name?.takeIf { it.isNotEmpty() },
                    latencyMs = lastLatency,
                    checkedAt = checkedAt,
                    attempt = attempt,
                    nextRetryAt = nextRetryAt,
                    nodes = nodes,
                    alwaysOn = if (q) vpn?.isAlwaysOn else null,
                    lockdown = if (q) vpn?.isLockdownEnabled else null,
                    connectedAt = if (phase == WatchPhase.CONNECTED || phase == WatchPhase.CHECKS_OFF) connectedAt else 0L,
                    trafficBaseRx = trafficBaseRx,
                    trafficBaseTx = trafficBaseTx,
                )
        }
    }
}
