package io.github.du10777.tropa.ui.settings

import android.content.pm.ApplicationInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.extension.toast
import com.v2ray.ang.ui.base.BaseComponentActivity
import io.github.du10777.tropa.data.BypassApps
import io.github.du10777.tropa.data.TropaPrefs
import io.github.du10777.tropa.ui.common.ActionRow
import io.github.du10777.tropa.ui.common.TropaTopBar
import io.github.du10777.tropa.ui.theme.TropaColors
import io.github.du10777.tropa.ui.theme.TropaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

/**
 * "Apps without VPN": the list, adding an installed app, the banks and state services preset.
 * A running VPN takes the changes with a restart when the screen is left.
 */
class TropaAppsActivity : BaseComponentActivity() {
    private data class InstalledApp(val packageName: String, val name: String)

    private var selected by mutableStateOf(BypassApps.packages())
    private var reversed by mutableStateOf(BypassApps.reversed)
    private var installed by mutableStateOf<List<InstalledApp>?>(null)
    private var picking by mutableStateOf(false)
    private var query by mutableStateOf("")
    private var changed = false

    override fun onStart() {
        super.onStart()
        if (installed == null) lifecycleScope.launch { installed = loadInstalled() }
    }

    override fun onStop() {
        super.onStop()
        // The per-app list is part of the VPN interface: a running VPN restarts, a stopped one stays stopped.
        if (changed) {
            changed = false
            LauncherManager.restartService(this)
        }
    }

    @Composable
    override fun ScreenContent() {
        TropaTheme {
            BackHandler(enabled = picking) { picking = false }
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.systemBarsPadding()) {
                    if (picking) {
                        TropaTopBar(stringResource(R.string.tropa_apps_add), onBack = { picking = false })
                        Picker()
                    } else {
                        TropaTopBar(stringResource(R.string.tropa_settings_per_app), onBack = ::finish)
                        AppList()
                    }
                }
            }
        }
    }

    @Composable
    private fun AppList() {
        val names = installed?.associate { it.packageName to it.name }.orEmpty()
        val apps = selected.sortedWith(compareBy(Collator.getInstance()) { names[it] ?: it })
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Text(stringResource(R.string.tropa_apps_explain), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (reversed) {
                item {
                    Surface(shape = RoundedCornerShape(18.dp), color = TropaColors.Problem.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.tropa_apps_reversed, selected.size), fontSize = 14.sp)
                            TextButton(onClick = { update(emptySet()) }) { Text(stringResource(R.string.tropa_apps_reversed_reset)) }
                        }
                    }
                }
            } else {
                item {
                    ActionRow(R.drawable.ic_add_24dp, stringResource(R.string.tropa_apps_add_preset), stringResource(R.string.tropa_apps_add_preset_hint), ::addPreset)
                }
                item {
                    ActionRow(R.drawable.ic_per_apps_24dp, stringResource(R.string.tropa_apps_add), null, onClick = {
                        query = ""
                        picking = true
                    })
                }
                if (apps.isEmpty()) {
                    item { Text(stringResource(R.string.tropa_apps_empty), fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp)) }
                } else {
                    items(apps, key = { it }) { pkg ->
                        AppRow(pkg, names[pkg], loading = installed == null) { update(selected - pkg) }
                    }
                    if (TropaPrefs.connectedSince > 0L) {
                        item { Text(stringResource(R.string.tropa_apps_restart_note), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }

    @Composable
    private fun Picker() {
        val all = installed
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.tropa_apps_search)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search_24dp), contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (all == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = TropaColors.Brand) }
                return@Column
            }
            val found = all.filter { app ->
                app.packageName !in selected &&
                    (query.isBlank() || app.name.contains(query.trim(), ignoreCase = true) || app.packageName.contains(query.trim(), ignoreCase = true))
            }
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(found, key = { it.packageName }) { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                update(selected + app.packageName)
                                picking = false
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        AppIcon(app.packageName)
                        Column(Modifier.weight(1f)) {
                            Text(app.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(app.packageName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AppRow(pkg: String, name: String?, loading: Boolean, onRemove: () -> Unit) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AppIcon(pkg)
                Column(Modifier.weight(1f)) {
                    Text(name ?: pkg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (name == null && !loading) stringResource(R.string.tropa_apps_not_installed, pkg) else pkg,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(painterResource(R.drawable.ic_delete_24dp), contentDescription = stringResource(R.string.tropa_apps_remove), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    @Composable
    private fun AppIcon(pkg: String) {
        val context = LocalContext.current
        val icon by produceState<ImageBitmap?>(null, pkg) {
            value = withContext(Dispatchers.IO) {
                runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
            }
        }
        Box(Modifier.size(40.dp)) {
            icon?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
        }
    }

    private fun update(packages: Set<String>) {
        BypassApps.save(packages)
        selected = packages
        reversed = false
        changed = true
    }

    private fun addPreset() {
        val add = BypassApps.toAdd(BypassApps.BANKS_AND_STATE.filter(::isInstalled).toSet(), selected)
        if (add.isEmpty()) {
            toast(R.string.tropa_apps_preset_none)
            return
        }
        update(selected + add)
        toast(resources.getQuantityString(R.plurals.tropa_apps_preset_added, add.size, add.size))
    }

    private fun isInstalled(pkg: String): Boolean = runCatching { packageManager.getApplicationInfo(pkg, 0) }.isSuccess

    /** Apps a person can pick: everything with a launcher icon plus the non-system ones, Tropa itself left out. */
    private suspend fun loadInstalled(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = packageManager
        val collator = Collator.getInstance()
        pm.getInstalledApplications(0)
            .filter { it.packageName != packageName }
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { InstalledApp(it.packageName, it.loadLabel(pm).toString()) }
            .sortedWith(compareBy(collator) { it.name })
    }
}
