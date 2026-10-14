package io.github.du10777.tropa.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.enums.Language
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.main.MainActivity
import com.v2ray.ang.ui.perappproxy.PerAppProxyActivity
import com.v2ray.ang.util.Utils
import io.github.du10777.tropa.data.CheckSettings
import io.github.du10777.tropa.data.ConnectionLog
import io.github.du10777.tropa.data.LocalProxy
import io.github.du10777.tropa.ui.log.TropaLogActivity
import io.github.du10777.tropa.data.TropaPrefs
import io.github.du10777.tropa.service.WatchStateStore
import io.github.du10777.tropa.ui.common.TropaTopBar
import io.github.du10777.tropa.ui.permissions.TropaPermissionsActivity
import io.github.du10777.tropa.ui.theme.TropaColors
import io.github.du10777.tropa.ui.theme.TropaTheme

/** Tropa settings: the few things a family member may need; the rest lives in the advanced mode. */
class TropaSettingsActivity : BaseComponentActivity() {
    private var advancedMode by mutableStateOf(TropaPrefs.advancedMode)

    /** Changed on the log and proxy screens; read again when coming back. */
    private var logLevel by mutableStateOf(ConnectionLog.level)
    private var proxy by mutableStateOf(LocalProxy.userProxy)

    override fun onResume() {
        super.onResume()
        logLevel = ConnectionLog.level
        proxy = LocalProxy.userProxy
    }

