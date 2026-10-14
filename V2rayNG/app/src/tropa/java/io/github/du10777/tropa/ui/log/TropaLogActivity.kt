package io.github.du10777.tropa.ui.log

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import io.github.du10777.tropa.data.ProblemReports
import io.github.du10777.tropa.service.ProbeEvent
import io.github.du10777.tropa.service.WatchStateStore
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
}

/** The whole connection log, newest first, with copy, share and clear. */
class TropaLogActivity : BaseComponentActivity() {

    @Composable
    override fun ScreenContent() {
        TropaTheme {
            val context = LocalContext.current
            var events by remember { mutableStateOf<List<ProbeEvent>>(emptyList()) }
            var size by remember { mutableLongStateOf(0L) }
            var refresh by remember { mutableLongStateOf(0L) }
            var confirmClear by remember { mutableStateOf(false) }
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
                    Text(
                        stringResource(
                            R.string.tropa_log_size,
                            ConnectionLog.sizeLabel(context, size),
                            ConnectionLog.sizeLabel(context, ConnectionLog.limitKb * 1024L),
                        ),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
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
        }
    }
}

/** One log line: session markers stand out, checks show server, address and result. */
@Composable
fun LogEntry(e: ProbeEvent) {
    val context = LocalContext.current
    val time = remember { SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT) }
    if (e.kind in ConnectionLog.SESSION_KINDS) {
        val color = when (e.kind) {
            "connected", "start" -> TropaColors.Connected
            "switch" -> TropaColors.BrandDark
            "stop", "reload" -> TropaColors.Idle
            else -> TropaColors.Problem
        }
        Surface(shape = RoundedCornerShape(10.dp), color = color.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
            Text(
                "${time.format(Date(e.at))} · ${ConnectionLog.kindLabel(context, e.kind)}" +
                    (if (e.server.isNotEmpty()) " · ${e.server}" else ""),
                color = color,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
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
            Text(
                "${time.format(Date(e.at))} · ${ConnectionLog.kindLabel(context, e.kind)}" +
                    (if (e.server.isNotEmpty()) " · ${e.server}" else ""),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
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
        }
    }
}
