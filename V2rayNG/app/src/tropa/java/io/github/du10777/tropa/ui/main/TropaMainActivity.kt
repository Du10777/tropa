package io.github.du10777.tropa.ui.main

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.enums.Language
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.base.HelperBaseComponentActivity
import com.v2ray.ang.ui.main.MainActivity
import io.github.du10777.tropa.data.AppUpdates
import io.github.du10777.tropa.data.Connections
import io.github.du10777.tropa.data.ProfileDetails
import io.github.du10777.tropa.data.SubscriptionRefresher
import io.github.du10777.tropa.ui.settings.TropaGeoActivity
import io.github.du10777.tropa.data.TropaPrefs
import io.github.du10777.tropa.service.CoreState
import io.github.du10777.tropa.service.TropaServiceEventsReceiver
import io.github.du10777.tropa.ui.common.ConfirmDialog
import io.github.du10777.tropa.ui.common.PermissionExplanationDialog
import io.github.du10777.tropa.ui.connections.TropaConnectionsActivity
import io.github.du10777.tropa.ui.link.TropaLinkActivity
import io.github.du10777.tropa.ui.log.LogActions
import io.github.du10777.tropa.ui.log.TropaLogActivity
import io.github.du10777.tropa.ui.permissions.TropaPermission
import io.github.du10777.tropa.ui.permissions.TropaPermissionRequester
import io.github.du10777.tropa.ui.settings.TropaSettingsActivity
import io.github.du10777.tropa.ui.theme.TropaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Launcher screen of Tropa: one connection, one switch, everything else one tap away. */
class TropaMainActivity : HelperBaseComponentActivity() {

    private val viewModel: TropaMainViewModel by viewModels()
    private val permissions = TropaPermissionRequester(this)

    /** A permission explanation waiting for an answer, then what to do next. */
    private class PendingExplanation(val permission: TropaPermission, val onYes: () -> Unit, val onNo: () -> Unit)

    private var explanation by mutableStateOf<PendingExplanation?>(null)
    private var showAddSheet by mutableStateOf(false)
    private var details by mutableStateOf<List<ProfileDetails>?>(null)

    /** A yes/no question waiting for an answer (delete, disconnect with lockdown). */
    private class PendingConfirm(val text: String, val note: String?, val onYes: () -> Unit)

