package io.github.du10777.tropa.ui.permissions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.github.du10777.tropa.ui.common.PermissionExplanationDialog
import io.github.du10777.tropa.ui.common.TropaTopBar
import io.github.du10777.tropa.ui.theme.TropaColors
import io.github.du10777.tropa.ui.theme.TropaTheme

/** Settings → Permissions: what Tropa may do, with green ticks and red crosses. */
class TropaPermissionsActivity : BaseComponentActivity() {
    private val requester = TropaPermissionRequester(this)
    private var refreshTick by mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        refreshTick++
    }

    @Composable
    override fun ScreenContent() {
        TropaTheme {
            var explaining by remember { mutableStateOf<TropaPermission?>(null) }
            // Reading the tick re-evaluates every status after returning from a system screen.
            @Suppress("UNUSED_VARIABLE") val tick = refreshTick
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.systemBarsPadding()) {
                    TropaTopBar(stringResource(R.string.tropa_permissions_title), onBack = ::finish)
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TropaPermission.entries.filter { it.isApplicable() }.forEach { permission ->
                            PermissionRow(permission, granted = permission.isGranted(this@TropaPermissionsActivity)) {
                                explaining = permission
                            }
                        }
                    }
                }
            }
            explaining?.let { permission ->
                PermissionExplanationDialog(
                    permission = permission,
                    onYes = {
                        explaining = null
                        requester.request(permission) { refreshTick++ }
                    },
                    onNo = { explaining = null },
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(permission: TropaPermission, granted: Boolean, onClick: () -> Unit) {
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
            Icon(
                painterResource(if (granted) R.drawable.tropa_ic_check_circle else R.drawable.tropa_ic_cancel_circle),
                contentDescription = null,
                tint = if (granted) TropaColors.PermissionGranted else TropaColors.PermissionDenied,
                modifier = Modifier.size(30.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(stringResource(permission.title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(if (granted) R.string.tropa_permission_granted else R.string.tropa_permission_denied),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
