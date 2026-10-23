package io.github.du10777.tropa.ui.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import io.github.du10777.tropa.data.AppUpdates
import io.github.du10777.tropa.ui.common.TropaTopBar
import io.github.du10777.tropa.ui.main.TropaMainActivity
import io.github.du10777.tropa.ui.theme.TropaColors
import io.github.du10777.tropa.ui.theme.TropaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Update: whether Tropa looks for a new version by itself (once a day), and below it a
 * button that looks right now. A newer version found opens the main screen, which offers it.
 */
class TropaUpdatesActivity : BaseComponentActivity() {
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
                        Intent(this@TropaUpdatesActivity, TropaMainActivity::class.java)
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
                    TropaTopBar(stringResource(R.string.tropa_updates_title), onBack = ::finish)
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 1.dp,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
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
                        FilledTonalButton(
                            onClick = ::checkNow,
                            enabled = !checking,
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = TropaColors.Brand.copy(alpha = 0.22f),
                                contentColor = TropaColors.BrandDark,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(painterResource(R.drawable.ic_cloud_download_24dp), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(if (checking) R.string.tropa_settings_updates_checking else R.string.tropa_updates_check_now))
                        }
                        Text(
                            available?.let { stringResource(R.string.tropa_settings_updates_available, BuildConfig.VERSION_NAME, it) }
                                ?: stringResource(R.string.tropa_settings_updates_status, BuildConfig.VERSION_NAME),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }

    private fun switchAutoCheck(value: Boolean) {
        autoCheck = value
        AppUpdates.enabled = value
    }
}
