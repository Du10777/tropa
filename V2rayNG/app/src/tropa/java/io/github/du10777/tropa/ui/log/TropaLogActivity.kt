package io.github.du10777.tropa.ui.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.v2ray.ang.R
import com.v2ray.ang.extension.toast
import com.v2ray.ang.ui.base.BaseComponentActivity
import io.github.du10777.tropa.data.ConnectionLog
import io.github.du10777.tropa.data.LogLevel
import io.github.du10777.tropa.data.ProblemReports
import io.github.du10777.tropa.service.ProbeEvent
import io.github.du10777.tropa.service.WatchStateStore
import io.github.du10777.tropa.ui.common.ChoiceDialog
import io.github.du10777.tropa.ui.common.ConfirmDialog
import io.github.du10777.tropa.ui.link.TropaLinkActivity
import io.github.du10777.tropa.ui.theme.TropaColors
import io.github.du10777.tropa.ui.theme.TropaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Copying, sharing and clearing the connection log, used by the log screen and the details sheet. */
object LogActions {
    private const val MAX_CLIP_CHARS = 400_000

    /** Chosen lines up to this long are shared as text (a message); longer ones as a file. */
    private const val SHARE_TEXT_CHARS = 50_000
    private val scope = MainScope()

    /** The log as text, read and formatted off the main thread (it can be megabytes). */
    private suspend fun logText(context: Context): String = withContext(Dispatchers.IO) {
        ProblemReports.shareText(context.applicationContext, WatchStateStore.read())
    }

    /**
     * Shares the log as a text file named after the moment it was taken (long logs do not fit into
     * an intent as plain text); the files of earlier shares are removed.
     */
    fun share(context: Context) {
        scope.launch { shareFile(context, logText(context.applicationContext)) }
    }

    private suspend fun shareFile(context: Context, text: String) {
        val app = context.applicationContext
        val uri = withContext(Dispatchers.IO) {
            val dir = File(app.cacheDir, "shared").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, shareFileName(System.currentTimeMillis())).apply { writeText(text) }
            FileProvider.getUriForFile(app, "${app.packageName}.cache", file)
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.tropa_log_title))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(chooser(context, send, R.string.tropa_log_share)) }
    }

    /** Tropa itself takes shared text as a connection link: it is left out of the list. */
    private fun chooser(context: Context, send: Intent, title: Int): Intent =
        Intent.createChooser(send, context.getString(title))
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, TropaLinkActivity::class.java)))

    /** The chosen lines (oldest first) as text: a message when short, a file when long. */
    fun shareLines(context: Context, events: List<ProbeEvent>) {
        scope.launch {
            val text = withContext(Dispatchers.Default) { ConnectionLog.text(context, events) }
            if (text.length > SHARE_TEXT_CHARS) return@launch shareFile(context, text)
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
                .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.tropa_log_title))
            runCatching { context.startActivity(chooser(context, send, R.string.tropa_log_share_selected)) }
        }
    }

    fun copyLines(context: Context, events: List<ProbeEvent>) {
        scope.launch {
            val full = withContext(Dispatchers.Default) { ConnectionLog.text(context, events) }
            val text = if (full.length > MAX_CLIP_CHARS) "…\n" + full.takeLast(MAX_CLIP_CHARS) else full
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.tropa_log_title), text))
            context.toast(context.resources.getQuantityString(R.plurals.tropa_log_lines_copied, events.size, events.size))
        }
    }

    /** Deletes the chosen lines; [onDone] runs on the main thread when they are gone. */
    fun deleteLines(context: Context, events: List<ProbeEvent>, onDone: () -> Unit) {
        scope.launch {
            val gone = withContext(Dispatchers.IO) { ConnectionLog.delete(context.applicationContext, events) }
            context.toast(context.resources.getQuantityString(R.plurals.tropa_log_lines_deleted, gone, gone))
            onDone()
        }
    }

    /** tropa-connection-log_2026.10.15_13-45-12.txt (local time). */
    internal fun shareFileName(at: Long): String =
        "tropa-connection-log_" + SimpleDateFormat("yyyy.MM.dd_HH-mm-ss", Locale.ROOT).format(Date(at)) + ".txt"

    fun copy(context: Context) {
        scope.launch {
            val full = logText(context)
            val text = if (full.length > MAX_CLIP_CHARS) "…\n" + full.takeLast(MAX_CLIP_CHARS) else full
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.tropa_log_title), text))
            context.toast(R.string.tropa_log_copied)
        }
    }

    fun clear(context: Context) {
        ConnectionLog.clear(context)
        context.toast(R.string.tropa_log_cleared)
    }

    /** Sends the whole log to the server of the connection; [onDone] runs when it is over. */
    fun sendToServer(context: Context, onDone: () -> Unit) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { ProblemReports.sendNow(context.applicationContext, WatchStateStore.read()) }
            when (result) {
                ProblemReports.SendResult.Sent -> context.toast(R.string.tropa_log_sent)
                ProblemReports.SendResult.NoServer -> context.toast(R.string.tropa_log_send_no_server, long = true)
                is ProblemReports.SendResult.Failed -> context.toast(context.getString(R.string.tropa_log_send_failed, result.reason), long = true)
            }
            onDone()
        }
    }

    /** The VPN service reads the level on every entry, so the change applies at once. */
    fun setLevel(context: Context, level: LogLevel) {
        if (level == ConnectionLog.level) return
        ConnectionLog.level = level
        ConnectionLog.append(context, ProbeEvent(System.currentTimeMillis(), "level", "", null, level.name, ok = true))
    }

    fun setLimit(context: Context, kb: Int) {
        ConnectionLog.limitKb = kb
        ConnectionLog.trimToLimit(context)
    }
}

