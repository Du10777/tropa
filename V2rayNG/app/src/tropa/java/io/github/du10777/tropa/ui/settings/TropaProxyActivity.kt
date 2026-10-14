package io.github.du10777.tropa.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.extension.toast
import com.v2ray.ang.ui.base.BaseComponentActivity
import io.github.du10777.tropa.data.LocalProxy
import io.github.du10777.tropa.ui.common.TropaTopBar
import io.github.du10777.tropa.ui.theme.TropaColors
import io.github.du10777.tropa.ui.theme.TropaTheme

/**
 * Settings → Local proxy: lets other apps on the phone (a browser, Telegram) go through Tropa via
 * 127.0.0.1:<port>, SOCKS5 or HTTP. Off by default; a login and password are recommended.
 */
class TropaProxyActivity : BaseComponentActivity() {

    @Composable
    override fun ScreenContent() {
        TropaTheme {
            val saved = remember { LocalProxy.userProxy }
            var enabled by remember { mutableStateOf(saved.enabled) }
            var port by remember { mutableStateOf(saved.port.toString()) }
            var user by remember { mutableStateOf(saved.user) }
            var pass by remember { mutableStateOf(saved.pass) }
            val portValue = port.toIntOrNull()
            val portOk = portValue != null && portValue in 1024..65535

            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.systemBarsPadding()) {
                    TropaTopBar(stringResource(R.string.tropa_proxy_title), onBack = ::finish)
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.tropa_proxy_hint), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .clickable { enabled = !enabled }
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(R.string.tropa_proxy_enabled), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = { enabled = it },
                                    colors = SwitchDefaults.colors(checkedTrackColor = TropaColors.Brand, checkedThumbColor = Color.White),
                                )
                            }
                        }
                        OutlinedTextField(
                            value = port,
                            onValueChange = { value -> port = value.filter { it.isDigit() }.take(5) },
                            label = { Text(stringResource(R.string.tropa_proxy_port)) },
                            isError = !portOk,
                            supportingText = { Text(stringResource(R.string.tropa_proxy_address, if (portOk) port else "…")) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = user,
                            onValueChange = { user = it.trim() },
                            label = { Text(stringResource(R.string.tropa_proxy_user)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = pass,
                            onValueChange = { pass = it.trim() },
                            label = { Text(stringResource(R.string.tropa_proxy_pass)) },
                            supportingText = {
                                if (user.isEmpty() || pass.isEmpty()) Text(stringResource(R.string.tropa_proxy_no_password), color = TropaColors.Problem)
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                user = LocalProxy.token(8)
                                pass = LocalProxy.token(16)
                            }) { Text(stringResource(R.string.tropa_proxy_generate)) }
                            TextButton(onClick = {
                                val text = "127.0.0.1:$port" + if (user.isNotEmpty()) " $user $pass" else ""
                                getSystemService(ClipboardManager::class.java)
                                    ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.tropa_proxy_title), text))
                                toast(R.string.tropa_proxy_copied)
                            }) { Text(stringResource(R.string.tropa_proxy_copy)) }
                            TextButton(
                                enabled = portOk,
                                onClick = {
                                    LocalProxy.userProxy = LocalProxy.UserProxy(enabled, portValue ?: return@TextButton, user, pass)
                                    // A running VPN takes the new inbound on a restart; a stopped one stays stopped.
                                    LauncherManager.restartService(this@TropaProxyActivity)
                                    toast(R.string.tropa_proxy_saved)
                                },
                            ) { Text(stringResource(R.string.tropa_checks_save)) }
                        }
                    }
                }
            }
        }
    }
}
