package io.github.du10777.tropa.ui.settings

import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.util.Utils
import io.github.du10777.tropa.data.GeoIpEntry
import io.github.du10777.tropa.data.GeoIpFile
import io.github.du10777.tropa.ui.common.TropaTopBar
import io.github.du10777.tropa.ui.theme.TropaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Address databases (GeoIP): the .dat files the core uses (shipped with the app or downloaded by
 * a subscription), the countries and lists inside, and their subnets.
 */
class TropaGeoActivity : BaseComponentActivity() {

    @Composable
    override fun ScreenContent() {
        TropaTheme {
            val root = remember { File(Utils.userAssetPath(this)) }
            var file by remember { mutableStateOf(intent.getStringExtra(EXTRA_FILE)?.let { File(root, it) }) }
            var code by remember { mutableStateOf(intent.getStringExtra(EXTRA_CODE)) }
            val openedDirectly = intent.hasExtra(EXTRA_CODE)

            BackHandler(enabled = !openedDirectly && (code != null || file != null)) {
                if (code != null) code = null else file = null
            }

            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.systemBarsPadding()) {
                    val title = when {
                        code != null -> "${file?.name} · ${code!!.uppercase()}"
                        file != null -> file!!.name
                        else -> stringResource(R.string.tropa_settings_geo)
                    }
                    TropaTopBar(title, onBack = {
                        when {
                            openedDirectly -> finish()
                            code != null -> code = null
                            file != null -> file = null
                            else -> finish()
                        }
                    })
                    val current = file
                    when {
                        current == null -> FileList(root) { file = it }
                        !current.exists() -> Message(stringResource(R.string.tropa_geo_missing))
                        code == null -> CodeList(current) { code = it }
                        else -> SubnetList(current, code!!)
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_FILE = "file"
        private const val EXTRA_CODE = "code"

        fun intent(context: Context, file: String? = null, code: String? = null): Intent =
            Intent(context, TropaGeoActivity::class.java).apply {
                file?.let { putExtra(EXTRA_FILE, it) }
                code?.let { putExtra(EXTRA_CODE, it) }
            }
    }
}

@Composable
private fun FileList(root: File, onOpen: (File) -> Unit) {
    val context = LocalContext.current
    val files = remember { root.walkTopDown().filter { it.isFile && it.name.endsWith(".dat") }.sortedBy { it.path }.toList() }
    if (files.isEmpty()) return Message(stringResource(R.string.tropa_geo_missing))
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(files) { f ->
            Row(
                title = f.relativeTo(root).path,
                subtitle = Formatter.formatShortFileSize(context, f.length()) +
                    if ("geoip" in f.name.lowercase()) "" else " · " + context.getString(R.string.tropa_geo_not_geoip),
                onClick = if ("geoip" in f.name.lowercase()) ({ onOpen(f) }) else null,
            )
        }
    }
}

@Composable
private fun CodeList(file: File, onOpen: (String) -> Unit) {
    var entries by remember(file) { mutableStateOf<List<GeoIpEntry>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(file) {
        entries = withContext(Dispatchers.IO) { runCatching { GeoIpFile.entries(file) }.getOrDefault(emptyList()) }
    }
    val list = entries ?: return Loading()
    Column {
        Search(query) { query = it }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list.filter { query.isBlank() || it.code.contains(query, ignoreCase = true) }) { entry ->
                Row(entry.code.uppercase(), stringResource(R.string.tropa_geo_subnets_count, entry.count)) { onOpen(entry.code) }
            }
        }
    }
}

@Composable
private fun SubnetList(file: File, code: String) {
    var subnets by remember(file, code) { mutableStateOf<List<String>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(file, code) {
        subnets = withContext(Dispatchers.IO) { runCatching { GeoIpFile.subnets(file, code) }.getOrDefault(emptyList()) }
    }
    val list = subnets ?: return Loading()
    Column {
        Search(query) { query = it }
        Text(
            stringResource(R.string.tropa_geo_subnets_count, list.size),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        val shown = if (query.isBlank()) list else list.filter { it.contains(query.trim()) }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            items(shown) { subnet -> Text(subnet, fontSize = 15.sp, modifier = Modifier.padding(vertical = 3.dp)) }
        }
    }
}

@Composable
private fun Row(title: String, subtitle: String, onClick: (() -> Unit)?) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Search(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        label = { Text(stringResource(R.string.tropa_geo_search)) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
