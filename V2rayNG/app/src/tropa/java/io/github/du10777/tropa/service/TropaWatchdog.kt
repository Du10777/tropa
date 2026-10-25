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
import io.github.du10777.tropa.data.AppUpdates
import io.github.du10777.tropa.data.BatteryGauge
import io.github.du10777.tropa.data.BatteryState
import io.github.du10777.tropa.data.Cellular
import io.github.du10777.tropa.data.CheckKind
import io.github.du10777.tropa.data.CheckSettings
import io.github.du10777.tropa.data.ConnectionLog
import io.github.du10777.tropa.data.Connections
import io.github.du10777.tropa.data.Diagnosis
import io.github.du10777.tropa.data.EnergyBudget
import io.github.du10777.tropa.data.EnergyStore
import io.github.du10777.tropa.data.LogLevel
import io.github.du10777.tropa.data.MeteredWifi
import io.github.du10777.tropa.data.Node
import io.github.du10777.tropa.data.NodePlan
import io.github.du10777.tropa.data.NodePlanner
import io.github.du10777.tropa.data.NodeSelector
import io.github.du10777.tropa.data.ProblemReports
import io.github.du10777.tropa.data.NodeCounter
import io.github.du10777.tropa.data.NodeHistoryStore
import io.github.du10777.tropa.data.Roaming
import io.github.du10777.tropa.data.ScreenMode
import io.github.du10777.tropa.data.SelfTest
import io.github.du10777.tropa.data.SelfTestResult
import io.github.du10777.tropa.data.SelfTestSupport
import io.github.du10777.tropa.data.ServerAddresses
import io.github.du10777.tropa.data.SpeedResult
import io.github.du10777.tropa.data.SubscriptionRefresher
import io.github.du10777.tropa.data.TrafficWatch
import io.github.du10777.tropa.data.UnreachableChecks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
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
import java.net.Inet6Address
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToLong

