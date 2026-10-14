package io.github.du10777.tropa.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.extension.toast
import com.v2ray.ang.ui.base.BaseComponentActivity
import io.github.du10777.tropa.data.CheckSettings
import io.github.du10777.tropa.service.TropaServiceEventsReceiver
import io.github.du10777.tropa.ui.common.TropaTopBar
import io.github.du10777.tropa.ui.theme.TropaColors
import io.github.du10777.tropa.ui.theme.TropaTheme

/** Settings → Connection check: on/off, how often, and the addresses used to check. */
class TropaChecksActivity : BaseComponentActivity() {

    @Composable
    override fun ScreenContent() {
        TropaTheme {
            var enabled by remember { mutableStateOf(CheckSettings.enabled) }
            var interval by remember { mutableIntStateOf(CheckSettings.activeIntervalSec) }
            var latency by remember { mutableStateOf(CheckSettings.latencyUrls.joinToString("\n")) }
            var throughput by remember { mutableStateOf(CheckSettings.throughputUrls.joinToString("\n")) }
            var direct by remember { mutableStateOf(CheckSettings.directUrls.joinToString("\n")) }

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
                        Card {
                            Column(Modifier.padding(16.dp)) {
                                Text(stringResource(R.string.tropa_checks_interval), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CheckSettings.ACTIVE_INTERVAL_CHOICES.forEach { seconds ->
                                        FilterChip(
                                            selected = interval == seconds,
                                            onClick = {
                                                interval = seconds
                                                CheckSettings.activeIntervalSec = seconds
                                                TropaServiceEventsReceiver.wakeWatchdog(this@TropaChecksActivity)
                                            },
                                            label = { Text(stringResource(R.string.tropa_checks_interval_value, seconds)) },
                                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = TropaColors.Brand, selectedLabelColor = Color.White),
                                        )
                                    }
                                }
                            }
                        }
                        Text(stringResource(R.string.tropa_checks_urls_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        UrlField(stringResource(R.string.tropa_checks_latency_urls), latency) { latency = it }
                        UrlField(stringResource(R.string.tropa_checks_throughput_urls), throughput) { throughput = it }
                        UrlField(stringResource(R.string.tropa_checks_direct_urls), direct) { direct = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                CheckSettings.resetUrls()
                                latency = CheckSettings.latencyUrls.joinToString("\n")
                                throughput = CheckSettings.throughputUrls.joinToString("\n")
                                direct = CheckSettings.directUrls.joinToString("\n")
                                toast(R.string.tropa_checks_saved)
                            }) { Text(stringResource(R.string.tropa_checks_reset)) }
                            TextButton(onClick = {
                                CheckSettings.latencyUrls = CheckSettings.parseUrls(latency)
                                CheckSettings.throughputUrls = CheckSettings.parseUrls(throughput)
                                CheckSettings.directUrls = CheckSettings.parseUrls(direct)
                                latency = CheckSettings.latencyUrls.joinToString("\n")
                                throughput = CheckSettings.throughputUrls.joinToString("\n")
                                direct = CheckSettings.directUrls.joinToString("\n")
                                toast(R.string.tropa_checks_saved)
                            }) { Text(stringResource(R.string.tropa_checks_save)) }
                        }
                    }
                }
            }
        }
    }
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
private fun UrlField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        minLines = 2,
        textStyle = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth(),
    )
}
