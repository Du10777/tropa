package io.github.du10777.tropa.ui.main

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.R
import com.v2ray.ang.handler.SettingsManager
import io.github.du10777.tropa.data.AddResult
import io.github.du10777.tropa.data.AppUpdates
import io.github.du10777.tropa.data.Connection
import io.github.du10777.tropa.data.Connections
import io.github.du10777.tropa.data.LinkCandidate
import io.github.du10777.tropa.data.LinkParser
import io.github.du10777.tropa.data.Roaming
import io.github.du10777.tropa.data.RoutingPreset
import io.github.du10777.tropa.data.SubscriptionRefresher
import io.github.du10777.tropa.data.TropaPrefs
import io.github.du10777.tropa.service.CoreState
import io.github.du10777.tropa.service.ServiceStateMonitor
import io.github.du10777.tropa.service.TropaServiceEventsReceiver
import io.github.du10777.tropa.service.WatchPhase
import io.github.du10777.tropa.service.WatchState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Clipboard text that looks like a connection and was not handled yet. */
data class ClipOffer(val fingerprint: String, val text: String)

data class MainUiState(
    val loaded: Boolean = false,
    val connections: List<Connection> = emptyList(),
    val active: Connection? = null,
    val core: CoreState = CoreState.Unknown,
    val error: String? = null,
    val connectedSince: Long = 0L,
    val clipOffer: ClipOffer? = null,
    val busy: Boolean = false,
    /** Watchdog report: channel check, active server, reconnects (null while the core is down). */
    val watch: WatchState? = null,
    /** The server's message (Announce header) of the active connection, unless the user closed it. */
    val announce: String? = null,
    /** A newer Tropa version to offer, with its download. */
    val update: UpdateOffer? = null,
    /** Roaming stopped the traffic: the question is shown, with the mobile data per day if known. */
    val roamingAsk: Boolean = false,
    val roamingDailyBytes: Long? = null,
)

/** A newer Tropa version offered on the main screen, and how its download goes. */
data class UpdateOffer(
    val release: AppUpdates.Release,
    /** Bytes downloaded so far; null while no download runs. */
    val progress: Long? = null,
    val total: Long = 0L,
    val failed: Boolean = false,
) {
    val downloading: Boolean get() = progress != null
}

sealed interface MainEvent {
    data class Message(@StringRes val text: Int, val arg: String? = null) : MainEvent

    /** A connection was added while the tunnel was running: switch to it. */
    data class SwitchTo(val connectionId: String) : MainEvent

    /** The update is downloaded and checked: hand it to the system installer. */
    data class Install(val file: File, val version: String) : MainEvent
}

class TropaMainViewModel(app: Application) : AndroidViewModel(app) {
    private val monitor = ServiceStateMonitor(app)
    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private val _events = Channel<MainEvent>(Channel.BUFFERED)
    val events: Flow<MainEvent> = _events.receiveAsFlow()

    private var updateCheck: Job? = null
    private var updateDownload: Job? = null

    /** The roaming country whose question was answered "Not now" in this run of the screen. */
    private var roamingDismissed: String? = null

    /** The roaming country the mobile data per day was looked up for. */
    private var roamingUsageFor: String? = null

    init {
        reload()
        monitor.start()
        // The upstream main screen copies geoip.dat / geosite.dat from the APK; without them configs
        // with geoip:/geosite: rules do not start. Tropa's screen replaces it, so it copies them too.
        viewModelScope.launch(Dispatchers.IO) { SettingsManager.initAssets(app, app.assets) }
        // Subscriptions are updated by Tropa itself, only while the VPN is on (see SubscriptionRefresher).
        SubscriptionRefresher.takeOver(app)
        // Share-link connections: Russia directly, the rest through the VPN (instead of upstream's China preset).
        RoutingPreset.applyOnce(app)
        viewModelScope.launch {
            combine(monitor.state, monitor.lastError, monitor.watch) { core, error, watch -> Triple(core, error, watch) }
                .collect { (core, error, watch) ->
                    val roamingMcc = watch?.roamingMcc?.takeIf { core == CoreState.Running && watch.phase == WatchPhase.ROAMING }
                    _state.update {
                        it.copy(
                            core = core,
                            error = error,
                            connectedSince = TropaPrefs.connectedSince,
                            watch = watch.takeIf { core == CoreState.Running },
                            roamingAsk = when {
                                roamingMcc == null -> false
                                it.roamingAsk -> true
                                else -> roamingMcc != roamingDismissed
                            },
                        )
                    }
                    if (roamingMcc != null && state.value.roamingAsk && roamingUsageFor != roamingMcc) {
                        roamingUsageFor = roamingMcc
                        viewModelScope.launch(Dispatchers.IO) {
                            // Nothing used over mobile data yet (or unknown): the dialog leaves the line out.
                            val bytes = Roaming.averageDailyMobileBytes(getApplication())?.takeIf { it > 0 }
                            _state.update { it.copy(roamingDailyBytes = bytes) }
                        }
                    }
                }
        }
        viewModelScope.launch {
            delay(SILENCE_TIMEOUT_MS)
            monitor.resolveSilence()
        }
    }

    fun reload() {
        val connections = Connections.list()
        val activeId = Connections.activeId(connections)
        val active = connections.firstOrNull { it.id == activeId }
        _state.update { state ->
            state.copy(
                loaded = true,
                connections = connections,
                active = active,
                announce = active?.announce?.takeIf { fingerprint(it) != TropaPrefs.dismissedAnnounce(active.id) },
            )
        }
    }

    fun onResumed() {
        reload()
        monitor.requestState()
        lookForUpdate()
    }