/** Starts and stops the watchdog together with the core (runs in the VPN service process). */
class TropaServiceEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_WAKE -> return TropaWatchdog.wakeUp()
            ACTION_SUBSCRIPTION_CHANGED -> return TropaWatchdog.onSubscriptionChanged()
            ACTION_TEST_CELLULAR -> return testCellular(intent)
            ACTION_TEST_SPEED -> return TropaWatchdog.measureSpeedSoon()
            ACTION_WIFI_ANSWER -> return answerWifi(context, intent)
            ACTION_TEST_WIFI -> return testWifi(intent)
        }
        when (intent.getIntExtra("key", 0)) {
            AppConfig.MSG_STATE_START_SUCCESS -> TropaWatchdog.start(context)
            AppConfig.MSG_STATE_STOP_SUCCESS -> TropaWatchdog.stop(context)
        }
    }

    companion object {
        const val ACTION_WAKE = "io.github.du10777.tropa.action.WAKE_WATCHDOG"
        const val ACTION_SUBSCRIPTION_CHANGED = "io.github.du10777.tropa.action.SUBSCRIPTION_CHANGED"

        /**
         * Test bench without a SIM: pretend a mobile network (extras network_mcc, home_mcc,
         * generation 2g/3g/4g/5g, signal 0-4; clear=true goes back to the real one). The receiver is not
         * exported, so only root or the system can send it (adb root + am broadcast -n).
         */
        const val ACTION_TEST_CELLULAR = "io.github.du10777.tropa.action.TEST_CELLULAR"

        /** Test bench: measure the speed of the current server at the next round (root only, like TEST_CELLULAR). */
        const val ACTION_TEST_SPEED = "io.github.du10777.tropa.action.TEST_SPEED"

        /** The person answered from the notification whether the Wi-Fi is limited (extras key, limited). */
        const val ACTION_WIFI_ANSWER = "io.github.du10777.tropa.action.WIFI_ANSWER"

        /** Test bench without Wi-Fi: pretend one (extras key, metered; clear=true goes back), root only. */
        const val ACTION_TEST_WIFI = "io.github.du10777.tropa.action.TEST_WIFI"

        /** Lets the watchdog (VPN service process) pick up changed settings right away. */
        fun wakeWatchdog(context: Context) {
            context.sendBroadcast(Intent(ACTION_WAKE).setPackage(context.packageName))
        }

        /** The active subscription was updated in the UI: the running tunnel takes its servers over. */
        fun subscriptionChanged(context: Context) {
            context.sendBroadcast(Intent(ACTION_SUBSCRIPTION_CHANGED).setPackage(context.packageName))
        }

        private fun answerWifi(context: Context, intent: Intent) {
            val key = intent.getStringExtra("key") ?: return
            MeteredWifi.remember(key, intent.getBooleanExtra("limited", true))
            TropaNotifier.cancelWifi(context)
            TropaWatchdog.wakeUp()
        }

        private fun testWifi(intent: Intent) {
            MeteredWifi.override = if (intent.getBooleanExtra("clear", false)) null else
                MeteredWifi.Wifi(intent.getStringExtra("key") ?: "test", intent.getBooleanExtra("metered", true))
            TropaWatchdog.wakeUp()
        }

        private fun testCellular(intent: Intent) {
            Cellular.override = if (intent.getBooleanExtra("clear", false)) null else Cellular.State(
                cellular = true,
                networkMcc = intent.getStringExtra("network_mcc"),
                homeMcc = intent.getStringExtra("home_mcc"),
                generation = when (intent.getStringExtra("generation")) {
                    "2g" -> Cellular.Generation.G2
                    "3g" -> Cellular.Generation.G3
                    "4g" -> Cellular.Generation.G4
                    "5g" -> Cellular.Generation.G5
                    else -> Cellular.Generation.UNKNOWN
                },
                signalLevel = intent.getIntExtra("signal", -1).takeIf { it in 0..4 },
            )
            TropaWatchdog.wakeUp()
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
    private const val LATENCY_MAX_BYTES = 32L * 1024
    private const val THROUGHPUT_MAX_BYTES = 256L * 1024
    private const val RETRY_AFTER_FAILURE_MS = 5_000L
    private const val NO_INTERNET_RECHECK_MS = 30_000L
    private const val CHECKS_OFF_RECHECK_MS = 60_000L
    private const val ROTATE_PROFILE_AFTER = 2

    /** A problem report is saved once an outage lasts this many reconnect attempts. */
    private const val REPORT_AFTER_ATTEMPTS = 3

    /**
     * With the screen on, the core's counters are looked at this often between the checks: when
     * most new connections through the current server fail, it is checked at once.
     */
    private const val COUNTER_POLL_MS = 5_000L

    /** Failing connections bring the check of the current server forward at most this often. */
    private const val EARLY_CHECK_MS = 30_000L

    /** Server histories (NodeHistoryStore) are written at most this often while watching. */
    private const val HISTORY_SAVE_MS = 60_000L

    /** During an outage the server names (LastIP) are checked before the first reconnect and every this many. */
    private const val ADDRESS_RECHECK_ATTEMPTS = 5

    /** A server down this many checks in a row while another carries the connection is probed directly. */
    private const val UNREACHABLE_AFTER = 3

    /** On a limited Wi-Fi the checks come this many times less often (and their probes are 16 times smaller). */
    private const val ECONOMY_FACTOR = 4

    /** While connected, a server check with the same outcome as before is logged once in this time. */
    private const val QUIET_LOG_MS = 10 * 60_000L

    /** How often the core log reader follows log level changes. */
    private const val CORE_LOG_POLL_MS = 3_000L

    /** While connected, due subscriptions are looked for this often (and when the connection comes up). */
    private const val SUBSCRIPTION_CHECK_MS = 30 * 60_000L

    /** Paused checks and stopped roaming traffic look at the network this often (network changes wake up at once). */
    private const val PAUSE_RECHECK_MS = 30_000L

    /** Why the checks pause (WatchState.pause). */
    const val PAUSE_WEAK = "weak"
    const val PAUSE_ROAMING = "roaming"

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

    /** The speed of the current server is measured at the next round (test bench). */
    private val speedSoon = AtomicBoolean(false)

    fun measureSpeedSoon() {
        speedSoon.set(true)
        wakeUp()
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

    /** Waits for a wake-up or the timeout; true when woken (screen on, network change, settings). */
    private suspend fun waitForWake(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) { wake.receive() } != null

    private data class ProbeResult(
        val ok: Boolean = false,
        val status: Int = 0,
        val latencyMs: Long = 0,
        val totalMs: Long = 0,
        val bytes: Long = 0,
        val error: String? = null,
    )

    /** One check of a server: answered or not, its times, and whether the self-test of its node answered. */
    private data class Check(
        val ok: Boolean,
        /** Until the first byte of the answer. */
        val latencyMs: Long? = null,
        /** The time to fetch the 128 KB of the self-test (self-test checks only). */
        val fetchMs: Long? = null,
        val selftest: Boolean = false,
        /** The node's gateway named by its self-test answer. */
        val gateway: String? = null,
        /** The exit named by its self-test answer (null also when the node names none). */
        val exit: String? = null,
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

        /** Why the checks pause right now (PAUSE_*), and the country of a roaming network that stopped the traffic. */
        private var pauseReason: String? = null
        private var roamingMcc: String? = null
        private var loggedPause: String? = null

        /** Check parameters: the user's, then the server's of the connection, then the defaults (read every round). */
        @Volatile
        private var cfg = CheckSettings.DEFAULTS

        /** Keeps the background checks within the battery budget; what it learned survives sessions. */
        private val energy = EnergyBudget(EnergyStore.settings(), EnergyStore.model())

        @Volatile
        private var battery = BatteryState.UNKNOWN

        /** The active server answered its last check: only then may the budget pause the checks to measure. */
        private var activeHealthy = true

        /** The budget factor written to the log last, per screen state (verbose level). */
        private val loggedScale = HashMap<ScreenMode, Double>()

        /** Which servers answer the self-test of their node (by server, for the session). */
        private val support = SelfTestSupport()

        /** The core's own counters per server: traffic of the user and failed connections. */
        private val traffic = TrafficWatch()

        /** The speed of the current server is measured at its next check (it just became current). */
        private var speedDue = false
        private var lastActiveCheckAt = 0L
        private var lastFailingNoteAt = 0L

        /** Server choices of the background end of the connect and of the watch rounds do not interleave. */
        private val steer = Any()

        /** Server histories are written at most this often (and when the plan changes). */
        private var historySavedAt = 0L

        /** The background check of the server names (LastIP), started with the session. */
        private var addressCheck: kotlinx.coroutines.Job? = null

        /** A limited Wi-Fi (see [MeteredWifi]): small and rare checks, no speed tests. */
        private var economy = false

        /** The metered Wi-Fi the person is asked about (its key), and the ones asked in this session. */
        private var wifiAsk: String? = null
        private val wifiAsked = HashSet<String>()

        /** What the network lets through, asked when no server answers (once per outage and network, see [diagnose]). */
        private var diagnosis: Diagnosis.Result? = null
        private var diagnosedAt = 0L
        private var diagnosedNetwork: String? = null

        /** Last outcome and last written check of each server, to keep steady periods short in the log. */
        private val lastOutcome = ConcurrentHashMap<String, Boolean>()
        private val lastWrittenAt = ConcurrentHashMap<String, Long>()
        private val trafficBaseRx = TrafficStats.getUidRxBytes(Process.myUid()).coerceAtLeast(0)
        private val trafficBaseTx = TrafficStats.getUidTxBytes(Process.myUid()).coerceAtLeast(0)

        suspend fun run() {
            record(ProbeEvent(System.currentTimeMillis(), "start", "", null, "", ok = true))
            lastNetwork = null
            logNetwork(context)
            scope.launch { captureCore() }
            scope.launch { refreshSubscriptions() }
            loadPlan()
            addressCheck = scope.launch { checkAddresses() }
            var attempt = 0
            while (currentCoroutineContext().isActive) {
                refreshSettings()
                updateWifi()
                val cell = cellular()
                if (guardRoaming(cell)) {
                    connectedAt = 0L
                    waitForWake(PAUSE_RECHECK_MS)
                    continue
                }
                pauseReason = pauseReasonOf(cell)
                if (!CheckSettings.enabled || pauseReason != null) {
                    if (connectedAt == 0L) {
                        connectedAt = System.currentTimeMillis()
                        scope.launch {
                            refreshDueSubscriptions()
                            checkAppUpdate()
                        }
                    }
                    if (subscriptionChanged.getAndSet(false)) applyUnchecked()
                    publish(if (pauseReason != null) WatchPhase.PAUSED else WatchPhase.CHECKS_OFF)
                    waitForWake(if (pauseReason != null) PAUSE_RECHECK_MS else CHECKS_OFF_RECHECK_MS)
                    continue
                }
                if (attempt == 0) publish(WatchPhase.CHECKING)
                if (establish()) {
                    attempt = 0
                    onConnected()
                    // Back to the top when the checks pause or stop; otherwise every server is dead.
                    if (monitor()) continue
                }
                attempt++
                // No server answers: what does the network let through? Nothing at all = the phone's
                // own network is down, then Tropa waits for it instead of reconnecting.
                if (Diagnosis.offline(diagnose().verdict)) {
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
                // A server name may point elsewhere now (or DNS lies): the names are checked again
                // before the first reconnect and every few after, so the reload takes the IP that works.
                if (attempt == 1 || attempt % ADDRESS_RECHECK_ATTEMPTS == 0) {
                    addressCheck?.join()
                    checkAddresses()
                }
                record(ProbeEvent(System.currentTimeMillis(), "reload", "", null, "", ok = true))
                connectedAt = 0L
                reconnect(attempt, rotate = !updated)
                loadPlan()
            }
        }

        /** Takes the check parameters again: the user may change them, a subscription update may bring the server's. */
        private fun refreshSettings() {
            cfg = runCatching { CheckSettings.current() }.onFailure { LogUtil.e(TAG, "cannot read the check settings", it) }
                .getOrDefault(cfg)
            selector.tune(cfg)
            energy.settings = runCatching { EnergyStore.settings() }.getOrDefault(energy.settings)
        }

        /** Reads the servers of the configuration the core runs (the saved one, the core starts from it). */
        private fun loadPlan() {
            subscriptionChanged.set(false)
            // What this plan learned is kept before its servers are replaced (tags may be reused).
            saveHistory(force = true)
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
            restoreHistory()
            activeTag = null
            lastOthersAt = 0L
            lastThroughputAt = 0L
            speedDue = false
            // The first look also starts counting the connections of this core (TropaNodeCounters).
            traffic.reset(counters(), System.currentTimeMillis())
            if (ConnectionLog.level >= LogLevel.VERBOSE) {
                val names = plan.nodes.joinToString(", ") { it.name.ifEmpty { it.tag.ifEmpty { "?" } } }
                record(ProbeEvent(System.currentTimeMillis(), "servers", "", null, "", ok = true, detail = names + (if (plan.balancerTag != null) " · balancer" else "")))
            }
        }

        /** The mobile side of the network under the VPN: country and generation. */
        private fun cellular(): Cellular.State {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return Cellular.read(context, null)
            val caps = runCatching { underlyingNetwork(cm)?.let { cm.getNetworkCapabilities(it) } }.getOrNull()
            return Cellular.read(context, caps)
        }

        /**
         * Abroad on mobile data without the user's consent for that country, everything through
         * Tropa stops while the VPN stays up: the core restarts in place with a config that blocks
         * all connections (LocalProxy.patchConfig). Returns whether the traffic is stopped now.
         */
        private fun guardRoaming(cell: Cellular.State): Boolean {
            // On the home network again the consents given abroad are forgotten.
            if (cell.home && Roaming.allowedMcc.isNotEmpty()) Roaming.allowedMcc = ""
            val block = Roaming.mustBlock(cell)
            if (block != Roaming.blocked) {
                Roaming.blocked = block
                record(
                    ProbeEvent(
                        System.currentTimeMillis(), "roaming", "", null, cell.networkMcc.orEmpty(), ok = !block,
                        detail = if (block) "traffic stopped" else "traffic allowed",
                    ),
                )
                reloadCore()
                loadPlan()
                if (block) TropaNotifier.notifyRoaming(context) else TropaNotifier.cancelRoaming(context)
            }
            if (block) {
                roamingMcc = cell.networkMcc
                publish(WatchPhase.ROAMING)
            }
            return block
        }

        /** Why the checks pause: roaming without checks there, or mobile data slower than 4G; null when they run. */
        private fun pauseReasonOf(cell: Cellular.State): String? = when {
            !Roaming.checksAllowed(cell) -> PAUSE_ROAMING
            cell.weak -> PAUSE_WEAK
            else -> null
        }

        private fun savedConfig(): String? {
            val guid = MmkvManager.getSelectServer() ?: return null
            return runCatching { CoreConfigManager.getV2rayConfig(context, guid) }.getOrNull()?.takeIf { it.status }?.content
        }

        /** Subscriptions are updated only while the VPN is on: every half an hour, when connected. */
        private suspend fun refreshSubscriptions() {
            while (currentCoroutineContext().isActive) {
                delay(SUBSCRIPTION_CHECK_MS)
                if (phase == WatchPhase.CONNECTED || phase == WatchPhase.CHECKS_OFF) {
                    refreshDueSubscriptions()
                    checkAppUpdate()
                }
            }
        }

        /** Once a day while the VPN is on: a newer Tropa version is announced with a notification (once per version). */
        private fun checkAppUpdate() {
            if (!AppUpdates.isDue()) return
            val release = AppUpdates.check().getOrNull() ?: return
            if (AppUpdates.takeNotification(release)) TropaNotifier.notifyUpdate(context, release.version)
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
            if (added.isNotEmpty()) probeAll(added.map { it.tag }, probeBytes())
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
            if (candidate == null || !confirmed(candidate.tag, checkServer(candidate.tag, probeBytes()))) {
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
                // A server checked by the self-test got its 128 KB already; a classic one downloads now.
                if (selector.stats(next)?.selftest != true && !economy && !throughputOk(next)) {
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
         * Checks all servers at once, one request each, and connects through the first one that
         * proves alive (at least 64 KB of the self-test in 5 s; a classic server also has to pass
         * the download check). The others keep their full time in the background; when all have
         * answered, the best of the live ones takes new connections (open ones stay).
         */
        private suspend fun establish(): Boolean {
            val tags = plan.nodes.map { it.tag }
            val answers = Channel<Pair<String, Check>>(Channel.UNLIMITED)
            val bytes = probeBytes()
            val probes = tags.associateWith { tag -> scope.async { checkServer(tag, bytes).also { answers.trySend(tag to it) } } }
            repeat(tags.size) {
                val (tag, check) = answers.receive()
                if (confirmed(tag, check)) {
                    select(tag)
                    lastThroughputAt = System.currentTimeMillis()
                    lastActiveCheckAt = lastThroughputAt
                    val pending = probes.filterValues { !it.isCompleted }
                    if (pending.isNotEmpty()) scope.launch { finishInBackground(pending) }
                    return true
                }
            }
            return false
        }

        /** A live server that may carry traffic: the self-test proved it, a classic one passes the download check. */
        private fun confirmed(tag: String, check: Check): Boolean = check.ok && (check.selftest || economy || passesDownload(tag))

        private fun passesDownload(tag: String): Boolean {
            select(tag)
            if (throughputOk(tag)) {
                lastThroughputAt = System.currentTimeMillis()
                return true
            }
            selector.record(tag, ok = false, latencyMs = null)
            return false
        }

        /** The servers that answer later still count: once all are in, the best live one takes over (no history yet to protect). */
        private suspend fun finishInBackground(pending: Map<String, Deferred<Check>>) {
            pending.values.forEach { runCatching { it.await() } }
            if (phase != WatchPhase.CONNECTED) return
            synchronized(steer) {
                val active = activeTag ?: return
                val best = selector.bestAlive() ?: return@synchronized
                val bestMs = selector.rankMs(best) ?: return@synchronized
                if (best != active && bestMs < (selector.rankMs(active) ?: Long.MAX_VALUE)) switchTo(best, "faster")
            }
            publish(WatchPhase.CONNECTED)
        }

        /**
         * Watches the active server while things work. Returns true when the checks pause or stop
         * (turned off, weak network, roaming), false when every server is dead.
         *
         * While the user's traffic flows through the current server (the core's counters), it is
         * alive and needs no test request; test requests would only compete with that traffic, so
         * the other servers and the speed wait too (the others at most up to their longest interval).
         */
        private suspend fun monitor(): Boolean {
            // Battery measurements cover steady watching only: an outage or a pause before is not comparable.
            energy.dropWindow()
            var nextDelay = intervalMs()
            while (currentCoroutineContext().isActive && CheckSettings.enabled) {
                val woken = waitBetweenChecks(nextDelay)
                refreshSettings()
                updateWifi()
                val cell = cellular()
                if (!CheckSettings.enabled || Roaming.mustBlock(cell) || pauseReasonOf(cell) != null) return true
                ensureOverride()
                if (subscriptionChanged.getAndSet(false)) applySubscription()
                val tag = activeTag ?: return false
                if (woken && energy.cancelQuiet()) recordEnergy(EnergyBudget.Event.QUIET_DROPPED)
                if (budgetRound()) {
                    // A quiet window: the checks pause to measure the phone without them.
                    nextDelay = (energy.quietUntil() - System.currentTimeMillis()).coerceIn(1_000L, EnergyBudget.QUIET_MS)
                    continue
                }
                val now = System.currentTimeMillis()
                val flows = traffic.look(counters(), now)
                // Every server's connections count for its history, not only the current one's.
                plan.nodes.forEach { node -> flows[node.tag]?.let { selector.addConnections(node.tag, it.conns, it.fails) } }
                saveHistory()
                val flow = flows[tag]
                flow?.kbps?.let { kbps -> selector.stats(tag)?.trafficKbps = kbps }
                if (flow?.failing == true) noteFailing(tag, flow)
                val carries = screenOn && flow?.carries == true
                val othersDue = plan.isBalanced && screenOn &&
                    now - lastOthersAt >= budgetedMs(CheckKind.OTHERS, cfg.othersIntervalSec) &&
                    (!carries || now - lastOthersAt >= CheckKind.OTHERS.corridor.maxSec * 1000L)
                if (carries && !othersDue) {
                    // The user's data comes through the current server: it is alive.
                    selector.record(tag, ok = true, latencyMs = null)
                    activeHealthy = true
                    checkedAt = now
                    lastActiveCheckAt = now
                    if (ConnectionLog.level == LogLevel.DEBUG) {
                        record(ProbeEvent(now, "flow", serverName(tag), null, "", ok = true, detail = flowDetail(flow)))
                    }
                    if (!steerAfter(tag)) return false
                    nextDelay = intervalMs()
                    logScale()
                    continue
                }
                val bytes = probeBytes(watching = true)
                // All servers are checked at the same time, the active one included.
                val tags = if (othersDue) plan.nodes.map { it.tag } else listOf(tag)
                val result = probeAll(tags, bytes).getValue(tag)
                lastActiveCheckAt = System.currentTimeMillis()
                if (othersDue) lastOthersAt = System.currentTimeMillis()
                // A failed check of the active server needs fresh data about the others to fail over.
                if (!result.ok && plan.isBalanced && !othersDue) {
                    probeAll(plan.nodes.map { it.tag }.filter { it != tag }, bytes)
                    lastOthersAt = System.currentTimeMillis()
                }
                activeHealthy = result.ok
                if (result.ok) {
                    lastLatency = result.latencyMs
                    checkedAt = System.currentTimeMillis()
                }
                val after = System.currentTimeMillis()
                val speedAt = budgetedMs(CheckKind.SPEED, cfg.downloadIntervalSec)
                if (speedSoon.getAndSet(false)) speedDue = true
                if (screenOn && result.ok && !carries && !economy && (speedDue || after - lastThroughputAt >= speedAt)) {
                    if (result.selftest) measureSpeed(tag)
                    else if (!throughputOk(tag)) selector.record(tag, ok = false, latencyMs = null)
                    lastThroughputAt = after
                    speedDue = false
                }
                if (!steerAfter(tag)) return false
                leaving?.let { gone ->
                    moveOff(gone)
                    publish(WatchPhase.CONNECTED)
                }
                if (result.ok) checkDownServers()
                nextDelay = if (result.ok) intervalMs() else RETRY_AFTER_FAILURE_MS
                logScale()
            }
            return true
        }

        /** Applies the selector's decision for the current server; false when every server is dead. */
        private fun steerAfter(tag: String): Boolean {
            synchronized(steer) {
                if (activeTag != tag) return true
                when (val decision = selector.evaluate(tag)) {
                    NodeSelector.Decision.Keep -> publish(WatchPhase.CONNECTED)
                    is NodeSelector.Decision.SwitchTo -> switchTo(
                        decision.tag,
                        if (decision.reason == NodeSelector.Reason.BETTER) "faster" else "previous one is down",
                    )

                    NodeSelector.Decision.AllDead -> return false
                }
            }
            return true
        }

        /**
         * Waits for the next check. With the screen on, the core's counters are looked at every few
         * seconds meanwhile: when most new connections through the current server fail, it is
         * checked right away (at most every [EARLY_CHECK_MS]). True when woken that way or by a signal.
         */
        private suspend fun waitBetweenChecks(totalMs: Long): Boolean {
            val until = System.currentTimeMillis() + totalMs
            while (true) {
                val left = until - System.currentTimeMillis()
                if (left <= 0) return false
                if (!screenOn) return waitForWake(left)
                if (waitForWake(minOf(left, COUNTER_POLL_MS))) return true
                val tag = activeTag ?: continue
                val now = System.currentTimeMillis()
                if (now - lastActiveCheckAt < EARLY_CHECK_MS) continue
                if (traffic.peek(counters(), now)[tag]?.failing == true) return true
            }
        }

        /** Connections through the current server keep failing (verbose level; at most every 10 minutes). */
        private fun noteFailing(tag: String, flow: TrafficWatch.Delta) {
            val now = System.currentTimeMillis()
            if (ConnectionLog.level < LogLevel.VERBOSE || now - lastFailingNoteAt < QUIET_LOG_MS) return
            lastFailingNoteAt = now
            record(ProbeEvent(now, "flow", serverName(tag), null, "", ok = false, detail = flowDetail(flow)))
        }

        private fun flowDetail(flow: TrafficWatch.Delta): String = buildList {
            add("${flow.down / 1024} KB in ${flow.ms / 1000} s")
            flow.kbps?.let { add(SpeedResult.label(it)) }
            add("connections ${flow.conns}, failed ${flow.fails}")
        }.joinToString(" · ")

        /** What earlier sessions learned about the servers of the plan (by fingerprint; not from another IP of the name). */
        private fun restoreHistory() {
            plan.nodes.forEach { node ->
                if (node.fingerprint.isEmpty()) return@forEach
                val history = runCatching { NodeHistoryStore.load(node.fingerprint) }.getOrNull() ?: return@forEach
                if (history.ip != null && history.ip != lastIpOf(node)) return@forEach
                selector.restore(node.tag, history)
            }
        }

        /** The LastIP of a server's name, null for a server given by IP or not proven yet. */
        private fun lastIpOf(node: Node): String? =
            Diagnosis.serverResource(node)?.host?.takeIf(ServerAddresses::isName)?.let(ServerAddresses::lastIp)

        /** Keeps what this session learned about the servers, at most every minute unless [force]. */
        private fun saveHistory(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - historySavedAt < HISTORY_SAVE_MS) return
            historySavedAt = now
            plan.nodes.forEach { node ->
                if (node.fingerprint.isEmpty()) return@forEach
                selector.snapshot(node.tag)?.let { history ->
                    runCatching { NodeHistoryStore.save(node.fingerprint, history.copy(ip = lastIpOf(node))) }
                        .onFailure { LogUtil.e(TAG, "cannot save the history of ${node.name}", it) }
                }
            }
        }

        /** The core's counters per server (an empty map if it cannot tell). */
        private fun counters(): Map<String, NodeCounter> =
            runCatching { TrafficWatch.parse(CoreServiceManager.coreController.tropaNodeCounters()) }.getOrDefault(emptyMap())

        /** The interval of the active server check after the battery budget, ms. */
        private fun intervalMs(): Long {
            val mode = screenMode()
            val kind = if (mode == ScreenMode.ON) CheckKind.ACTIVE else CheckKind.SCREEN_OFF
            val seed = if (mode == ScreenMode.ON) cfg.activeIntervalSec else cfg.screenOffIntervalSec
            return energy.intervalSec(kind, seed, mode, battery, planned(mode)) * 1000L * economyFactor()
        }

        private fun economyFactor(): Int = if (economy) ECONOMY_FACTOR else 1

        /** The self-test probe: 8 KB on a limited Wi-Fi; else 128 KB, or 64 KB for a watch round with the screen off. */
        private fun probeBytes(watching: Boolean = false): Long = when {
            economy -> SelfTest.ECONOMY_BYTES
            watching && !screenOn -> SelfTest.ALIVE_BYTES
            else -> SelfTest.PROBE_BYTES
        }

        /**
         * Looks at the Wi-Fi under the VPN: a limited one (or a metered one not answered about yet)
         * turns the economy on. About a metered one the person has not answered, Tropa asks once per
         * session: a silent notification with the answers, and the main screen shows the question too.
         */
        private fun updateWifi() {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val wifi = cm?.let { MeteredWifi.of(it, runCatching { underlyingNetwork(it) }.getOrNull()) }
            val limited = wifi?.let { MeteredWifi.limited(it.key) }
            val now = MeteredWifi.economy(wifi, limited)
            if (now != economy) {
                economy = now
                val why = when {
                    !now -> null
                    limited == true -> "limited Wi-Fi"
                    else -> "metered Wi-Fi, not answered yet"
                }
                record(
                    ProbeEvent(
                        System.currentTimeMillis(), "economy", "", null, if (now) "on" else "off", ok = true,
                        detail = why?.let { "$it: ${SelfTest.ECONOMY_BYTES / 1024} KB checks, $ECONOMY_FACTOR times rarer, no speed tests" },
                    ),
                )
            }
            val ask = wifi?.key?.takeIf { MeteredWifi.shouldAsk(wifi, limited) }
            if (ask != wifiAsk) {
                if (ask == null) TropaNotifier.cancelWifi(context)
                wifiAsk = ask
                if (ask != null && wifiAsked.add(ask)) TropaNotifier.askWifi(context, ask)
                publish(phase)
            }
        }

        /** The interval of a screen-on check of [kind] after the battery budget, ms. */
        private fun budgetedMs(kind: CheckKind, seedSec: Int): Long =
            energy.intervalSec(kind, seedSec, ScreenMode.ON, battery, planned(ScreenMode.ON)) * 1000L * economyFactor()

        private fun planned(mode: ScreenMode): Double {
            // A self-test check is one request; a classic one asks every latency address.
            val perCheck = if (activeTag?.let { selector.stats(it)?.selftest } == true) 1 else cfg.latencyUrls.size
            return EnergyBudget.plannedFetchesPerHour(mode, cfg, plan.nodes.size, perCheck)
        }

        private fun screenMode(): ScreenMode = if (screenOn) ScreenMode.ON else ScreenMode.OFF

        /** Reads the battery and moves the measurement windows; true while a quiet window pauses the checks. */
        private fun budgetRound(): Boolean {
            val now = System.currentTimeMillis()
            battery = runCatching { BatteryGauge.read(context) }.getOrDefault(BatteryState.UNKNOWN)
            energy.tick(now, screenMode(), battery, activeHealthy)?.let { event ->
                if (event == EnergyBudget.Event.BASE_MEASURED || event == EnergyBudget.Event.COST_MEASURED) {
                    runCatching { EnergyStore.saveModel(energy.model) }.onFailure { LogUtil.e(TAG, "cannot save the energy model", it) }
                }
                recordEnergy(event)
            }
            return energy.quiet(now)
        }

        /** Budget events go to the log from the verbose level. */
        private fun recordEnergy(event: EnergyBudget.Event) {
            if (ConnectionLog.level < LogLevel.VERBOSE) return
            val model = energy.model
            val detail = when (event) {
                EnergyBudget.Event.QUIET_STARTED -> "checks paused for ${EnergyBudget.QUIET_MS / 60_000} min to measure the battery without them"
                EnergyBudget.Event.QUIET_DROPPED -> "battery measurement dropped, checks resume"
                EnergyBudget.Event.BASE_MEASURED -> "phone without checks: ${model.baseUahPerHour?.roundToLong()} µAh/h"
                EnergyBudget.Event.COST_MEASURED ->
                    "one check request ≈ ${"%.1f".format(Locale.ROOT, model.uahPerFetch ?: 0.0)} µAh (${model.samples} measurements)"
            }
            record(ProbeEvent(System.currentTimeMillis(), "energy", "", null, "", ok = true, detail = detail))
        }

        /** Writes the budget factor when it moves the intervals by more than a tenth (verbose level). */
        private fun logScale() {
            if (ConnectionLog.level < LogLevel.VERBOSE) return
            val mode = screenMode()
            val scale = energy.scale(mode, battery, planned(mode))
            val last = loggedScale[mode] ?: 1.0
            val moved = if (scale.isInfinite() || last.isInfinite()) scale != last else abs(scale - last) > last * 0.1
            if (!moved) return
            loggedScale[mode] = scale
            val screen = if (mode == ScreenMode.ON) "screen on" else "screen off"
            val detail = if (scale.isInfinite()) {
                "$screen: battery saver or low battery, checks at their longest intervals"
            } else {
                "$screen: battery budget ×${"%.2f".format(Locale.ROOT, scale)}, the current server every ${intervalMs() / 1000} s"
            }
            record(ProbeEvent(System.currentTimeMillis(), "energy", "", null, "", ok = true, detail = detail))
        }

        private fun switchTo(tag: String, reason: String) {
            LogUtil.i(TAG, "switching new connections to $tag ($reason)")
            record(event(tag, "switch", "", ok = true, ProbeResult(ok = true), error = null, detail = reason))
            select(tag)
            speedDue = true
            publish(WatchPhase.CONNECTED)
        }

        private fun select(tag: String) = synchronized(steer) {
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

        /** Checks the servers in parallel; the results are recorded for the server choice. */
        private suspend fun probeAll(tags: List<String>, bytes: Long = SelfTest.PROBE_BYTES): Map<String, Check> = coroutineScope {
            tags.map { tag -> async { tag to checkServer(tag, bytes) } }.awaitAll().toMap()
        }

        /**
         * Checks one server the way its node supports: one request to the self-test of the node
         * (the first hop only), or the classic latency check. A self-test failure that is quick
         * (or brings other bytes) may mean a node without the self-test: the classic check then
         * tells, and a server that answers it is checked the classic way for some hours.
         */
        private suspend fun checkServer(tag: String, bytes: Long = SelfTest.PROBE_BYTES): Check {
            val key = plan.nodes.firstOrNull { it.tag == tag }?.fingerprint?.takeIf { it.isNotEmpty() } ?: tag
            val check = if (support.state(key) == SelfTestSupport.State.NO) {
                classic(tag, note = null)
            } else {
                val result = selfTest(tag, bytes)
                when {
                    result.aliveFor(bytes) -> {
                        if (support.yes(key)) noteMode(tag, selftest = true)
                        recordCheck(tag, selfTestEvent(tag, result, bytes))
                        // A probe under 64 KB tells nothing about the speed: servers are then compared by the first byte.
                        val fetchMs = if (bytes >= SelfTest.ALIVE_BYTES) result.fetchMs() else null
                        Check(ok = true, latencyMs = result.latencyMs, fetchMs = fetchMs, selftest = true, gateway = result.gatewayIp, exit = result.exitName)
                    }

                    result.maybeUnsupportedFor(bytes) -> {
                        val fallback = classic(tag, note = "self-test: " + shortError(result.error ?: "no answer"))
                        if (fallback.ok && support.no(key)) noteMode(tag, selftest = false)
                        fallback
                    }

                    else -> {
                        recordCheck(tag, selfTestEvent(tag, result, bytes))
                        Check(ok = false)
                    }
                }
            }
            selector.record(tag, check.ok, check.latencyMs, check.fetchMs)
            if (check.ok) {
                selector.stats(tag)?.selftest = check.selftest
                check.gateway?.let { selector.stats(tag)?.gateway = it }
                // Every self-test answer tells the exit anew: one without the header means the node names none.
                if (check.selftest) selector.stats(tag)?.exit = check.exit
            }
            return check
        }

        private suspend fun classic(tag: String, note: String?): Check {
            val result = probeLatency(tag, note)
            return Check(ok = result.ok, latencyMs = result.latencyMs.takeIf { result.ok })
        }

        private fun selfTest(tag: String, bytes: Long): SelfTestResult {
            energy.countFetch()
            val result = runCatching {
                SelfTestResult.parse(CoreServiceManager.coreController.tropaSelfTest(tag, SelfTest.url(bytes), SelfTest.TIMEOUT_MS, bytes))
            }.getOrElse { SelfTestResult(error = it.message ?: it.javaClass.simpleName) }
            traffic.ownBytes(tag, result.bytes)
            return result
        }

        private fun selfTestEvent(tag: String, result: SelfTestResult, bytes: Long): ProbeEvent {
            val ok = result.aliveFor(bytes)
            val shown = if (bytes >= SelfTest.ALIVE_BYTES) result.fetchMs() ?: result.totalMs else result.totalMs
            val probe = ProbeResult(ok = ok, status = result.status, latencyMs = shown, totalMs = result.totalMs, bytes = result.bytes)
            val error = if (ok) null else shortError(result.error ?: "no answer") + if (result.bytes > 0) " (${result.bytes / 1024} KB came)" else ""
            val detail = if (ConnectionLog.level >= LogLevel.VERBOSE && result.status > 0) {
                listOfNotNull("first byte ${result.latencyMs} ms", result.gatewayIp?.let { "gateway $it" }).joinToString(" · ")
            } else {
                null
            }
            return event(tag, "selftest", "", ok, probe, error = error, detail = detail)
        }

        /** The time-boxed speed of the current server (and the upload, if the user asked for it); never decides alive or dead. */
        private fun measureSpeed(tag: String) {
            val stats = selector.stats(tag)
            val down = speedTest(tag, upload = false)
            down.kbps?.let { stats?.speedKbps = it }
            recordResult(speedEvent(tag, "speed", down))
            if (CheckSettings.checkUpload && down.ok) {
                val up = speedTest(tag, upload = true)
                up.kbps?.let { stats?.uploadKbps = it }
                recordResult(speedEvent(tag, "upload", up))
            }
        }

        private fun speedTest(tag: String, upload: Boolean): SpeedResult {
            energy.countFetch()
            val url = if (upload) SelfTest.UPLOAD_URL else SelfTest.url(SelfTest.SPEED_BYTES)
            val result = runCatching {
                SpeedResult.parse(CoreServiceManager.coreController.tropaSpeedTest(tag, url, SelfTest.SPEED_MS, SelfTest.SPEED_BYTES, upload))
            }.getOrElse { SpeedResult(error = it.message ?: it.javaClass.simpleName) }
            if (!upload) traffic.ownBytes(tag, result.bytes)
            return result
        }

        private fun speedEvent(tag: String, kind: String, result: SpeedResult): ProbeEvent {
            val probe = ProbeResult(ok = result.ok, latencyMs = result.ms, totalMs = result.ms, bytes = result.bytes)
            return event(tag, kind, "", result.ok, probe, error = result.error?.let(::shortError), detail = result.kbps?.let { SpeedResult.label(it) })
        }

        /** How a server is checked changed: by the self-test of its node, or the classic way (verbose level). */
        private fun noteMode(tag: String, selftest: Boolean) {
            if (ConnectionLog.level < LogLevel.VERBOSE) return
            val detail = if (selftest) "the node answers the self-test: one request per check, to the node itself"
            else "no self-test on this node: checked through the check addresses"
            record(ProbeEvent(System.currentTimeMillis(), "mode", serverName(tag), null, "", ok = true, detail = detail))
        }

        private fun serverName(tag: String): String = plan.nodes.firstOrNull { it.tag == tag }?.name?.takeIf { it.isNotEmpty() } ?: tag

        /**
         * A server is alive when any of the latency addresses answers through it. All addresses are
         * asked at once and the first success counts; the others finish in the background.
         * The log gets one line per server: the address that answered, or the errors of all of them.
         */
        private suspend fun probeLatency(tag: String, note: String? = null): ProbeResult {
            val urls = cfg.latencyUrls
            val timeoutMs = cfg.latencyTimeoutSec * 1000L
            if (urls.isEmpty()) return ProbeResult(error = "no addresses")
            // At the debug level every address is written by itself, the late ones too.
            val perAddress = ConnectionLog.level == LogLevel.DEBUG
            val answers = Channel<Pair<String, ProbeResult>>(Channel.UNLIMITED)
            urls.forEach { url ->
                scope.launch {
                    val result = probe(tag, url, timeoutMs, LATENCY_MAX_BYTES)
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
                    recordCheck(tag, event(tag, "latency", url, ok = true, result, detail = note), write = !perAddress)
                    return result
                }
                errors[url] = result.error ?: "error"
            }
            val details = (errors.entries.map { (url, error) -> "${hostOf(url)}: ${shortError(error)}" } + listOfNotNull(note)).joinToString("; ")
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

        /** At least 64 KB (or the server's downloadMinKb) must come through: catches channels that stall after ~16 KB. */
        private fun throughputOk(tag: String): Boolean {
            val values = cfg
            return values.downloadUrls.any { url ->
                val result = probe(tag, url, values.downloadTimeoutSec * 1000L, maxOf(THROUGHPUT_MAX_BYTES, values.downloadMinBytes))
                val ok = result.ok && result.bytes >= values.downloadMinBytes
                val detail = if (ConnectionLog.level == LogLevel.DEBUG) httpStatus(result) else null
                recordResult(event(tag, "download", url, ok, result, error = result.error ?: if (!ok) "only ${result.bytes} bytes" else null, detail = detail))
                ok
            }
        }

        private fun probe(tag: String, url: String, timeoutMs: Long, maxBytes: Long): ProbeResult = try {
            energy.countFetch()
            val json = CoreServiceManager.coreController.tropaProbe(tag, url, timeoutMs, maxBytes)
            (gson.fromJson(json, ProbeResult::class.java) ?: ProbeResult(error = "empty result")).also { traffic.ownBytes(tag, it.bytes) }
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

        /**
         * Probes directly, outside the tunnel, what the network lets through: the servers, white-list
         * sites, foreign sites, and the gateway when nothing answers (see [Diagnosis]). Held for the
         * same network: [Diagnosis.TTL_MS], or one pause when the network itself seemed down (that
         * changes fast); a network change asks again.
         */
        private suspend fun diagnose(): Diagnosis.Result {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val network = cm?.let { runCatching { underlyingNetwork(it) }.getOrNull() }
            val key = network?.toString() ?: "none"
            val now = System.currentTimeMillis()
            diagnosis?.let { held ->
                val ttl = if (Diagnosis.offline(held.verdict)) NO_INTERNET_RECHECK_MS - 5_000 else Diagnosis.TTL_MS
                if (key == diagnosedNetwork && now - diagnosedAt < ttl) return held
            }
            val probe = network?.let { NetworkProbe(it, ServerAddresses::lastIp) }
            val result = if (cm == null || probe == null) {
                Diagnosis.Result(Diagnosis.Verdict.NO_NETWORK, emptyMap(), null)
            } else {
                val probes = probe.probeAll(Diagnosis.resources(plan.nodes, cfg.directUrls), Diagnosis.parallel(cellular()))
                val gateway = if (probes.values.none { it.reach == Diagnosis.Reach.AVAILABLE }) probe.gatewayAnswers(cm) else null
                Diagnosis.Result(Diagnosis.verdict(probes, gateway), probes, gateway)
            }
            diagnosis = result
            diagnosedAt = System.currentTimeMillis()
            diagnosedNetwork = key
            recordDiagnosis(result, startedAt = now)
            if (result.verdict == Diagnosis.Verdict.SERVERS_BLOCKED && probe != null) scope.launch { reportBlockedServers(result, probe) }
            return result
        }

        /**
         * The LastIP of every server name (see [ServerAddresses]): the name is resolved on the
         * physical network and the fresh answer and the LastIP are tried at the same time; the first
         * that answers TLS for the name becomes the LastIP. A DNS answer that includes the LastIP
         * needs no race. Returns whether a LastIP changed (the next core start uses it).
         */
        private suspend fun checkAddresses(): Boolean {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val network = runCatching { underlyingNetwork(cm) }.getOrNull() ?: return false
            val probe = NetworkProbe(network)
            var changed = false
            plan.nodes.mapNotNull { node -> Diagnosis.serverResource(node)?.let { node to it } }
                .filter { (_, server) -> ServerAddresses.isName(server.host) }
                .distinctBy { (_, server) -> server.host }
                .forEach { (node, server) ->
                    val last = ServerAddresses.lastIp(server.host)
                    val fresh = probe.lookupIpv4(server.host)
                    if (last != null && last in fresh) return@forEach
                    val candidates = (fresh + listOfNotNull(last)).distinct()
                    val winner = if (candidates.isEmpty()) null else probe.race(server.host, server.port, server.url, candidates)
                    if (winner != null && winner != last) {
                        ServerAddresses.setLastIp(server.host, winner)
                        changed = true
                    }
                    if (ConnectionLog.level >= LogLevel.VERBOSE || winner == null) {
                        val dns = fresh.joinToString(" ").ifEmpty { "no answer" }
                        val detail = "${server.host}: DNS $dns, last ${last ?: "none"} → ${winner ?: "nothing answers"}"
                        record(ProbeEvent(System.currentTimeMillis(), "address", serverName(node.tag), null, "", ok = winner != null, detail = detail))
                    }
                }
            return changed
        }

        /**
         * Some servers stay down while another carries the connection: in the background each of them
         * is probed directly, outside the tunnel (once a day per server and its LastIP). When its port
         * does not answer while foreign sites do, its address may be blocked from this network: that
         * goes to the log and, as a short report, to the server (the report goes out past the tunnel,
         * so the server sees the address of this network). The node's gateway (from its self-test
         * answers) tells which: it answers → the server's own address; silent too → its whole network.
         */
        private fun checkDownServers() {
            val active = activeTag ?: return
            plan.nodes.filter { it.tag != active && it.fingerprint.isNotEmpty() }.forEach { node ->
                if ((selector.stats(node.tag)?.consecutiveFails ?: 0) < UNREACHABLE_AFTER) return@forEach
                val server = Diagnosis.serverResource(node) ?: return@forEach
                val ip = ServerAddresses.lastIp(server.host) ?: server.host
                if (!UnreachableChecks.take(UnreachableChecks.key(node.fingerprint, ip))) return@forEach
                scope.launch { probeDownServer(node, server, ip) }
            }
        }

        private suspend fun probeDownServer(node: Node, server: Diagnosis.Resource, ip: String) {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
            val network = runCatching { underlyingNetwork(cm) }.getOrNull() ?: return
            val probe = NetworkProbe(network, ServerAddresses::lastIp)
            val direct = probe.probe(server)
            val foreign = probe.probeAll(Diagnosis.resources(emptyList(), emptyList()), parallel = true)
            val foreignUp = foreign.values.count { it.reach == Diagnosis.Reach.AVAILABLE }
            val gateway = if (direct.reach != Diagnosis.Reach.AVAILABLE && foreignUp > 0) checkGateway(node, probe) else null
            reportUnreachable(node.name.ifEmpty { node.tag }, server, ip, direct, foreignUp, foreign.size, gateway)
        }

        /** The node's gateway and whether it answers a ping from this network. */
        private data class GatewayCheck(val ip: String, val answers: Boolean)

        /** Null when the node never named its gateway (no self-test, or an older server). */
        private suspend fun checkGateway(node: Node, probe: NetworkProbe): GatewayCheck? {
            val gateway = selector.stats(node.tag)?.gateway ?: return null
            return GatewayCheck(gateway, probe.ping(gateway))
        }

        /** Logs a server's direct reachability; one that does not answer while the internet works is reported. */
        private fun reportUnreachable(
            name: String,
            server: Diagnosis.Resource,
            ip: String,
            direct: Diagnosis.Probe,
            foreignUp: Int,
            foreignAll: Int,
            gateway: GatewayCheck?,
        ) {
            val blocked = direct.reach != Diagnosis.Reach.AVAILABLE && foreignUp > 0
            val verdict = when {
                !blocked -> ""
                gateway == null -> " — maybe blocked from this network, reported to the server"
                gateway.answers -> "; its gateway ${gateway.ip} answers — the server's own address seems blocked here (or the server is off), reported to the server"
                else -> "; its gateway ${gateway.ip} is silent too — the server's whole network seems unreachable from here, reported to the server"
            }
            val detail = "${direct.describe()} at $ip; foreign sites $foreignUp/$foreignAll$verdict"
            record(ProbeEvent(System.currentTimeMillis(), "unreachable", name, "${server.host}:${server.port}", "", ok = !blocked, detail = detail))
            if (!blocked) return
            ProblemReports.saveUnreachable(
                context,
                mapOf(
                    "server" to name,
                    "address" to "${server.host}:${server.port}",
                    "ip" to ip,
                    "probe" to direct.describe(),
                    "reach" to direct.reach.name.lowercase(Locale.ROOT),
                    "foreignSites" to "$foreignUp/$foreignAll",
                    "gateway" to gateway?.ip,
                    "gatewayAnswers" to gateway?.answers,
                    "operator" to cellular().operator,
                ),
            )
            scope.launch { ProblemReports.uploadPending(context) }
        }

        /** Every server is down while foreign sites answer: each unreachable one is reported (once a day). */
        private suspend fun reportBlockedServers(result: Diagnosis.Result, probe: NetworkProbe) {
            val foreign = result.probes.filterKeys { it.group == Diagnosis.Group.FOREIGN }.values
            val up = foreign.count { it.reach == Diagnosis.Reach.AVAILABLE }
            result.probes.filterKeys { it.group == Diagnosis.Group.SERVER }.forEach { (server, direct) ->
                if (direct.reach == Diagnosis.Reach.AVAILABLE) return@forEach
                val node = plan.nodes.firstOrNull { Diagnosis.serverResource(it)?.host == server.host } ?: return@forEach
                val ip = ServerAddresses.lastIp(server.host) ?: server.host
                if (!UnreachableChecks.take(UnreachableChecks.key(node.fingerprint.ifEmpty { server.host }, ip))) return@forEach
                reportUnreachable(server.label, server, ip, direct, up, foreign.size, if (up > 0) checkGateway(node, probe) else null)
            }
        }

        /** The verdict as a log chip; every resource at the debug level. */
        private fun recordDiagnosis(result: Diagnosis.Result, startedAt: Long) {
            if (ConnectionLog.level == LogLevel.DEBUG) {
                result.probes.forEach { (resource, probe) ->
                    record(
                        ProbeEvent(
                            startedAt, "reach", resource.label, "${resource.host}:${resource.port}",
                            resource.group.name.lowercase(Locale.ROOT), ok = probe.reach == Diagnosis.Reach.AVAILABLE,
                            detail = probe.describe(),
                        ),
                    )
                }
            }
            val verdict = result.verdict.name.lowercase(Locale.ROOT)
            record(ProbeEvent(System.currentTimeMillis(), "diagnosis", "", null, verdict, ok = false, detail = result.summary()))
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
            diagnosis = null
            scope.launch {
                refreshDueSubscriptions()
                checkAppUpdate()
            }
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
            if (phase == WatchPhase.PAUSED && (this.phase != WatchPhase.PAUSED || pauseReason != loggedPause)) {
                record(ProbeEvent(System.currentTimeMillis(), "paused", "", null, pauseReason.orEmpty(), ok = true))
            }
            loggedPause = pauseReason.takeIf { phase == WatchPhase.PAUSED }
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
                    speedKbps = stats?.speedKbps ?: stats?.trafficKbps,
                    uploadKbps = stats?.uploadKbps,
                    selftest = stats?.selftest,
                    exit = stats?.exit,
                )
            }
            return WatchState(
                    phase = phase,
                    activeName = plan.nodes.firstOrNull { it.tag == activeTag }?.name?.takeIf { it.isNotEmpty() },
                    activeExit = activeTag?.let { selector.stats(it)?.exit },
                    latencyMs = lastLatency,
                    checkedAt = checkedAt,
                    attempt = attempt,
                    nextRetryAt = nextRetryAt,
                    nodes = nodes,
                    alwaysOn = if (q) vpn?.isAlwaysOn else null,
                    lockdown = if (q) vpn?.isLockdownEnabled else null,
                    connectedAt = if (phase == WatchPhase.CONNECTED || phase == WatchPhase.CHECKS_OFF || phase == WatchPhase.PAUSED) connectedAt else 0L,
                    pause = pauseReason.takeIf { phase == WatchPhase.PAUSED },
                    diagnosis = diagnosis?.verdict?.name?.takeIf { phase == WatchPhase.NO_SERVER || phase == WatchPhase.NO_INTERNET },
                    roamingMcc = roamingMcc.takeIf { phase == WatchPhase.ROAMING },
                    economy = economy,
                    wifiAsk = wifiAsk,
                    trafficBaseRx = trafficBaseRx,
                    trafficBaseTx = trafficBaseTx,
                )
        }
    }
}
