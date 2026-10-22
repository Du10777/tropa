package io.github.du10777.tropa.ui.main

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.R

/** "Is this Wi-Fi unlimited?" about a Wi-Fi Android marks as metered (see MeteredWifi). */
@Composable
fun WifiDialog(onUnlimited: () -> Unit, onLimited: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.tropa_wifi_question)) },
        text = { Text(stringResource(R.string.tropa_wifi_dialog_text)) },
        confirmButton = { TextButton(onClick = onLimited) { Text(stringResource(R.string.tropa_wifi_limited)) } },
        dismissButton = { TextButton(onClick = onUnlimited) { Text(stringResource(R.string.tropa_wifi_unlimited)) } },
    )
}
