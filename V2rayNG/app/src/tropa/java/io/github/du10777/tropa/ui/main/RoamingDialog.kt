package io.github.du10777.tropa.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import io.github.du10777.tropa.data.ConnectionLog

/** "You are roaming. Are you sure you want to transfer data?", with the usual mobile data per day if known. */
@Composable
fun RoamingDialog(dailyBytes: Long?, onContinue: () -> Unit, onLater: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.tropa_roaming_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tropa_roaming_dialog_text))
                dailyBytes?.let { Text(stringResource(R.string.tropa_roaming_dialog_usage, ConnectionLog.sizeLabel(context, it))) }
            }
        },
        confirmButton = { TextButton(onClick = onContinue) { Text(stringResource(R.string.tropa_roaming_continue)) } },
        dismissButton = { TextButton(onClick = onLater) { Text(stringResource(R.string.tropa_roaming_later)) } },
    )
}