    /** Once a day GitHub is asked for a newer version; one found earlier (also by the watchdog) is offered. */
    private fun lookForUpdate() {
        if (updateCheck?.isActive == true || updateDownload?.isActive == true) return
        updateCheck = viewModelScope.launch(Dispatchers.IO) {
            if (AppUpdates.isDue()) AppUpdates.check()
            val release = AppUpdates.available()?.takeIf { AppUpdates.enabled && AppUpdates.shouldOffer(it) }
            _state.update { state ->
                when {
                    state.update?.downloading == true -> state
                    release == null -> state.copy(update = null)
                    state.update?.release == release -> state
                    else -> state.copy(update = UpdateOffer(release))
                }
            }
        }
    }

    /** "Continue" in roaming: data may go through Tropa in this country, the watchdog lifts the block. */
    fun allowRoaming() {
        val mcc = state.value.watch?.roamingMcc ?: return
        Roaming.allowedMcc = mcc
        TropaServiceEventsReceiver.wakeWatchdog(getApplication())
        _state.update { it.copy(roamingAsk = false) }
    }

    fun dismissRoaming() {
        roamingDismissed = state.value.watch?.roamingMcc
        _state.update { it.copy(roamingAsk = false) }
    }

    fun askRoamingAgain() {
        _state.update { it.copy(roamingAsk = it.watch?.phase == WatchPhase.ROAMING) }
    }

    fun postponeUpdate() {
        state.value.update?.release?.let(AppUpdates::postpone)
        _state.update { it.copy(update = null) }
    }

    fun downloadUpdate() {
        val offer = state.value.update ?: return
        val apk = offer.release.apk ?: return
        if (updateDownload?.isActive == true) return
        _state.update { it.copy(update = offer.copy(progress = 0L, total = apk.size, failed = false)) }
        updateDownload = viewModelScope.launch(Dispatchers.IO) {
            val file = try {
                AppUpdates.download(getApplication(), apk) { done, total ->
                    _state.update { state -> state.update?.let { state.copy(update = it.copy(progress = done, total = total)) } ?: state }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _state.update { state -> state.copy(update = state.update?.copy(progress = null, failed = file == null)) }
            if (file != null) {
                _state.update { it.copy(update = null) }
                _events.send(MainEvent.Install(file, offer.release.version))
            }
        }
    }

    fun cancelUpdate() {
        updateDownload?.cancel()
        _state.update { state -> state.copy(update = state.update?.copy(progress = null)) }
    }

    /** The switch was turned on; if the service never reports back, fall back to "disconnected". */
    fun markStarting() {
        monitor.markStarting()
        viewModelScope.launch {
            delay(START_TIMEOUT_MS)
            if (state.value.core != CoreState.Starting) return@launch
            monitor.requestState()
            delay(SILENCE_TIMEOUT_MS)
            if (state.value.core == CoreState.Starting) monitor.markStopped()
        }
    }

    fun onClipboardText(text: String?) {
        val offer = text?.takeIf { it.isNotBlank() }?.let { clip ->
            val fingerprint = fingerprint(clip)
            // Only our own xsub:// links are offered: any copied https:// address would pop up otherwise.
            val candidate = LinkParser.findTropaLink(clip)
            when {
                fingerprint == TropaPrefs.handledClip -> null
                candidate == null -> null
                state.value.connections.any { it.url == candidate.url } -> null
                else -> ClipOffer(fingerprint, clip)
            }
        }
        _state.update { it.copy(clipOffer = offer) }
    }

    fun dismissAnnounce() {
        val active = state.value.active ?: return
        active.announce?.let { TropaPrefs.setDismissedAnnounce(active.id, fingerprint(it)) }
        _state.update { it.copy(announce = null) }
    }

    fun dismissClipOffer() {
        state.value.clipOffer?.let { TropaPrefs.handledClip = it.fingerprint }
        _state.update { it.copy(clipOffer = null) }
    }

    /** Adds whatever connection [text] holds; [fallbackName] names connections without a title. */
    fun importText(text: String?, fallbackName: String) {
        val candidate = LinkParser.parse(text)
        if (candidate == null) {
            send(MainEvent.Message(R.string.tropa_import_not_found))
            return
        }
        if (state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                when (candidate) {
                    is LinkCandidate.Subscription -> Connections.addSubscription(candidate)
                    is LinkCandidate.Configs -> Connections.addConfigs(candidate.text, fallbackName)
                }
            }
            val handled = result is AddResult.Added || result is AddResult.AlreadyAdded
            if (handled && text != null) TropaPrefs.handledClip = fingerprint(text)
            when (result) {
                is AddResult.Added -> {
                    activate(result.id)
                    send(MainEvent.Message(R.string.tropa_import_added))
                }

                is AddResult.AlreadyAdded -> {
                    activate(result.id)
                    send(MainEvent.Message(R.string.tropa_import_exists))
                }

                is AddResult.NetworkError -> send(MainEvent.Message(R.string.tropa_import_network_error, result.message))
                AddResult.Unrecognized -> send(MainEvent.Message(R.string.tropa_import_unrecognized))
            }
            _state.update { it.copy(busy = false, clipOffer = if (handled) null else it.clipOffer) }
            reload()
        }
    }

    private fun activate(id: String) {
        val wasActive = state.value.active?.id == id
        Connections.setActive(id)
        if (!wasActive && state.value.core == CoreState.Running) send(MainEvent.SwitchTo(id))
    }

    private fun send(event: MainEvent) {
        _events.trySend(event)
    }

    override fun onCleared() {
        monitor.stop()
        super.onCleared()
    }

    private companion object {
        const val SILENCE_TIMEOUT_MS = 1500L
        const val START_TIMEOUT_MS = 20_000L

        fun fingerprint(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.trim().toByteArray(Charsets.UTF_8))
                .take(12)
                .joinToString("") { "%02x".format(it) }
    }
}
