package io.github.du10777.tropa.ui.settings

import android.content.Intent
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import io.github.du10777.tropa.data.EnergyModel
import io.github.du10777.tropa.data.EnergySettings
import io.github.du10777.tropa.data.EnergyStore
import io.github.du10777.tropa.data.MeteredWifi
import io.github.du10777.tropa.service.TropaServiceEventsReceiver
import io.github.du10777.tropa.ui.common.TropaTopBar
import io.github.du10777.tropa.ui.theme.TropaColors
import io.github.du10777.tropa.ui.theme.TropaTheme
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Settings → Connection check: on/off, how often, the addresses used to check, and the rest of the
 * parameters. The server of the active connection may send its own (`Tropa-Checks`); what the user
 * changes here wins over them, and every value says where it comes from.
 */
class TropaChecksActivity : BaseComponentActivity() {
    /** Bumped on every return to the screen: the Wi-Fi answers may have changed on their own screen. */
    private var resumes by mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumes++
    }

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
            var energy by remember { mutableStateOf(EnergyStore.settings()) }
            var upload by remember { mutableStateOf(CheckSettings.checkUpload) }
            val energyModel = remember { EnergyStore.model() }

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
                                ChipRow(
                                    choices = (CheckSettings.ACTIVE_INTERVAL_CHOICES + base.activeIntervalSec + values.activeIntervalSec).distinct().sorted(),
                                    selected = values.activeIntervalSec,
                                    label = { stringResource(R.string.tropa_checks_interval_value, it) },
                                ) { seconds ->
                                    CheckSettings.setActiveInterval(seconds, base)
                                    user = CheckSettings.user()
                                    TropaServiceEventsReceiver.wakeWatchdog(this@TropaChecksActivity)
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
                        ToggleCard(R.string.tropa_checks_upload, R.string.tropa_checks_upload_hint, upload) {
                            upload = it
                            CheckSettings.checkUpload = it
                            TropaServiceEventsReceiver.wakeWatchdog(this@TropaChecksActivity)
                        }
                        WifiCard(
                            wifi = remember { MeteredWifi.current(this@TropaChecksActivity) },
                            resumes = resumes,
                            onChange = { TropaServiceEventsReceiver.wakeWatchdog(this@TropaChecksActivity) },
                            onAnswers = { startActivity(Intent(this@TropaChecksActivity, TropaWifiAnswersActivity::class.java)) },
                        )
                        MoreCard(
                            values = values,
                            user = user,
                            server = server,
                            onSave = { input ->
                                CheckSettings.setNumbers(input, base)
                                user = CheckSettings.user()
                                TropaServiceEventsReceiver.wakeWatchdog(this@TropaChecksActivity)
                                val clamped = input.any { (key, value) -> value != null && value !in CheckParams.RANGES.getValue(key) }
                                toast(if (clamped) R.string.tropa_checks_saved_clamped else R.string.tropa_checks_saved)
                            },
                            onReset = {
                                CheckSettings.resetNumbers()
                                user = CheckSettings.user()
                                TropaServiceEventsReceiver.wakeWatchdog(this@TropaChecksActivity)
                                toast(R.string.tropa_checks_saved)
                            },
                        )
                        EnergyCard(energy, energyModel) { changed ->
                            energy = changed
                            EnergyStore.saveSettings(changed)
                            TropaServiceEventsReceiver.wakeWatchdog(this@TropaChecksActivity)
                        }
                    }
                }
            }
        }
    }
}

/** A setting that is on or off, with a line about what it does. */
@Composable
private fun ToggleCard(@StringRes title: Int, @StringRes hint: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card {
        Row(
            modifier = Modifier
                .clickable { onChange(!checked) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(checkedTrackColor = TropaColors.Brand, checkedThumbColor = Color.White),
            )
        }
    }
}

/**
 * Limited Wi-Fi: what Tropa does on one, the answer about the Wi-Fi the phone is on (changeable),
 * and the list of all answers on its own screen.
 */
@Composable
private fun WifiCard(wifi: MeteredWifi.Wifi?, resumes: Int, onChange: () -> Unit, onAnswers: () -> Unit) {
    var limited by remember(resumes) { mutableStateOf(wifi?.let { MeteredWifi.limited(it.key) }) }
    fun answer(value: Boolean) {
        val current = wifi ?: return
        MeteredWifi.remember(current.key, value, current.label)
        limited = value
        onChange()
    }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.tropa_wifi_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.tropa_wifi_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (wifi != null) {
                val now = when {
                    limited == true -> R.string.tropa_wifi_current_limited
                    limited == false -> R.string.tropa_wifi_current_unlimited
                    wifi.metered -> R.string.tropa_wifi_current_metered
                    else -> R.string.tropa_wifi_current_plain
                }
                Text(stringResource(now), fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { answer(false) }, enabled = limited != false) { Text(stringResource(R.string.tropa_wifi_unlimited)) }
                    TextButton(onClick = { answer(true) }, enabled = limited != true) { Text(stringResource(R.string.tropa_wifi_limited)) }
                }
            }
            TextButton(onClick = onAnswers) { Text(stringResource(R.string.tropa_wifi_answers)) }
        }
    }
}

/** One number of "More parameters": its Tropa-Checks key and the label of its field. */
private data class NumberField(val key: String, @StringRes val label: Int)

