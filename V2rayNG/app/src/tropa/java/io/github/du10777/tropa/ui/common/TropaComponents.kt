package io.github.du10777.tropa.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import io.github.du10777.tropa.ui.permissions.TropaPermission
import io.github.du10777.tropa.ui.theme.TropaColors

/** Screen title row; [onBack] adds the back arrow. */
@Composable
fun TropaTopBar(title: String, onBack: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back_24dp), contentDescription = stringResource(R.string.tropa_back))
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
    }
}

/** Large tappable row: icon, title and a hint, used for the ways to add a connection. */
@Composable
fun ActionRow(
    @DrawableRes icon: Int,
    title: String,
    hint: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surface,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = container,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
            Column {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                if (!hint.isNullOrEmpty()) {
                    Text(hint, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Explanation shown before Tropa asks Android for a permission. */
@Composable
fun PermissionExplanationDialog(permission: TropaPermission, onYes: () -> Unit, onNo: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNo,
        title = { Text(stringResource(permission.title)) },
        text = {
            Column {
                Text(stringResource(permission.reason))
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.tropa_permission_question), fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = { TextButton(onClick = onYes) { Text(stringResource(R.string.tropa_yes)) } },
        dismissButton = { TextButton(onClick = onNo) { Text(stringResource(R.string.tropa_no)) } },
    )
}

/** Radio buttons: picking an option applies it and closes the dialog. [note] adds a second line. */
@Composable
fun <T> ChoiceDialog(
    title: String,
    hint: String?,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    note: (T) -> String? = { null },
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (hint != null) Text(hint, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = option == selected, onClick = { onSelect(option) }, role = Role.RadioButton)
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = TropaColors.Brand))
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(label(option), fontSize = 16.sp)
                            note(option)?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tropa_cancel)) } },
    )
}

/** Simple yes/no question. */
@Composable
fun ConfirmDialog(text: String, note: String? = null, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column {
                Text(text, fontSize = 17.sp)
                if (note != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.tropa_yes)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tropa_no)) } },
    )
}