    private var confirm by mutableStateOf<PendingConfirm?>(null)
    private var lastClipStamp = -1L

    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) startCore() else toast(R.string.tropa_perm_vpn_reason)
    }

    private val connectionsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.reload()
        if (result.data?.getBooleanExtra(TropaConnectionsActivity.EXTRA_OPEN_ADD, false) == true) showAddSheet = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Tropa speaks Russian by default, whatever the phone language; Settings can change it.
        if (!TropaPrefs.localeInitialized) {
            TropaPrefs.localeInitialized = true
            AppLocaleManager.setApplicationLanguage(Language.RUSSIAN.code)
        }
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            val imported = handleImportIntent(intent)
            if (!imported && TropaPrefs.advancedMode && intent?.action == Intent.ACTION_MAIN) {
                startActivity(Intent(this, MainActivity::class.java))
            }
            if (TropaPermission.PHONE_STATE.shouldAutoAsk(this)) explain(TropaPermission.PHONE_STATE)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect(::onEvent)
            }
        }
        lifecycleScope.launch {
            viewModel.state.map { it.core }.distinctUntilChanged().collect { core ->
                if (core == CoreState.Running && TropaPermission.BATTERY.shouldAutoAsk(this@TropaMainActivity)) {
                    explain(TropaPermission.BATTERY)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleImportIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResumed()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android 10+ only lets the focused app read the clipboard.
        if (hasFocus) checkClipboard()
    }

    @Composable
    override fun ScreenContent() {
        TropaTheme {
            val state by viewModel.state.collectAsStateWithLifecycle()
            TropaMainScreen(
                state = state,
                showAddSheet = showAddSheet,
                details = details,
                actions = MainActions(
                    onToggle = { on -> if (on) connect() else disconnect() },
                    onAddCopied = {
                        showAddSheet = false
                        importClipboard()
                    },
                    onScanQr = {
                        showAddSheet = false
                        scanQr()
                    },
                    onAddFile = {
                        showAddSheet = false
                        pickFile()
                    },
                    onOpenAddSheet = { showAddSheet = true },
                    onCloseAddSheet = { showAddSheet = false },
                    onOpenConnections = { connectionsLauncher.launch(Intent(this, TropaConnectionsActivity::class.java)) },
                    onOpenSettings = { startActivity(Intent(this, TropaSettingsActivity::class.java)) },
                    onAcceptClip = { state.clipOffer?.let { viewModel.importText(it.text, defaultName()) } },
                    onDismissClip = viewModel::dismissClipOffer,
                    onDismissAnnounce = viewModel::dismissAnnounce,
                    onOpenUrl = { url -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) } },
                    onRefreshActive = ::refreshActive,
                    onDeleteActive = ::askDeleteActive,
                    onShowDetails = ::showDetails,
                    onCloseDetails = { details = null },
                    onShareLog = { LogActions.share(this) },
                    onOpenLog = { startActivity(Intent(this, TropaLogActivity::class.java)) },
                    onOpenGeo = { ref -> startActivity(TropaGeoActivity.intent(this, ref.file, ref.code)) },
                    onRoaming = viewModel::askRoamingAgain,
                ),
            )
            if (state.roamingAsk) {
                RoamingDialog(dailyBytes = state.roamingDailyBytes, onContinue = viewModel::allowRoaming, onLater = viewModel::dismissRoaming)
            }
            state.update?.let { offer ->
                UpdateDialog(
                    offer = offer,
                    // All releases: whoever skipped versions sees every change since theirs.
                    onChangelog = { openUrl(AppUpdates.RELEASES_PAGE) },
                    onUpdate = ::startUpdate,
                    onCancel = viewModel::cancelUpdate,
                    onLater = viewModel::postponeUpdate,
                )
            }
            confirm?.let { pending ->
                ConfirmDialog(
                    text = pending.text,
                    note = pending.note,
                    onConfirm = {
                        confirm = null
                        pending.onYes()
                    },
                    onDismiss = { confirm = null },
                )
            }
            explanation?.let { pending ->
                PermissionExplanationDialog(
                    permission = pending.permission,
                    onYes = {
                        explanation = null
                        pending.onYes()
                    },
                    onNo = {
                        explanation = null
                        TropaPrefs.setPermissionAsked(pending.permission.id)
                        pending.onNo()
                    },
                )
            }
        }
    }

    private fun onEvent(event: MainEvent) {
        when (event) {
            is MainEvent.Message -> toast(
                if (event.arg == null) getString(event.text) else getString(event.text, event.arg),
                long = true,
            )

            is MainEvent.SwitchTo -> Connections.serverToStart(event.connectionId)?.let { guid ->
                MmkvManager.setSelectServer(guid)
                LauncherManager.restartService(this)
            }

            is MainEvent.Install -> {
                // The installer stops the running app with its VPN; the new version turns it back on.
                AppUpdates.resumeVpnFor = if (viewModel.state.value.core == CoreState.Running) event.version else ""
                runCatching { startActivity(AppUpdates.installIntent(this, event.file)) }
                    .onFailure { toast(R.string.tropa_update_failed) }
            }
        }
    }

    /** "Update": the install permission first (asked once, explained), then the download. */
    private fun startUpdate() {
        val release = viewModel.state.value.update?.release ?: return
        // No build for this phone in the release: its page lists all of them.
        if (release.apk == null) return openUrl(release.page)
        val permission = TropaPermission.INSTALL
        if (!permission.isApplicable() || permission.isGranted(this)) return viewModel.downloadUpdate()
        explanation = PendingExplanation(
            permission = permission,
            onYes = {
                permissions.request(permission) {
                    if (permission.isGranted(this)) viewModel.downloadUpdate() else toast(R.string.tropa_update_no_permission, long = true)
                }
            },
            onNo = { toast(R.string.tropa_update_no_permission, long = true) },
        )
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    }

    /** Shows the explanation; on "Yes" asks Android, then continues with [next] either way. */
    private fun explain(permission: TropaPermission, next: () -> Unit = {}) {
        if (explanation != null) return
        explanation = PendingExplanation(
            permission = permission,
            onYes = { permissions.request(permission, next) },
            onNo = next,
        )
    }

    private fun connect() {
        val activeId = viewModel.state.value.active?.id
        val guid = activeId?.let(Connections::serverToStart)
        if (guid == null) {
            toast(R.string.tropa_no_connection)
            return
        }
        MmkvManager.setSelectServer(guid)
        if (TropaPermission.NOTIFICATIONS.shouldAutoAsk(this)) {
            explain(TropaPermission.NOTIFICATIONS) { requestVpnAndStart() }
        } else {
            requestVpnAndStart()
        }
    }

    private fun requestVpnAndStart() {
        if (!SettingsManager.isVpnMode()) {
            startCore()
            return
        }
        val prepare = VpnService.prepare(this) ?: return startCore()
        // Android asks for VPN consent with its own dialog; Tropa explains first.
        explanation = PendingExplanation(
            permission = TropaPermission.VPN,
            onYes = { vpnLauncher.launch(prepare) },
            onNo = { toast(R.string.tropa_perm_vpn_reason) },
        )
    }

    private fun startCore() {
        viewModel.markStarting()
        LauncherManager.startService(this)
    }

    private fun disconnect() {
        if (viewModel.state.value.watch?.lockdown == true) {
            // With "block connections without VPN" the phone stays offline until the VPN is back.
            confirm = PendingConfirm(getString(R.string.tropa_disconnect_lockdown), null) { LauncherManager.stopService(this) }
        } else {
            LauncherManager.stopService(this)
        }
    }

    private fun showDetails() {
        val id = viewModel.state.value.active?.id ?: return
        lifecycleScope.launch {
            details = withContext(Dispatchers.IO) { Connections.details(id) }
        }
    }

    private fun refreshActive() {
        val id = viewModel.state.value.active?.id ?: return
        val running = viewModel.state.value.core == CoreState.Running
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { SubscriptionRefresher.refresh(id) }
            toast(if (result != Connections.Refresh.FAILED) R.string.tropa_connection_updated else R.string.tropa_connection_update_failed)
            viewModel.reload()
            // The running tunnel takes the new servers over without a restart.
            if (result == Connections.Refresh.UPDATED && running) TropaServiceEventsReceiver.subscriptionChanged(this@TropaMainActivity)
        }
    }

    private fun askDeleteActive() {
        val active = viewModel.state.value.active ?: return
        val running = viewModel.state.value.core == CoreState.Running || viewModel.state.value.core == CoreState.Starting
        confirm = PendingConfirm(
            text = getString(R.string.tropa_connection_delete_confirm, active.name.ifEmpty { defaultName() }),
            note = if (running) getString(R.string.tropa_connection_delete_active_note) else null,
        ) {
            if (running) LauncherManager.stopService(this)
            Connections.delete(active.id)
            viewModel.reload()
        }
    }

    private fun importClipboard() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        viewModel.importText(text, defaultName())
    }

    private fun checkClipboard() {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        val description = clipboard.primaryClipDescription
        if (description == null || !description.hasMimeType("text/*")) {
            viewModel.onClipboardText(null)
            return
        }
        // Read each new clip once: Android 12+ shows a toast on every read.
        val stamp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) description.timestamp else 0L
        if (stamp != 0L && stamp == lastClipStamp) return
        lastClipStamp = stamp
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        viewModel.onClipboardText(text)
    }

    private fun scanQr() {
        val scan = { launchQRCodeScanner { result -> if (result != null) viewModel.importText(result, defaultName()) } }
        if (TropaPermission.CAMERA.shouldAutoAsk(this)) explain(TropaPermission.CAMERA, scan) else scan()
    }

    private fun pickFile() {
        launchFileChooser { uri -> if (uri != null) importFile(uri) }
    }

    private fun importFile(uri: Uri) {
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { readSmallTextFile(uri) }
            if (file == null) {
                toast(R.string.tropa_import_file_error)
            } else {
                viewModel.importText(file.second, file.first ?: defaultName())
            }
        }
    }

    /** Name without extension and content of a picked file, null if unreadable or too big. */
    private fun readSmallTextFile(uri: Uri): Pair<String?, String>? = try {
        var name: String? = null
        var size = -1L
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getString(0)?.substringBeforeLast('.')
                if (!cursor.isNull(1)) size = cursor.getLong(1)
            }
        }
        if (size > MAX_FILE_BYTES) {
            null
        } else {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { name to it.readText() }
        }
    } catch (e: Exception) {
        null
    }

    private fun handleImportIntent(intent: Intent?): Boolean {
        val text = intent?.getStringExtra(TropaLinkActivity.EXTRA_TEXT) ?: return false
        intent.removeExtra(TropaLinkActivity.EXTRA_TEXT)
        viewModel.importText(text, defaultName())
        return true
    }

    private fun defaultName(): String = getString(R.string.tropa_connection_default_name)

    private companion object {
        const val MAX_FILE_BYTES = 8L * 1024 * 1024
    }
}
