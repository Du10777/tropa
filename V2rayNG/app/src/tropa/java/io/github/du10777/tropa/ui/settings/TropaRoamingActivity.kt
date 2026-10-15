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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import io.github.du10777.tropa.data.Roaming
import io.github.du10777.tropa.service.TropaServiceEventsReceiver
import io.github.du10777.tropa.ui.common.TropaTopBar
import io.github.du10777.tropa.ui.theme.TropaColors
import io.github.du10777.tropa.ui.theme.TropaTheme

/** Settings → Roaming: stop the traffic abroad on mobile data, keep the checks there. */
class TropaRoamingActivity : BaseComponentActivity() {
    private var stop by mutableStateOf(Roaming.stopTraffic)
    private var checks by mutableStateOf(Roaming.checksInRoaming)

    @Composable
    override fun ScreenContent() {
        TropaTheme {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.systemBarsPadding()) {
                    TropaTopBar(stringResource(R.string.tropa_roaming_title), onBack = ::finish)
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(stringResource(R.string.tropa_roaming_explain), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SwitchCard(stringResource(R.string.tropa_roaming_stop), stringResource(R.string.tropa_roaming_stop_hint), stop) {
                            stop = it
                            Roaming.stopTraffic = it
                            TropaServiceEventsReceiver.wakeWatchdog(this@TropaRoamingActivity)
                        }
                        SwitchCard(stringResource(R.string.tropa_roaming_checks), stringResource(R.string.tropa_roaming_checks_hint), checks) {
                            checks = it
                            Roaming.checksInRoaming = it
                            TropaServiceEventsReceiver.wakeWatchdog(this@TropaRoamingActivity)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchCard(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(hint, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(checkedTrackColor = TropaColors.Brand, checkedThumbColor = Color.White),
            )
        }
    }
}
