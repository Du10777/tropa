package io.github.du10777.tropa.ui.main

import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import io.github.du10777.tropa.data.GeoRule
import io.github.du10777.tropa.data.GeoRouting
import io.github.du10777.tropa.data.GeoRef
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import io.github.du10777.tropa.data.ProfileDetails
import io.github.du10777.tropa.service.CoreState
import io.github.du10777.tropa.service.WatchPhase
import io.github.du10777.tropa.service.WatchState
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
    val onRefreshActive: () -> Unit = {},
    val onDeleteActive: () -> Unit = {},
    val onShowDetails: () -> Unit = {},
    val onCloseDetails: () -> Unit = {},
    val onShareLog: () -> Unit = {},
    val onOpenLog: () -> Unit = {},
    val onOpenGeo: (GeoRef) -> Unit = {},
)

/**
 * The simple screen. Everything tappable sits at the bottom, within reach of the thumb: the
 * connection card with the switch on the right and the bottom bar (connections, add, settings).
 */
@Composable
fun TropaMainScreen(
    state: MainUiState,
    showAddSheet: Boolean,
    actions: MainActions,
    details: List<ProfileDetails>? = null,
) {
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
    if (details != null) DetailsSheet(details, state.watch, actions)
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
    val online = state.core == CoreState.Running &&
        (state.watch?.phase == WatchPhase.CONNECTED || state.watch?.phase == WatchPhase.CHECKS_OFF)
    if (online) {
        OnlineStats(state.watch?.connectedAt?.takeIf { it > 0 } ?: state.connectedSince, state.watch)
        TrafficCard(state.watch)
    }
    StatusBlock(state, onClick = if (state.active != null) actions.onShowDetails else null)
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
private fun OnlineStats(connectedSince: Long, watch: WatchState?) {
    val now = rememberNow()
    val seconds = if (connectedSince > 0) ((now - connectedSince) / 1000).coerceAtLeast(0) else 0
    val latency = watch?.latencyMs?.takeIf { watch.phase == WatchPhase.CONNECTED }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        StatTile(
            label = stringResource(R.string.tropa_stat_online),
            value = String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60),
            modifier = Modifier.weight(1f),
        )
        if (latency != null) {
            StatTile(
                label = stringResource(R.string.tropa_stat_latency),
                value = stringResource(R.string.tropa_value_ms, latency),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp, modifier = modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Current time, refreshed every second while shown. */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    return now
}

/** What the status block shows: colours, title and hint. */
private enum class StatusLook { IDLE, PROGRESS, GOOD, BAD }

private fun statusLook(state: MainUiState): StatusLook = when (state.core) {
    CoreState.Starting -> StatusLook.PROGRESS
    CoreState.Failed -> StatusLook.BAD
    CoreState.Stopped, CoreState.Unknown -> StatusLook.IDLE
    CoreState.Running -> when (state.watch?.phase) {
        null, WatchPhase.CHECKING -> StatusLook.PROGRESS
        WatchPhase.CONNECTED, WatchPhase.CHECKS_OFF -> StatusLook.GOOD
        WatchPhase.NO_SERVER, WatchPhase.NO_INTERNET -> StatusLook.BAD
    }
}

private fun accentOf(look: StatusLook): Color = when (look) {
    StatusLook.IDLE -> TropaColors.Idle
    StatusLook.PROGRESS -> TropaColors.Checking
    StatusLook.GOOD -> TropaColors.Connected
    StatusLook.BAD -> TropaColors.Problem
}

private fun containerOf(look: StatusLook): Color = when (look) {
    StatusLook.IDLE -> TropaColors.IdleContainer
    StatusLook.PROGRESS -> TropaColors.CheckingContainer
    StatusLook.GOOD -> TropaColors.ConnectedContainer
    StatusLook.BAD -> TropaColors.ProblemContainer
}

@Composable
private fun StatusBlock(state: MainUiState, onClick: (() -> Unit)? = null) {
    val look = statusLook(state)
    val accent = accentOf(look)
    val watch = state.watch
    val now = rememberNow()
    val title: String
    val hint: String
    when (state.core) {
        CoreState.Starting -> {
            title = stringResource(R.string.tropa_status_connecting)
            hint = stringResource(R.string.tropa_status_connecting_hint)
        }

        CoreState.Failed -> {
            title = stringResource(R.string.tropa_status_error)
            hint = state.error.orEmpty()
        }

        CoreState.Stopped, CoreState.Unknown -> {
            title = stringResource(R.string.tropa_status_disconnected)
            hint = stringResource(R.string.tropa_status_disconnected_hint)
        }

        CoreState.Running -> when (watch?.phase) {
            null, WatchPhase.CHECKING -> {
                title = stringResource(R.string.tropa_status_checking)
                hint = stringResource(R.string.tropa_status_checking_hint)
            }

            WatchPhase.CONNECTED -> {
                title = stringResource(R.string.tropa_status_connected)
                hint = listOfNotNull(
                    watch.checkedAt.takeIf { it > 0 }?.let { stringResource(R.string.tropa_status_checked_ago, agoText(now - it)) },
                    watch.activeName,
                ).joinToString(" · ")
            }

            WatchPhase.NO_SERVER -> {
                title = stringResource(R.string.tropa_status_no_server)
                val wait = ((watch.nextRetryAt - now) / 1000).coerceAtLeast(0).toInt()
                hint = stringResource(R.string.tropa_status_no_server_hint, watch.attempt, wait)
            }

            WatchPhase.NO_INTERNET -> {
                title = stringResource(R.string.tropa_status_no_internet)
                hint = stringResource(R.string.tropa_status_no_internet_hint)
            }

            WatchPhase.CHECKS_OFF -> {
                title = stringResource(R.string.tropa_status_connected)
                hint = stringResource(R.string.tropa_status_checks_off_hint)
            }
        }
    }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = containerOf(look),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val reconnecting = state.core == CoreState.Running && watch?.phase == WatchPhase.NO_SERVER
            if (look == StatusLook.PROGRESS || reconnecting) {
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
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = accent)
                if (hint.isNotEmpty()) {
                    Text(hint, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun agoText(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return if (seconds < 60) {
        stringResource(R.string.tropa_ago_seconds, seconds.toInt())
    } else {
        stringResource(R.string.tropa_ago_minutes, (seconds / 60).toInt())
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConnectionCard(connection: Connection, state: MainUiState, actions: MainActions) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val switchOn = state.core == CoreState.Running || state.core == CoreState.Starting
    val accent = accentOf(statusLook(state))
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                },
            ),
    ) {
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (connection.isSubscription) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tropa_connection_refresh)) },
                    onClick = {
                        menuOpen = false
                        actions.onRefreshActive()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tropa_menu_details)) },
                onClick = {
                    menuOpen = false
                    actions.onShowDetails()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tropa_connection_delete), color = TropaColors.Problem) },
                onClick = {
                    menuOpen = false
                    actions.onDeleteActive()
                },
            )
        }
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
                // Like OpenVPN Connect: while connecting and checking the switch turns into an
                // outlined pill with a spinner and cannot be tapped.
                if (statusLook(state) == StatusLook.PROGRESS) {
                    ConnectingSwitch(TropaColors.Checking, onCancel = { actions.onToggle(false) })
                } else {
                    Switch(
                        checked = switchOn,
                        onCheckedChange = actions.onToggle,
                        enabled = !state.busy,
                        colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = Color.White),
                        modifier = Modifier.scale(1.3f),
                    )
                }
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
    ModalBottomSheet(
        onDismissRequest = actions.onCloseAddSheet,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsSheet(details: List<ProfileDetails>, watch: WatchState?, actions: MainActions) {
    ModalBottomSheet(
        onDismissRequest = actions.onCloseDetails,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.tropa_menu_details),
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp),
            )
            if (details.isEmpty()) {
                Text(stringResource(R.string.tropa_details_no_servers), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (details.count { it.inUse } > 1) {
                Text(stringResource(R.string.tropa_details_together), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            details.forEach { profile -> ProfileBlock(profile, watch) }
            details.firstOrNull { it.inUse && it.geo.isNotEmpty() }?.let { GeoBlock(it.geo, actions.onOpenGeo) }
            LogHeader(actions)
        }
    }
}

@Composable
private fun ProfileBlock(profile: ProfileDetails, watch: WatchState?) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    profile.name.ifEmpty { stringResource(R.string.tropa_connection_default_name) },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (profile.inUse) {
                    Surface(shape = RoundedCornerShape(10.dp), color = TropaColors.Brand) {
                        Text(
                            stringResource(R.string.tropa_details_in_use),
                            color = TropaColors.OnBrand,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            if (profile.balanced) {
                Text(stringResource(R.string.tropa_details_balanced), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            profile.nodes.forEach { node ->
                val live = watch?.nodes?.firstOrNull { it.tag == node.tag || (node.name.isNotEmpty() && it.name == node.name) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(
                                when (live?.alive) {
                                    true -> TropaColors.Connected
                                    false -> TropaColors.Problem
                                    null -> TropaColors.Idle
                                }
                            )
                    )
                    Column(Modifier.weight(1f)) {
                        Text(listOfNotNull(node.protocol.ifEmpty { null }, node.address).joinToString(" · "), fontSize = 14.sp)
                        node.transport?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (live?.alive == true && live.latencyMs != null) {
                        Text(stringResource(R.string.tropa_value_ms, live.latencyMs), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (live?.active == true) {
                        Surface(shape = CircleShape, color = TropaColors.Brand, modifier = Modifier.size(8.dp)) {}
                    }
                }
            }
        }
    }
}

/** Inactive switch shown while connecting: outlined pill and rotating dots where the thumb would be. */
@Composable
private fun ConnectingSwitch(color: Color, onCancel: () -> Unit) {
    val rotation by rememberInfiniteTransition(label = "connecting").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1000, easing = LinearEasing)),
        label = "spinner",
    )
    Box(
        modifier = Modifier
            .size(width = 68.dp, height = 42.dp)
            .clip(RoundedCornerShape(21.dp))
            .clickable(onClick = onCancel)
            .border(3.dp, color, RoundedCornerShape(21.dp)),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Canvas(
            Modifier
                .padding(end = 9.dp)
                .size(24.dp)
                .rotate(rotation)
        ) {
            val dots = 8
            val radius = size.minDimension / 2 - 3.dp.toPx()
            for (i in 0 until dots) {
                val angle = Math.toRadians(i * 360.0 / dots)
                drawCircle(
                    color = color.copy(alpha = (i + 1f) / dots),
                    radius = 2.6.dp.toPx(),
                    center = Offset(center.x + radius * kotlin.math.cos(angle).toFloat(), center.y + radius * kotlin.math.sin(angle).toFloat()),
                )
            }
        }
    }
}

/** Speed now, a one-minute chart and the amount of this session (all VPN traffic is the app's). */
@Composable
private fun TrafficCard(watch: WatchState?) {
    val context = LocalContext.current
    val uid = remember { android.os.Process.myUid() }
    val history = remember { mutableStateListOf<Pair<Long, Long>>() }
    var totals by remember { mutableStateOf(0L to 0L) }
    LaunchedEffect(watch?.trafficBaseRx) {
        var lastRx = android.net.TrafficStats.getUidRxBytes(uid)
        var lastTx = android.net.TrafficStats.getUidTxBytes(uid)
        while (true) {
            delay(1000)
            val rx = android.net.TrafficStats.getUidRxBytes(uid)
            val tx = android.net.TrafficStats.getUidTxBytes(uid)
            history.add((rx - lastRx).coerceAtLeast(0) to (tx - lastTx).coerceAtLeast(0))
            while (history.size > 60) history.removeAt(0)
            lastRx = rx
            lastTx = tx
            val baseRx = watch?.trafficBaseRx?.takeIf { it > 0 } ?: rx
            val baseTx = watch?.trafficBaseTx?.takeIf { it > 0 } ?: tx
            totals = (rx - baseRx).coerceAtLeast(0) to (tx - baseTx).coerceAtLeast(0)
        }
    }
    val now = history.lastOrNull() ?: (0L to 0L)
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("↓ " + speedText(context, now.first), color = TropaColors.Connected, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(14.dp))
                Text("↑ " + speedText(context, now.second), color = TropaColors.BrandDark, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.tropa_traffic_now), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(vertical = 6.dp)
            ) {
                val peak = (history.maxOfOrNull { maxOf(it.first, it.second) } ?: 0L).coerceAtLeast(1024L).toFloat()
                fun line(values: List<Long>, color: Color) {
                    if (values.size < 2) return
                    val step = size.width / 59f
                    val offset = (60 - values.size) * step
                    val path = Path()
                    values.forEachIndexed { i, v ->
                        val x = offset + i * step
                        val y = size.height - size.height * (v / peak)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
                }
                line(history.map { it.first }, TropaColors.Connected)
                line(history.map { it.second }, TropaColors.Brand)
            }
            Text(
                stringResource(
                    R.string.tropa_traffic_session,
                    Formatter.formatShortFileSize(context, totals.first),
                    Formatter.formatShortFileSize(context, totals.second),
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun speedText(context: android.content.Context, bytesPerSecond: Long): String {
    val bits = bytesPerSecond * 8
    return when {
        bits >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f %s", bits / 1_000_000.0, context.getString(R.string.tropa_unit_mbps))
        else -> String.format(Locale.getDefault(), "%d %s", bits / 1000, context.getString(R.string.tropa_unit_kbps))
    }
}

/** Where the routing sends which addresses; geoip lists open as subnet lists. */
@Composable
private fun GeoBlock(rules: List<GeoRule>, onOpen: (GeoRef) -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.tropa_geo_routing), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            rules.forEach { rule ->
                val title = when (rule.target) {
                    "direct" -> R.string.tropa_geo_direct
                    "block" -> R.string.tropa_geo_block
                    else -> R.string.tropa_geo_proxy
                }
                Text(stringResource(title), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                rule.items.take(30).forEach { item ->
                    val ref = GeoRouting.parseRef(item)
                    Text(
                        item,
                        fontSize = 14.sp,
                        color = if (ref != null) TropaColors.Info else MaterialTheme.colorScheme.onSurface,
                        modifier = if (ref != null) Modifier.clickable { onOpen(ref) } else Modifier,
                    )
                }
                if (rule.items.size > 30) Text("… +${rule.items.size - 30}", fontSize = 13.sp)
            }
        }
    }
}

/** Only the head of the connection log: the title opens it, the share icon sends it. */
@Composable
private fun LogHeader(actions: MainActions) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .clickable(onClick = actions.onOpenLog)
                .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.tropa_log_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = actions.onShareLog) {
                    Icon(painterResource(R.drawable.ic_share_24dp), contentDescription = stringResource(R.string.tropa_log_share))
                }
            }
            Text("…", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
