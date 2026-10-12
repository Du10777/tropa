package io.github.du10777.tropa.ui.main

import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import io.github.du10777.tropa.data.Connection
import io.github.du10777.tropa.service.CoreState
import io.github.du10777.tropa.ui.common.ActionRow
import io.github.du10777.tropa.ui.theme.TropaColors
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Everything the main screen can ask the activity to do. */
class MainActions(
    val onToggle: (Boolean) -> Unit,
    val onAddCopied: () -> Unit,
    val onScanQr: () -> Unit,
    val onAddFile: () -> Unit,
    val onOpenAddSheet: () -> Unit,
    val onCloseAddSheet: () -> Unit,
    val onOpenConnections: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onAcceptClip: () -> Unit,
    val onDismissClip: () -> Unit,
)

/**
 * The simple screen. Everything tappable sits at the bottom, within reach of the thumb: the
 * connection card with the switch on the right and the bottom bar (connections, add, settings).
 */
@Composable
fun TropaMainScreen(state: MainUiState, showAddSheet: Boolean, actions: MainActions) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.systemBarsPadding()) {
            AppBar()
            if (state.busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = TropaColors.Brand)
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                val viewport = maxHeight
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = viewport)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = if (state.loaded && state.connections.isEmpty()) {
                        Arrangement.SpaceBetween
                    } else {
                        Arrangement.spacedBy(12.dp, Alignment.Bottom)
                    },
                ) {
                    if (state.loaded && state.connections.isEmpty()) {
                        EmptyState(actions)
                    } else {
                        ConnectionContent(state, actions)
                    }
                }
            }
            BottomBar(actions)
        }
    }
    if (showAddSheet) AddConnectionSheet(actions)
}