/**
 * The whole connection log, newest first, with its level and size, copy, share and clear. A long
 * press on a line starts choosing lines: then taps add and remove lines, and copy, share and delete
 * work on the chosen ones only. While lines are chosen the list does not move (new entries wait).
 */
class TropaLogActivity : BaseComponentActivity() {

    @Composable
    override fun ScreenContent() {
        TropaTheme {
            val context = LocalContext.current
            var events by remember { mutableStateOf<List<ProbeEvent>>(emptyList()) }
            var size by remember { mutableLongStateOf(0L) }
            var refresh by remember { mutableLongStateOf(0L) }
            var level by remember { mutableStateOf(ConnectionLog.level) }
            var limitKb by remember { mutableIntStateOf(ConnectionLog.limitKb) }
            var confirmClear by remember { mutableStateOf(false) }
            var choosingLevel by remember { mutableStateOf(false) }
            var choosingSize by remember { mutableStateOf(false) }
            var sending by remember { mutableStateOf(false) }
            // Chosen lines: indexes into [events] (oldest first), which stay put while any is chosen.
            var chosen by remember { mutableStateOf(emptySet<Int>()) }
            var confirmDelete by remember { mutableStateOf(false) }
            val choosing = chosen.isNotEmpty()
            fun chosenEvents(): List<ProbeEvent> = chosen.sorted().mapNotNull { events.getOrNull(it) }
            BackHandler(enabled = choosing) { chosen = emptySet() }
            LaunchedEffect(refresh, choosing) {
                if (choosing) return@LaunchedEffect
                var seen: Pair<Long, Long>? = null
                while (true) {
                    // re-read only when the service has written something new
                    val stamp = ConnectionLog.stamp(context)
                    if (stamp != seen) {
                        events = withContext(Dispatchers.IO) { ConnectionLog.read(context) }
                        size = stamp.first
                        seen = stamp
                    }
                    delay(2000)
                }
            }
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.systemBarsPadding()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (choosing) {
                            IconButton(onClick = { chosen = emptySet() }) {
                                Icon(painterResource(R.drawable.tropa_ic_close), contentDescription = stringResource(R.string.tropa_log_selection_close))
                            }
                            Text(
                                stringResource(R.string.tropa_log_selected, chosen.size),
                                fontSize = 19.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { chosen = events.indices.toSet() }) {
                                Icon(painterResource(R.drawable.ic_select_all_24dp), contentDescription = stringResource(R.string.tropa_log_select_all))
                            }
                            IconButton(onClick = {
                                LogActions.copyLines(context, chosenEvents())
                                chosen = emptySet()
                            }) {
                                Icon(painterResource(R.drawable.tropa_ic_copy), contentDescription = stringResource(R.string.tropa_log_copy_selected))
                            }
                            IconButton(onClick = {
                                LogActions.shareLines(context, chosenEvents())
                                chosen = emptySet()
                            }) {
                                Icon(painterResource(R.drawable.ic_share_24dp), contentDescription = stringResource(R.string.tropa_log_share_selected))
                            }
                            IconButton(onClick = { confirmDelete = true }) {
                                Icon(painterResource(R.drawable.ic_delete_24dp), contentDescription = stringResource(R.string.tropa_log_delete_selected))
                            }
                        } else {
                            IconButton(onClick = ::finish) {
                                Icon(painterResource(R.drawable.ic_arrow_back_24dp), contentDescription = stringResource(R.string.tropa_back))
                            }
                            Text(
                                stringResource(R.string.tropa_log_title),
                                fontSize = 19.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { LogActions.copy(context) }) {
                                Icon(painterResource(R.drawable.tropa_ic_copy), contentDescription = stringResource(R.string.tropa_log_copy))
                            }
                            IconButton(onClick = { LogActions.share(context) }) {
                                Icon(painterResource(R.drawable.ic_share_24dp), contentDescription = stringResource(R.string.tropa_log_share))
                            }
                            IconButton(onClick = { confirmClear = true }) {
                                Icon(painterResource(R.drawable.ic_delete_24dp), contentDescription = stringResource(R.string.tropa_log_clear))
                            }
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        Column {
                            LogSettingRow(stringResource(R.string.tropa_log_level), stringResource(level.title)) { choosingLevel = true }
                            HorizontalDivider(color = MaterialTheme.colorScheme.background, modifier = Modifier.padding(horizontal = 14.dp))
                            LogSettingRow(
                                stringResource(R.string.tropa_log_size_title),
                                stringResource(
                                    R.string.tropa_log_size,
                                    ConnectionLog.sizeLabel(context, size),
                                    ConnectionLog.sizeLabel(context, limitKb * 1024L),
                                ),
                            ) { choosingSize = true }
                        }
                    }
                    FilledTonalButton(
                        onClick = {
                            sending = true
                            LogActions.sendToServer(context) { sending = false }
                        },
                        enabled = !sending,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = TropaColors.Brand.copy(alpha = 0.22f),
                            contentColor = TropaColors.BrandDark,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    ) {
                        Icon(painterResource(R.drawable.tropa_ic_upload), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (sending) R.string.tropa_log_sending else R.string.tropa_log_send))
                    }
                    if (events.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.tropa_log_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                    } else {
                        // Both hints take two lines, so the list does not jump when choosing starts.
                        Text(
                            stringResource(if (choosing) R.string.tropa_log_choosing_hint else R.string.tropa_log_select_hint),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            minLines = 2,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
                        )
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            itemsIndexed(events.asReversed()) { position, event ->
                                val index = events.lastIndex - position
                                ChoosableLine(
                                    chosen = index in chosen,
                                    onTap = { if (choosing) chosen = chosen.toggled(index) },
                                    onLongPress = { chosen = chosen.toggled(index) },
                                ) { LogEntry(event) }
                            }
                        }
                    }
                }
            }
            if (confirmClear) {
                ConfirmDialog(
                    text = stringResource(R.string.tropa_log_clear_confirm),
                    onConfirm = {
                        confirmClear = false
                        LogActions.clear(context)
                        refresh++
                    },
                    onDismiss = { confirmClear = false },
                )
            }
            if (confirmDelete) {
                ConfirmDialog(
                    text = stringResource(R.string.tropa_log_delete_selected_confirm, chosen.size),
                    onConfirm = {
                        confirmDelete = false
                        LogActions.deleteLines(context, chosenEvents()) {
                            chosen = emptySet()
                            refresh++
                        }
                    },
                    onDismiss = { confirmDelete = false },
                )
            }
            if (choosingLevel) {
                ChoiceDialog(
                    title = stringResource(R.string.tropa_log_level),
                    hint = null,
                    options = LogLevel.entries,
                    selected = level,
                    label = { context.getString(it.title) },
                    note = { context.getString(it.hint) },
                    onSelect = {
                        LogActions.setLevel(context, it)
                        level = it
                        choosingLevel = false
                    },
                    onDismiss = { choosingLevel = false },
                )
            }
            if (choosingSize) {
                ChoiceDialog(
                    title = stringResource(R.string.tropa_log_size_title),
                    hint = stringResource(R.string.tropa_log_size_hint),
                    options = ConnectionLog.LIMIT_CHOICES_KB,
                    selected = limitKb,
                    label = { ConnectionLog.sizeLabel(context, it * 1024L) },
                    onSelect = {
                        LogActions.setLimit(context, it)
                        limitKb = it
                        choosingSize = false
                        refresh++
                    },
                    onDismiss = { choosingSize = false },
                )
            }
        }
    }
}

