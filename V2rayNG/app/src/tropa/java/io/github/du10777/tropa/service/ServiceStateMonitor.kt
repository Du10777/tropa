package io.github.du10777.tropa.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import io.github.du10777.tropa.data.TropaPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** State of the upstream core service as seen from the UI process. */
enum class CoreState { Unknown, Stopped, Starting, Running, Failed }

/**
 * Listens to the upstream service broadcasts (the same protocol the upstream main screen uses).
 * The service only answers MSG_REGISTER_CLIENT while it runs, so silence means it is stopped.
 */
class ServiceStateMonitor(context: Context) {
    private val app = context.applicationContext
    private val _state = MutableStateFlow(CoreState.Unknown)
    val state: StateFlow<CoreState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** Latest watchdog report (channel check, reconnects); null while the core is down. */
    private val _watch = MutableStateFlow(WatchStateStore.read())
    val watch: StateFlow<WatchState?> = _watch.asStateFlow()

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WatchStateStore.ACTION) {
                _watch.value = WatchStateStore.read()
                return
            }
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING, AppConfig.MSG_STATE_START_SUCCESS -> {
                    if (TropaPrefs.connectedSince == 0L) TropaPrefs.connectedSince = System.currentTimeMillis()
                    _lastError.value = null
                    _state.value = CoreState.Running
                }

                AppConfig.MSG_STATE_NOT_RUNNING, AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    TropaPrefs.connectedSince = 0L
                    _watch.value = null
                    if (_state.value != CoreState.Failed) _state.value = CoreState.Stopped
                }

                AppConfig.MSG_STATE_START_FAILURE -> {
                    TropaPrefs.connectedSince = 0L
                    _lastError.value = intent.getStringExtra("content")
                    _state.value = CoreState.Failed
                }
            }
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY).apply { addAction(WatchStateStore.ACTION) }
        ContextCompat.registerReceiver(app, receiver, filter, Utils.receiverFlags())
        registered = true
        requestState()
    }

    /** Asks a running service to report itself; call when the screen comes back. */
    fun requestState() {
        _watch.value = WatchStateStore.read()
        MessageHelper.sendMsg2Service(app, AppConfig.MSG_REGISTER_CLIENT, "")
    }

    /** No answer to [requestState] in time: the service is not running. */
    fun resolveSilence() {
        if (_state.value == CoreState.Unknown) {
            TropaPrefs.connectedSince = 0L
            _watch.value = null
            _state.value = CoreState.Stopped
        }
    }

    fun markStarting() {
        _lastError.value = null
        _state.value = CoreState.Starting
    }

    fun markStopped() {
        _state.value = CoreState.Stopped
    }

    fun stop() {
        if (!registered) return
        registered = false
        try {
            app.unregisterReceiver(receiver)
        } catch (e: Exception) {
            LogUtil.e("Tropa", "Failed to unregister service receiver", e)
        }
    }
}