@Composable
private fun AppBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TropaLogo(30.dp)
        Text(stringResource(R.string.app_name), fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun TropaLogo(size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(TropaColors.Brand),
    ) {
        Image(painterResource(R.drawable.tropa_logo_glyph), contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun EmptyState(actions: MainActions) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TropaLogo(112.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.tropa_add_connection_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.tropa_add_connection_hint),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AddActions(actions)
    }
}

@Composable
private fun AddActions(actions: MainActions, container: Color = MaterialTheme.colorScheme.surface) {
    ActionRow(R.drawable.tropa_ic_paste, stringResource(R.string.tropa_add_copied_link), stringResource(R.string.tropa_add_copied_link_hint), actions.onAddCopied, container = container)
    ActionRow(R.drawable.tropa_ic_qr, stringResource(R.string.tropa_scan_qr), stringResource(R.string.tropa_scan_qr_hint), actions.onScanQr, container = container)
    ActionRow(R.drawable.ic_file_24dp, stringResource(R.string.tropa_add_file), stringResource(R.string.tropa_add_file_hint), actions.onAddFile, container = container)
}

@Composable
private fun ConnectionContent(state: MainUiState, actions: MainActions) {
    if (state.clipOffer != null) ClipboardBanner(actions)
    if (state.core == CoreState.Running) OnlineStats(state.connectedSince)
    StatusBlock(state)
    state.active?.let { ConnectionCard(it, state, actions) }
}

@Composable
private fun ClipboardBanner(actions: MainActions) {
    Surface(shape = RoundedCornerShape(18.dp), color = TropaColors.InfoContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.tropa_clipboard_banner),
                    color = TropaColors.Info,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = actions.onDismissClip) {
                    Icon(
                        painterResource(R.drawable.tropa_ic_close),
                        contentDescription = stringResource(R.string.tropa_dismiss),
                        tint = TropaColors.Info,
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = TropaColors.Info,
                modifier = Modifier
                    .padding(end = 10.dp)
                    .clickable(onClick = actions.onAcceptClip),
            ) {
                Text(
                    stringResource(R.string.tropa_add_copied_link),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
        }
    }
}

@Composable
private fun OnlineStats(connectedSince: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connectedSince) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val seconds = if (connectedSince > 0) ((now - connectedSince) / 1000).coerceAtLeast(0) else 0
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text(stringResource(R.string.tropa_stat_online), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun StatusBlock(state: MainUiState) {
    val (container, accent) = when (state.core) {
        CoreState.Running -> TropaColors.ConnectedContainer to TropaColors.Connected
        CoreState.Starting -> TropaColors.CheckingContainer to TropaColors.Checking
        CoreState.Failed -> TropaColors.ProblemContainer to TropaColors.Problem
        CoreState.Stopped, CoreState.Unknown -> TropaColors.IdleContainer to TropaColors.Idle
    }
    val title = when (state.core) {
        CoreState.Running -> R.string.tropa_status_connected
        CoreState.Starting -> R.string.tropa_status_connecting
        CoreState.Failed -> R.string.tropa_status_error
        CoreState.Stopped, CoreState.Unknown -> R.string.tropa_status_disconnected
    }
    val hint = when (state.core) {
        CoreState.Running -> stringResource(R.string.tropa_status_connected_hint)
        CoreState.Starting -> stringResource(R.string.tropa_status_connecting_hint)
        CoreState.Failed -> state.error.orEmpty()
        CoreState.Stopped, CoreState.Unknown -> stringResource(R.string.tropa_status_disconnected_hint)
    }
    Surface(shape = RoundedCornerShape(18.dp), color = container, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.core == CoreState.Starting) {
                CircularProgressIndicator(color = accent, strokeWidth = 3.dp, modifier = Modifier.size(18.dp))
            } else {
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
            }
            Column {
                Text(stringResource(title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = accent)
                if (hint.isNotEmpty()) {
                    Text(hint, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(connection: Connection, state: MainUiState, actions: MainActions) {
    val context = LocalContext.current
    val switchOn = state.core == CoreState.Running || state.core == CoreState.Starting
    val accent = when (state.core) {
        CoreState.Running -> TropaColors.Connected
        CoreState.Starting -> TropaColors.Checking
        else -> TropaColors.Problem
    }
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        connection.name.ifEmpty { stringResource(R.string.tropa_connection_default_name) },
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(cardSubtitle(connection), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = switchOn,
                    onCheckedChange = actions.onToggle,
                    enabled = !state.busy,
                    colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = Color.White),
                    modifier = Modifier.scale(1.3f),
                )
                Spacer(Modifier.width(6.dp))
            }
            val info = connection.info
            val fraction = info?.usedFraction
            if (info != null && fraction != null && info.total != null) {
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    color = TropaColors.Brand,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                )
                Spacer(Modifier.height(6.dp))
                Row {
                    Text(
                        stringResource(R.string.tropa_card_used, Formatter.formatShortFileSize(context, info.used)),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.tropa_card_of_total, Formatter.formatShortFileSize(context, info.total)),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (info != null && (info.total == null || info.total == 0L)) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.tropa_card_unlimited), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun cardSubtitle(connection: Connection): String {
    val expire = connection.info?.expire?.takeIf { it > 0 }
    return when {
        expire != null -> stringResource(
            R.string.tropa_card_expires,
            DateFormat.getDateInstance(DateFormat.LONG).format(Date(expire * 1000)),
        )

        connection.isSubscription -> pluralStringResource(R.plurals.tropa_card_servers, connection.serverCount, connection.serverCount)
        else -> stringResource(R.string.tropa_connection_manual)
    }
}

@Composable
private fun BottomBar(actions: MainActions) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            BottomItem(R.drawable.ic_subscriptions_24dp, stringResource(R.string.tropa_nav_connections), actions.onOpenConnections)
            Surface(
                shape = CircleShape,
                color = TropaColors.Brand,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .size(60.dp)
                    .offset(y = (-6).dp)
                    .clickable(onClick = actions.onOpenAddSheet),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(R.drawable.ic_add_24dp),
                        contentDescription = stringResource(R.string.tropa_nav_add),
                        tint = TropaColors.OnBrand,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            BottomItem(R.drawable.ic_settings_24dp, stringResource(R.string.tropa_nav_settings), actions.onOpenSettings)
        }
    }
}

@Composable
private fun BottomItem(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(110.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(26.dp))
        Text(label, fontSize = 12.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddConnectionSheet(actions: MainActions) {
    ModalBottomSheet(onDismissRequest = actions.onCloseAddSheet, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.tropa_add_connection_title),
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
            AddActions(actions, container = MaterialTheme.colorScheme.surfaceVariant)
            TextButton(onClick = actions.onCloseAddSheet, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.tropa_cancel), fontSize = 15.sp)
            }
        }
    }
}
