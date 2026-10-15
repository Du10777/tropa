package io.github.du10777.tropa.ui.connections

import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import io.github.du10777.tropa.data.Connection
import io.github.du10777.tropa.data.Connections
import io.github.du10777.tropa.data.SubscriptionRefresher
import io.github.du10777.tropa.data.TropaPrefs
import io.github.du10777.tropa.service.TropaServiceEventsReceiver
import io.github.du10777.tropa.ui.common.ActionRow
import io.github.du10777.tropa.ui.common.ConfirmDialog
import io.github.du10777.tropa.ui.common.TropaTopBar
import io.github.du10777.tropa.ui.theme.TropaColors
import io.github.du10777.tropa.ui.theme.TropaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** List of connections: pick the active one, update a subscription, delete any of them. */
class TropaConnectionsActivity : BaseComponentActivity() {
    private var connections by mutableStateOf(Connections.list())
    private var activeId by mutableStateOf(Connections.activeId())
    private var refreshing by mutableStateOf<String?>(null)
    private var deleting by mutableStateOf<Connection?>(null)

    private val tunnelUp: Boolean get() = TropaPrefs.connectedSince > 0L

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        connections = Connections.list()
        activeId = Connections.activeId(connections)
    }

    @Composable
    override fun ScreenContent() {
        TropaTheme {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.systemBarsPadding()) {
                    TropaTopBar(stringResource(R.string.tropa_connections_title), onBack = ::finish)
                    if (connections.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.tropa_connections_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
                        ) {
                            items(connections, key = { it.id }) { connection ->
                                ConnectionRow(
                                    connection = connection,
                                    active = connection.id == activeId,
                                    refreshing = refreshing == connection.id,
                                    onSelect = { select(connection) },
                                    onRefresh = { refresh(connection) },
                                    onDelete = { deleting = connection },
                                )
                            }
                        }
                    }
                    Box(Modifier.padding(16.dp)) {
                        ActionRow(
                            icon = R.drawable.ic_add_24dp,
                            title = stringResource(R.string.tropa_nav_add),
                            hint = null,
                            onClick = {
                                setResult(RESULT_OK, Intent().putExtra(EXTRA_OPEN_ADD, true))
                                finish()
                            },
                        )
                    }
                }
            }
            deleting?.let { connection ->
                ConfirmDialog(
                    text = stringResource(R.string.tropa_connection_delete_confirm, connection.name),
                    note = if (connection.id == activeId && tunnelUp) stringResource(R.string.tropa_connection_delete_active_note) else null,
                    onConfirm = {
                        deleting = null
                        delete(connection)
                    },
                    onDismiss = { deleting = null },
                )
            }
        }
    }

    private fun select(connection: Connection) {
        if (connection.id == activeId) return
        Connections.setActive(connection.id)
        activeId = connection.id
        if (tunnelUp) {
            Connections.serverToStart(connection.id)?.let { guid ->
                MmkvManager.setSelectServer(guid)
                LauncherManager.restartService(this)
            }
        }
    }

    private fun refresh(connection: Connection) {
        if (refreshing != null) return
        refreshing = connection.id
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { SubscriptionRefresher.refresh(connection.id) }
            refreshing = null
            toast(if (result != Connections.Refresh.FAILED) R.string.tropa_connection_updated else R.string.tropa_connection_update_failed)
            reload()
            // The running tunnel takes the new servers over without a restart.
            if (result == Connections.Refresh.UPDATED && connection.id == activeId && tunnelUp) {
                TropaServiceEventsReceiver.subscriptionChanged(this@TropaConnectionsActivity)
            }
        }
    }

    private fun delete(connection: Connection) {
        if (connection.id == activeId) LauncherManager.stopService(this)
        Connections.delete(connection.id)
        reload()
    }

    companion object {
        const val EXTRA_OPEN_ADD = "io.github.du10777.tropa.extra.OPEN_ADD"
    }
}

@Composable
private fun ConnectionRow(
    connection: Connection,
    active: Boolean,
    refreshing: Boolean,
    onSelect: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = active,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(selectedColor = TropaColors.Brand),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    connection.name.ifEmpty { stringResource(R.string.tropa_connection_default_name) },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(details(connection), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (connection.isSubscription) {
                if (refreshing) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    }
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(painterResource(R.drawable.ic_cloud_download_24dp), contentDescription = stringResource(R.string.tropa_connection_refresh))
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(painterResource(R.drawable.ic_delete_24dp), contentDescription = stringResource(R.string.tropa_connection_delete))
            }
        }
    }
}

@Composable
private fun details(connection: Connection): String {
    val servers = pluralStringResource(R.plurals.tropa_card_servers, connection.serverCount, connection.serverCount)
    if (!connection.isSubscription) return "$servers · ${stringResource(R.string.tropa_connection_manual)}"
    if (connection.lastUpdated <= 0L) return servers
    val ago = DateUtils.getRelativeTimeSpanString(connection.lastUpdated, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    return "$servers · ${stringResource(R.string.tropa_connection_updated_at, ago)}"
}