    @Composable
    override fun ScreenContent() {
        TropaTheme {
            var choosingLanguage by remember { mutableStateOf(false) }
            var killSwitch by remember { mutableStateOf(CheckSettings.killSwitch) }
            var explainKillSwitch by remember { mutableStateOf(false) }
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.systemBarsPadding()) {
                    TropaTopBar(stringResource(R.string.tropa_settings_title), onBack = ::finish)
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SettingsRow(R.drawable.ic_translate_24dp, stringResource(R.string.tropa_settings_language), currentLanguageLabel()) {
                            choosingLanguage = true
                        }
                        SettingsRow(R.drawable.ic_lock_24dp, stringResource(R.string.tropa_settings_permissions), null) {
                            startActivity(Intent(this@TropaSettingsActivity, TropaPermissionsActivity::class.java))
                        }
                        SettingsRow(R.drawable.ic_per_apps_24dp, stringResource(R.string.tropa_settings_per_app), null) {
                            startActivity(Intent(this@TropaSettingsActivity, PerAppProxyActivity::class.java))
                        }
                        SettingsRow(
                            R.drawable.ic_privacy_24dp,
                            stringResource(R.string.tropa_proxy_title),
                            if (proxy.enabled) stringResource(R.string.tropa_proxy_address, proxy.port.toString()) else stringResource(R.string.tropa_proxy_off),
                        ) {
                            startActivity(Intent(this@TropaSettingsActivity, TropaProxyActivity::class.java))
                        }
                        SettingsRow(R.drawable.ic_check_update_24dp, stringResource(R.string.tropa_settings_checks), null) {
                            startActivity(Intent(this@TropaSettingsActivity, TropaChecksActivity::class.java))
                        }
                        SettingsRow(R.drawable.ic_description_24dp, stringResource(R.string.tropa_log_title), stringResource(logLevel.title)) {
                            startActivity(Intent(this@TropaSettingsActivity, TropaLogActivity::class.java))
                        }
                        SettingsRow(R.drawable.ic_routing_24dp, stringResource(R.string.tropa_settings_geo), null) {
                            startActivity(TropaGeoActivity.intent(this@TropaSettingsActivity))
                        }
                        KillSwitchRow(
                            enabled = killSwitch,
                            status = killSwitchStatus(),
                            onChange = { enabled ->
                                killSwitch = enabled
                                CheckSettings.killSwitch = enabled
                                if (enabled) explainKillSwitch = true
                            },
                            onClick = { if (killSwitch) explainKillSwitch = true },
                        )
                        AdvancedModeRow(advancedMode) { enabled ->
                            advancedMode = enabled
                            TropaPrefs.advancedMode = enabled
                            if (enabled) startActivity(Intent(this@TropaSettingsActivity, MainActivity::class.java))
                        }
                        SettingsRow(
                            R.drawable.ic_about_24dp,
                            stringResource(R.string.tropa_settings_about),
                            stringResource(R.string.tropa_about_version, BuildConfig.VERSION_NAME, BuildConfig.UPSTREAM_VERSION_NAME),
                        ) { Utils.openUri(this@TropaSettingsActivity, REPOSITORY_URL) }
                        SettingsRow(R.drawable.ic_source_code_24dp, stringResource(R.string.tropa_about_source), null) {
                            Utils.openUri(this@TropaSettingsActivity, REPOSITORY_URL)
                        }
                    }
                }
            }
            if (explainKillSwitch) {
                AlertDialog(
                    onDismissRequest = { explainKillSwitch = false },
                    title = { Text(stringResource(R.string.tropa_settings_kill_switch)) },
                    text = { Text(stringResource(R.string.tropa_kill_switch_explain)) },
                    confirmButton = {
                        TextButton(onClick = {
                            explainKillSwitch = false
                            openVpnSettings()
                        }) { Text(stringResource(R.string.tropa_kill_switch_open)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { explainKillSwitch = false }) { Text(stringResource(R.string.tropa_cancel)) }
                    },
                )
            }
            if (choosingLanguage) {
                LanguageDialog(
                    onPick = { code ->
                        choosingLanguage = false
                        AppLocaleManager.setApplicationLanguage(code)
                    },
                    onDismiss = { choosingLanguage = false },
                )
            }
        }
    }

    private fun openVpnSettings() {
        try {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    /** What Android reports about always-on VPN with lockdown (only while the VPN runs, Android 10+). */
    @Composable
    private fun killSwitchStatus(): String = when (WatchStateStore.read()?.lockdown) {
        true -> stringResource(R.string.tropa_kill_switch_status_on)
        false -> stringResource(R.string.tropa_kill_switch_status_off)
        null -> stringResource(R.string.tropa_kill_switch_status_unknown)
    }

    @Composable
    private fun currentLanguageLabel(): String {
        val tag = AppCompatDelegate.getApplicationLocales().get(0)?.language
        return when (tag) {
            Language.RUSSIAN.languageTag -> stringResource(R.string.tropa_language_ru)
            Language.ENGLISH.languageTag -> stringResource(R.string.tropa_language_en)
            null -> stringResource(R.string.tropa_language_system)
            else -> tag
        }
    }

    private companion object {
        const val REPOSITORY_URL = "https://github.com/Du10777/tropa"
    }
}

@Composable
private fun SettingsRow(@DrawableRes icon: Int, title: String, value: String?, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (value != null) Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(painterResource(R.drawable.tropa_ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AdvancedModeRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!enabled) },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(painterResource(R.drawable.ic_routing_24dp), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.tropa_settings_advanced), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.tropa_settings_advanced_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = enabled,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(checkedTrackColor = TropaColors.Brand, checkedThumbColor = Color.White),
            )
        }
    }
}

@Composable
private fun LanguageDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val current = AppCompatDelegate.getApplicationLocales().get(0)?.language
    val options = listOf(
        Language.RUSSIAN.code to stringResource(R.string.tropa_language_ru),
        Language.ENGLISH.code to stringResource(R.string.tropa_language_en),
        Language.AUTO.code to stringResource(R.string.tropa_language_system),
    )
    val selected = when (current) {
        Language.RUSSIAN.languageTag -> Language.RUSSIAN.code
        Language.ENGLISH.languageTag -> Language.ENGLISH.code
        null -> Language.AUTO.code
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tropa_settings_language)) },
        text = {
            Column {
                options.forEach { (code, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = code == selected, onClick = { onPick(code) }, role = Role.RadioButton)
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = code == selected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = TropaColors.Brand),
                        )
                        Text(label, fontSize = 16.sp, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tropa_cancel)) } },
    )
}

@Composable
private fun KillSwitchRow(enabled: Boolean, status: String, onChange: (Boolean) -> Unit, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(painterResource(R.drawable.ic_privacy_24dp), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.tropa_settings_kill_switch), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.tropa_kill_switch_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (enabled) Text(status, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            }
            Switch(
                checked = enabled,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(checkedTrackColor = TropaColors.Brand, checkedThumbColor = Color.White),
            )
        }
    }
}
