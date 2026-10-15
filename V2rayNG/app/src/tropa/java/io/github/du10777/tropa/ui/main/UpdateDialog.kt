package io.github.du10777.tropa.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import io.github.du10777.tropa.data.ConnectionLog
import io.github.du10777.tropa.ui.theme.TropaColors

/** "Version X is available": what is new, update (download with progress, then the system installer), not now. */
@Composable
fun UpdateDialog(offer: UpdateOffer, onChangelog: () -> Unit, onUpdate: () -> Unit, onCancel: () -> Unit, onLater: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { if (!offer.downloading) onLater() },
        title = { Text(stringResource(R.string.tropa_update_title, offer.release.version)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    offer.downloading -> {
                        val done = offer.progress ?: 0L
                        Text(
                            stringResource(
                                R.string.tropa_update_downloading,
                                ConnectionLog.sizeLabel(context, done),
                                ConnectionLog.sizeLabel(context, offer.total),
                            ),
                        )
                        if (offer.total > 0) {
                            LinearProgressIndicator(progress = { done.toFloat() / offer.total }, modifier = Modifier.fillMaxWidth(), color = TropaColors.Brand)
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = TropaColors.Brand)
                        }
                    }

                    offer.failed -> Text(stringResource(R.string.tropa_update_failed), color = TropaColors.Problem)
                    else -> Text(stringResource(R.string.tropa_update_text))
                }
                Text(
                    stringResource(R.string.tropa_update_changelog),
                    color = TropaColors.Brand,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(onClick = onChangelog).padding(vertical = 4.dp),
                )
            }
        },
        confirmButton = {
            if (offer.downloading) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.tropa_cancel)) }
            } else {
                TextButton(onClick = onUpdate) { Text(stringResource(if (offer.failed) R.string.tropa_update_retry else R.string.tropa_update_install)) }
            }
        },
        dismissButton = {
            if (!offer.downloading) TextButton(onClick = onLater) { Text(stringResource(R.string.tropa_update_later)) }
        },
    )
}
