package io.github.du10777.tropa.ui.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.toast
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.util.Utils
import io.github.du10777.tropa.data.AppUpdates
import io.github.du10777.tropa.ui.common.TropaTopBar
import io.github.du10777.tropa.ui.main.TropaLogo
import io.github.du10777.tropa.ui.main.TropaMainActivity
import io.github.du10777.tropa.ui.theme.TropaColors
import io.github.du10777.tropa.ui.theme.TropaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → About: the version with a link to the project page, whether Tropa looks for a new
 * version by itself (once a day), and a button that looks right now. A newer version found opens the
 * main screen, which offers it.
 */
class TropaAboutActivity : BaseComponentActivity() {
    private var autoCheck by mutableStateOf(AppUpdates.enabled)
    private var checking by mutableStateOf(false)
    private var available by mutableStateOf(AppUpdates.available()?.version)

    override fun onResume() {
        super.onResume()
        available = AppUpdates.available()?.version
    }

    private fun checkNow() {
        if (checking) return
        checking = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { AppUpdates.check() }
            checking = false
            val release = result.getOrNull()
            available = release?.version
            when {
                result.isFailure -> toast(R.string.tropa_update_check_failed)
                release == null -> toast(R.string.tropa_update_latest)
                else -> {
                    AppUpdates.clearPostponed()
                    startActivity(
                        Intent(this@TropaAboutActivity, TropaMainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                }
            }
        }
    }

    @Composable
    override fun ScreenContent() {
        TropaTheme {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.systemBarsPadding()) {
                    TropaTopBar(stringResource(R.string.tropa_settings_about), onBack = ::finish)
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Card {
                            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    TropaLogo(44.dp)
                                    Column {
                                        Text(stringResource(R.string.app_name), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            stringResource(R.string.tropa_about_version, BuildConfig.VERSION_NAME, BuildConfig.UPSTREAM_VERSION_NAME),
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                TextButton(
                                    onClick = { Utils.openUri(this@TropaAboutActivity, REPOSITORY_URL) },
                                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                                ) { Text(stringResource(R.string.tropa_about_more)) }
                            }
                        }
                        Card {
                            Row(
                                modifier = Modifier
                                    .clickable { switchAutoCheck(!autoCheck) }
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.tropa_settings_updates), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                    Text(stringResource(R.string.tropa_updates_auto_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = autoCheck,
                                    onCheckedChange = ::switchAutoCheck,
                                    colors = SwitchDefaults.colors(checkedTrackColor = TropaColors.Brand, checkedThumbColor = Color.White),
                                )
                            }
                        }
                        // A solid brand button: the translucent one looked disabled in the dark theme.
                        Button(
                            onClick = ::checkNow,
                            enabled = !checking,
                            colors = ButtonDefaults.buttonColors(containerColor = TropaColors.Brand, contentColor = TropaColors.OnBrand),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(painterResource(R.drawable.ic_cloud_download_24dp), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(if (checking) R.string.tropa_settings_updates_checking else R.string.tropa_updates_check_now),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        available?.let {
                            Text(
                                stringResource(R.string.tropa_settings_updates_available, BuildConfig.VERSION_NAME, it),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun switchAutoCheck(value: Boolean) {
        autoCheck = value
        AppUpdates.enabled = value
    }

    companion object {
        const val REPOSITORY_URL = "https://github.com/Du10777/tropa"
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
