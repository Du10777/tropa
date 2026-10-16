package io.github.du10777.tropa.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.extension.toast
import com.v2ray.ang.ui.base.BaseComponentActivity
import io.github.du10777.tropa.data.CheckParams
import io.github.du10777.tropa.data.CheckSettings
import io.github.du10777.tropa.data.CheckSource
import io.github.du10777.tropa.data.CheckValues
import io.github.du10777.tropa.data.Connections
import io.github.du10777.tropa.service.TropaServiceEventsReceiver
import io.github.du10777.tropa.ui.common.TropaTopBar
import io.github.du10777.tropa.ui.theme.TropaColors
import io.github.du10777.tropa.ui.theme.TropaTheme

/**
 * Settings → Connection check: on/off, how often, the addresses used to check, and the rest of the
 * parameters. The server of the active connection may send its own (`Tropa-Checks`); what the user
 * changes here wins over them, and every value says where it comes from.
 */
class TropaChecksActivity : BaseComponentActivity() {

    @Composable
    override fun ScreenContent() {
        TropaTheme {
            val connectionId = remember { Connections.activeId() }
            val connectionName = remember { Connections.list().firstOrNull { it.id == connectionId }?.name.orEmpty() }
            val server = remember { CheckSettings.server(connectionId) }
            val base = remember { CheckSettings.base(connectionId) }
            var user by remember { mutableStateOf(CheckSettings.user()) }
            val values = user.over(base)
            var enabled by remember { mutableStateOf(CheckSettings.enabled) }
            var latency by remember { mutableStateOf(values.latencyUrls.joinToString("\n")) }
            var download by remember { mutableStateOf(values.downloadUrls.joinToString("\n")) }
            var direct by remember { mutableStateOf(values.directUrls.joinToString("\n")) }

            fun saved() {
                user = CheckSettings.user()
                val now = user.over(base)
                latency = now.latencyUrls.joinToString("\n")
                download = now.downloadUrls.joinToString("\n")
                direct = now.directUrls.joinToString("\n")
                TropaServiceEventsReceiver.wakeWatchdog(this@TropaChecksActivity)
                toast(R.string.tropa_checks_saved)
            }

            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.systemBarsPadding()) {
                    TropaTopBar(stringResource(R.string.tropa_settings_checks), onBack = ::finish)
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Card {
                            Row(
                                modifier = Modifier
                                    .clickable {
                                        enabled = !enabled
                                        CheckSettings.enabled = enabled
                                        TropaServiceEventsReceiver.wakeWatchdog(this@TropaChecksActivity)
                                    }
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.tropa_checks_enabled), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                    Text(stringResource(R.string.tropa_checks_enabled_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = {
                                        enabled = it
                                        CheckSettings.enabled = it
                                        TropaServiceEventsReceiver.wakeWatchdog(this@TropaChecksActivity)
                                    },
                                    colors = SwitchDefaults.colors(checkedTrackColor = TropaColors.Brand, checkedThumbColor = Color.White),
                                )
                            }
                        }
                        if (server != null) {
                            Card {
                                Column(Modifier.padding(16.dp)) {
                                    Text(stringResource(R.string.tropa_checks_server_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TropaColors.BrandDark)
                                    Text(
                                        stringResource(R.string.tropa_checks_server_text, connectionName),
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        Card {
                            Column(Modifier.padding(16.dp)) {
                                Text(stringResource(R.string.tropa_checks_interval), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                val choices = (CheckSettings.ACTIVE_INTERVAL_CHOICES + base.activeIntervalSec + values.activeIntervalSec).distinct().sorted()
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    choices.forEach { seconds ->
                                        FilterChip(
                                            selected = values.activeIntervalSec == seconds,
                                            onClick = {
                                                CheckSettings.setActiveInterval(seconds, base)
                                                user = CheckSettings.user()
                                                TropaServiceEventsReceiver.wakeWatchdog(this@TropaChecksActivity)
                                            },
                                            label = { Text(stringResource(R.string.tropa_checks_interval_value, seconds)) },
                                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = TropaColors.Brand, selectedLabelColor = Color.White),
                                        )
                                    }
                                }
                                SourceText(CheckSettings.source(user.activeIntervalSec, server?.activeIntervalSec))
                            }
                        }
                        Text(stringResource(R.string.tropa_checks_urls_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        UrlField(stringResource(R.string.tropa_checks_latency_urls), latency, CheckSettings.source(user.latencyUrls, server?.latencyUrls)) { latency = it }
                        UrlField(
                            stringResource(R.string.tropa_checks_throughput_urls, values.downloadMinKb),
                            download,
                            CheckSettings.source(user.downloadUrls, server?.downloadUrls),
                        ) { download = it }
                        UrlField(stringResource(R.string.tropa_checks_direct_urls), direct, CheckSettings.source(user.directUrls, server?.directUrls)) { direct = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                CheckSettings.resetUser()
                                saved()
                            }) { Text(stringResource(if (server != null) R.string.tropa_checks_reset_server else R.string.tropa_checks_reset)) }
                            TextButton(onClick = {
                                CheckSettings.setUrls(CheckSettings.parseUrls(latency), CheckSettings.parseUrls(download), CheckSettings.parseUrls(direct), base)
                                saved()
                            }) { Text(stringResource(R.string.tropa_checks_save)) }
                        }
                        MoreCard(values, server)
                    }
                }
            }
        }
    }
}

/** The parameters only the server changes: shown, so it is clear what the phone runs with. */
@Composable
private fun MoreCard(values: CheckValues, server: CheckParams?) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.tropa_checks_more), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            ParamRow(
                R.string.tropa_checks_others,
                stringResource(R.string.tropa_checks_every, duration(values.othersIntervalSec)),
                server?.othersIntervalSec != null,
            )
            ParamRow(
                R.string.tropa_checks_download,
                stringResource(R.string.tropa_checks_download_value, values.downloadMinKb, duration(values.downloadIntervalSec)),
                server?.downloadMinKb != null || server?.downloadIntervalSec != null,
            )
            ParamRow(
                R.string.tropa_checks_screen_off,
                stringResource(R.string.tropa_checks_screen_off_value, duration(values.screenOffIntervalSec)),
                server?.screenOffIntervalSec != null,
            )
            ParamRow(
                R.string.tropa_checks_timeouts,
                stringResource(R.string.tropa_checks_timeouts_value, duration(values.latencyTimeoutSec), duration(values.downloadTimeoutSec)),
                server?.latencyTimeoutSec != null || server?.downloadTimeoutSec != null,
            )
            ParamRow(
                R.string.tropa_checks_fail,
                pluralStringResource(R.plurals.tropa_checks_fail_value, values.failThreshold, values.failThreshold),
                server?.failThreshold != null,
            )
            ParamRow(
                R.string.tropa_checks_better,
                pluralStringResource(R.plurals.tropa_checks_better_value, values.betterRounds, values.betterPercent, values.betterMinMs, values.betterRounds),
                server?.betterPercent != null || server?.betterMinMs != null || server?.betterRounds != null,
            )
            Text(stringResource(R.string.tropa_checks_more_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ParamRow(@StringRes title: Int, value: String, fromServer: Boolean) {
    Column {
        Text(stringResource(title), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (fromServer) SourceText(CheckSource.SERVER)
    }
}

@Composable
private fun SourceText(source: CheckSource) {
    Text(
        stringResource(
            when (source) {
                CheckSource.USER -> R.string.tropa_checks_source_user
                CheckSource.SERVER -> R.string.tropa_checks_source_server
                CheckSource.DEFAULT -> R.string.tropa_checks_source_default
            }
        ),
        fontSize = 12.sp,
        color = if (source == CheckSource.SERVER) TropaColors.BrandDark else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 45 s, 3 min, 1 h. */
@Composable
private fun duration(seconds: Int): String = when {
    seconds >= 3600 && seconds % 3600 == 0 -> stringResource(R.string.tropa_duration_hours, seconds / 3600)
    seconds >= 60 && seconds % 60 == 0 -> stringResource(R.string.tropa_duration_minutes, seconds / 60)
    else -> stringResource(R.string.tropa_duration_seconds, seconds)
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
        content = content,
    )
}

@Composable
private fun UrlField(label: String, value: String, source: CheckSource, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = { SourceText(source) },
        minLines = 2,
        textStyle = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth(),
    )
}