private fun Set<Int>.toggled(index: Int): Set<Int> = if (index in this) this - index else this + index

/** A log line that can be chosen: a long press starts choosing, a chosen line is tinted. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChoosableLine(chosen: Boolean, onTap: () -> Unit, onLongPress: () -> Unit, content: @Composable () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (chosen) TropaColors.Brand.copy(alpha = 0.22f) else Color.Transparent)
            .combinedClickable(
                onClick = onTap,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
            )
            .padding(horizontal = 2.dp, vertical = 2.dp),
    ) { content() }
}

/** A setting of the log: name on the left, value and an arrow on the right. */
@Composable
private fun LogSettingRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 14.sp, color = TropaColors.BrandDark, fontWeight = FontWeight.SemiBold)
        Icon(
            painterResource(R.drawable.tropa_ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** One log line: session markers stand out, notes are quiet, checks show server, address and result. */
@Composable
fun LogEntry(e: ProbeEvent) {
    val context = LocalContext.current
    val time = remember { SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT) }
    val headline = "${time.format(Date(e.at))} · ${ConnectionLog.title(context, e)}" + (if (e.server.isNotEmpty()) " · ${e.server}" else "")
    if (e.kind in ConnectionLog.SESSION_KINDS) {
        val color = when (e.kind) {
            "connected", "start" -> TropaColors.Connected
            "switch", "subscription" -> TropaColors.BrandDark
            "checks" -> if (e.ok) TropaColors.BrandDark else TropaColors.Problem
            "stop", "reload", "servers", "level" -> TropaColors.Idle
            "network" -> if (e.target == "none") TropaColors.Problem else TropaColors.Idle
            else -> TropaColors.Problem
        }
        Surface(shape = RoundedCornerShape(10.dp), color = color.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text(headline, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                e.detail?.takeIf { it.isNotEmpty() }?.let { Text(it, color = color, fontSize = 12.sp) }
            }
        }
        return
    }
    if (e.kind in ConnectionLog.NOTE_KINDS) {
        Column(Modifier.padding(start = 16.dp)) {
            Text(headline, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            e.detail?.takeIf { it.isNotEmpty() }?.let {
                Text(it, fontSize = 11.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace, color = if (e.ok) MaterialTheme.colorScheme.onSurfaceVariant else TropaColors.Problem)
            }
        }
        return
    }
    Row {
        Box(
            Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(if (e.ok) TropaColors.Connected else TropaColors.Problem)
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(headline, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            e.address?.takeIf { it.isNotEmpty() && e.server.isNotEmpty() && it !in e.server }?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (e.target.isNotEmpty()) {
                Text(e.target.removePrefix("https://"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val result = when {
                e.ok && e.kind in ConnectionLog.BYTE_KINDS -> stringResource(R.string.tropa_log_ok_bytes, e.latencyMs, Formatter.formatShortFileSize(context, e.bytes))
                e.ok -> stringResource(R.string.tropa_value_ms, e.latencyMs)
                else -> e.error.orEmpty()
            }
            if (result.isNotEmpty()) {
                Text(result, fontSize = 12.sp, color = if (e.ok) TropaColors.Connected else TropaColors.Problem)
            }
            e.detail?.takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