/** The groups of "More parameters", each under its title. */
private val MORE_GROUPS: List<Pair<Int, List<NumberField>>> = listOf(
    R.string.tropa_checks_others to listOf(NumberField("othersIntervalSec", R.string.tropa_checks_param_every_sec)),
    R.string.tropa_checks_download to listOf(
        NumberField("downloadMinKb", R.string.tropa_checks_param_min_kb),
        NumberField("downloadIntervalSec", R.string.tropa_checks_param_every_sec),
    ),
    R.string.tropa_checks_screen_off to listOf(NumberField("screenOffIntervalSec", R.string.tropa_checks_param_every_sec)),
    R.string.tropa_checks_timeouts to listOf(
        NumberField("latencyTimeoutSec", R.string.tropa_checks_param_latency_sec),
        NumberField("downloadTimeoutSec", R.string.tropa_checks_param_download_sec),
    ),
    R.string.tropa_checks_fail to listOf(NumberField("failThreshold", R.string.tropa_checks_param_fails)),
    R.string.tropa_checks_better to listOf(
        NumberField("betterPercent", R.string.tropa_checks_param_percent),
        NumberField("betterMinMs", R.string.tropa_checks_param_ms),
        NumberField("betterRounds", R.string.tropa_checks_param_rounds),
    ),
)

/**
 * The rest of the parameters, each editable: what the phone runs with now, where each group comes from,
 * and the buttons to save or to go back to the server's (or the default) values.
 */
@Composable
private fun MoreCard(
    values: CheckValues,
    user: CheckParams,
    server: CheckParams?,
    onSave: (Map<String, Int?>) -> Unit,
    onReset: () -> Unit,
) {
    var texts by remember(values) { mutableStateOf(CheckSettings.MORE_KEYS.associateWith { values.number(it).toString() }) }
    val focus = LocalFocusManager.current
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.tropa_checks_more), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            MORE_GROUPS.forEach { (title, fields) ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(title), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        fields.forEach { field ->
                            val range = CheckParams.RANGES.getValue(field.key)
                            OutlinedTextField(
                                value = texts.getValue(field.key),
                                onValueChange = { typed -> texts = texts + (field.key to typed.filter { it.isDigit() }.take(6)) },
                                label = { Text(stringResource(field.label), maxLines = 1) },
                                supportingText = { Text(stringResource(R.string.tropa_checks_param_range, range.first, range.last)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                textStyle = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    SourceText(
                        when {
                            fields.any { user.number(it.key) != null } -> CheckSource.USER
                            fields.any { server?.number(it.key) != null } -> CheckSource.SERVER
                            else -> CheckSource.DEFAULT
                        },
                    )
                }
            }
            Text(stringResource(R.string.tropa_checks_more_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    focus.clearFocus()
                    onReset()
                }) {
                    Text(stringResource(if (server != null) R.string.tropa_checks_reset_server else R.string.tropa_checks_reset))
                }
                TextButton(onClick = {
                    focus.clearFocus()
                    onSave(texts.mapValues { (_, text) -> text.toIntOrNull() })
                }) {
                    Text(stringResource(R.string.tropa_checks_save))
                }
            }
        }
    }
}

/** How much battery the background checks may use (sliders), and what Tropa measured so far. */
@Composable
private fun EnergyCard(settings: EnergySettings, model: EnergyModel, onChange: (EnergySettings) -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.tropa_energy_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.tropa_energy_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            EnergyRow(
                title = { percent, hours -> stringResource(R.string.tropa_energy_screen_off, percent, hours) },
                percent = settings.screenOffPercent,
                hours = settings.screenOffHours,
                onChange = { percent, hours -> onChange(settings.copy(screenOffPercent = percent, screenOffHours = hours)) },
            )
            EnergyRow(
                title = { percent, hours -> stringResource(R.string.tropa_energy_screen_on, percent, hours) },
                percent = settings.screenOnPercent,
                hours = settings.screenOnHours,
                onChange = { percent, hours -> onChange(settings.copy(screenOnPercent = percent, screenOnHours = hours)) },
            )
            Text(
                model.uahPerFetch?.let { stringResource(R.string.tropa_energy_measured, formatUah(it), model.samples) }
                    ?: stringResource(R.string.tropa_energy_unmeasured),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One screen state: its title follows the sliders as they move; the value is saved when a slider is let go. */
@Composable
private fun EnergyRow(title: @Composable (Int, Int) -> String, percent: Int, hours: Int, onChange: (Int, Int) -> Unit) {
    var p by remember(percent) { mutableFloatStateOf(percent.toFloat()) }
    var h by remember(hours) { mutableFloatStateOf(hours.toFloat()) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title(p.roundToInt(), h.roundToInt()), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        SliderRow(stringResource(R.string.tropa_energy_slider_percent), p, EnergySettings.PERCENT_RANGE, { p = it }) { onChange(p.roundToInt(), h.roundToInt()) }
        SliderRow(stringResource(R.string.tropa_energy_slider_hours), h, EnergySettings.HOURS_RANGE, { h = it }) { onChange(p.roundToInt(), h.roundToInt()) }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: IntRange, onValue: (Float) -> Unit, onDone: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp))
        // Whole numbers, without tick marks: a 1-48 hours slider would be a row of dots.
        Slider(
            value = value,
            onValueChange = { onValue(it.roundToInt().toFloat()) },
            onValueChangeFinished = onDone,
            valueRange = range.first.toFloat()..range.last.toFloat(),
            colors = SliderDefaults.colors(thumbColor = TropaColors.Brand, activeTrackColor = TropaColors.Brand),
            modifier = Modifier.weight(1f),
        )
    }
}

/** 0.8, 12, 140: one decimal below ten. */
private fun formatUah(value: Double): String =
    if (value < 10) String.format(Locale.getDefault(), "%.1f", value) else value.roundToInt().toString()

/** One choice out of a few numbers, as chips in a row. */
@Composable
private fun ChipRow(choices: List<Int>, selected: Int, label: @Composable (Int) -> String, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { value ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label(value)) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = TropaColors.Brand, selectedLabelColor = Color.White),
            )
        }
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
