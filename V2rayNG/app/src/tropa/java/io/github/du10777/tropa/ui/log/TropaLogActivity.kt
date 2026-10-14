package io.github.du10777.tropa.ui.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
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
    private val scope = MainScope()

    /** The log as text, read and formatted off the main thread (it can be megabytes). */
    private suspend fun logText(context: Context): String = withContext(Dispatchers.IO) {
        ProblemReports.shareText(context.applicationContext, WatchStateStore.read())
    }

    /** Shares the log as a text file (long logs do not fit into an intent as plain text). */
    fun share(context: Context) {
        scope.launch {
            val app = context.applicationContext
            val uri = withContext(Dispatchers.IO) {
                val file = File(app.cacheDir, "tropa-connection-log.txt").apply { writeText(logText(app)) }
                FileProvider.getUriForFile(app, "${app.packageName}.cache", file)
            }
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.tropa_log_title))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.tropa_log_share))) }
        }
    }

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

/** The whole connection log, newest first, with its level and size, copy, share and clear. */
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
            LaunchedEffect(refresh) {
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
                    if (events.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.tropa_log_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(events.asReversed()) { event -> LogEntry(event) }
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
            "switch" -> TropaColors.BrandDark
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
                e.ok && e.kind == "download" -> stringResource(R.string.tropa_log_ok_bytes, e.latencyMs, Formatter.formatShortFileSize(context, e.bytes))
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

