package io.github.du10777.tropa.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import io.github.du10777.tropa.data.MeteredWifi
import io.github.du10777.tropa.service.TropaServiceEventsReceiver
import io.github.du10777.tropa.ui.common.TropaTopBar
import io.github.du10777.tropa.ui.theme.TropaColors
import io.github.du10777.tropa.ui.theme.TropaTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings → Connection check → Limited Wi-Fi → Answers about networks: every Wi-Fi the person said
 * is limited or unlimited, with a cross that forgets the answer (Tropa asks about that network again).
 */
class TropaWifiAnswersActivity : BaseComponentActivity() {
    private var answers by mutableStateOf(MeteredWifi.answers())

    override fun onResume() {
        super.onResume()
        answers = MeteredWifi.answers()
    }

    private fun forget(key: String) {
        MeteredWifi.forget(key)
        answers = MeteredWifi.answers()
        TropaServiceEventsReceiver.wakeWatchdog(this)
    }

    @Composable
    override fun ScreenContent() {
        TropaTheme {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.systemBarsPadding()) {
                    TropaTopBar(stringResource(R.string.tropa_wifi_answers_title), onBack = ::finish)
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (answers.isEmpty()) {
                            Text(stringResource(R.string.tropa_wifi_answers_empty), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surface,
                                shadowElevation = 1.dp,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(vertical = 8.dp)) {
                                    HeaderRow()
                                    answers.forEach { answer ->
                                        HorizontalDivider(color = MaterialTheme.colorScheme.background, modifier = Modifier.padding(horizontal = 16.dp))
                                        AnswerRow(answer) { forget(answer.key) }
                                    }
                                }
                            }
                        }
                        Text(stringResource(R.string.tropa_wifi_answers_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderRow() {
    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.tropa_wifi_answers_network), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.tropa_wifi_answers_status), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(STATUS_WIDTH))
        Spacer(Modifier.width(ICON_WIDTH))
    }
}

/** One network: its name (the subnet) with the day of the answer, the answer, and the cross. */
@Composable
private fun AnswerRow(answer: MeteredWifi.Answer, onForget: () -> Unit) {
    val day = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(answer.at))
    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(MeteredWifi.describe(answer.label) ?: stringResource(R.string.tropa_wifi_answers_unnamed), fontSize = 15.sp)
            Text(stringResource(R.string.tropa_wifi_answers_when, day), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            stringResource(if (answer.limited) R.string.tropa_wifi_answers_limited else R.string.tropa_wifi_answers_unlimited),
            fontSize = 14.sp,
            color = if (answer.limited) TropaColors.BrandDark else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(STATUS_WIDTH),
        )
        Box(Modifier.width(ICON_WIDTH), contentAlignment = Alignment.Center) {
            IconButton(onClick = onForget) {
                Icon(
                    painterResource(R.drawable.tropa_ic_cancel_circle),
                    contentDescription = stringResource(R.string.tropa_wifi_answers_forget),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val STATUS_WIDTH = 112.dp
private val ICON_WIDTH = 48.dp
