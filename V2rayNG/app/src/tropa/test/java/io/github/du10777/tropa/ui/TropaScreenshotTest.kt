package io.github.du10777.tropa.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.du10777.tropa.data.Connection
import io.github.du10777.tropa.data.Node
import io.github.du10777.tropa.data.ProfileDetails
import io.github.du10777.tropa.data.SubscriptionInfo
import io.github.du10777.tropa.service.CoreState
import io.github.du10777.tropa.service.ProbeEvent
import io.github.du10777.tropa.ui.log.LogEntry
import io.github.du10777.tropa.service.WatchNode
import io.github.du10777.tropa.service.WatchPhase
import io.github.du10777.tropa.service.WatchState
import io.github.du10777.tropa.ui.main.ClipOffer
import io.github.du10777.tropa.ui.main.MainActions
import io.github.du10777.tropa.ui.main.MainUiState
import io.github.du10777.tropa.ui.main.TropaMainScreen
import io.github.du10777.tropa.ui.theme.TropaTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the Tropa screens on the JVM (no device needed) into PNG files under build/tropa-screens,
 * so layout changes can be reviewed as pictures. Run testPlaystoreDebugUnitTest with a filter on this class.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A plain Application: the upstream one starts native MMKV, which the screens do not need.
@Config(sdk = [35], qualifiers = "ru-w380dp-h800dp-xxhdpi", application = Application::class)
class TropaScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val connection = Connection(
        id = "c1",
        name = "Моё подключение",
        url = "https://example.com/sub/abc",
        serverCount = 2,
        lastUpdated = System.currentTimeMillis(),
        info = SubscriptionInfo(upload = 2_000_000_000, download = 46_200_000_000, total = 100_000_000_000, expire = 1_794_700_800),
    )

    private val noop = MainActions(
        onToggle = {}, onAddCopied = {}, onScanQr = {}, onAddFile = {}, onOpenAddSheet = {}, onCloseAddSheet = {},
        onOpenConnections = {}, onOpenSettings = {}, onAcceptClip = {}, onDismissClip = {},
    )

    @OptIn(ExperimentalRoborazziApi::class)
    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent { TropaTheme { content() } }
        compose.waitForIdle()
        // Whole screen, so the bottom sheet window is captured too.
        captureScreenRoboImage("build/tropa-screens/$name.png")
    }

    private val nodes = listOf(
        WatchNode("bal-1-vless", "VLESS · entry.example.org", alive = true, latencyMs = 48, active = true),
        WatchNode("bal-1-wireguard", "WireGuard · 127.0.0.1", alive = false, latencyMs = null, active = false),
    )

    private fun watch(phase: WatchPhase) = WatchState(
        phase = phase,
        activeName = "VLESS · entry.example.org",
        latencyMs = 48,
        checkedAt = System.currentTimeMillis() - 12_000,
        attempt = 3,
        nextRetryAt = System.currentTimeMillis() + 10_000,
        nodes = nodes,
        connectedAt = System.currentTimeMillis() - 493_000,
    )

    private fun loaded(core: CoreState, clip: Boolean = false, error: String? = null, watch: WatchState? = null) = MainUiState(
        loaded = true,
        connections = listOf(connection),
        active = connection,
        core = core,
        error = error,
        connectedSince = System.currentTimeMillis() - 493_000,
        clipOffer = if (clip) ClipOffer("x", "xsub://abc") else null,
        watch = watch,
    )

    @Test
    fun firstRun() = shot("1_first_run") {
        TropaMainScreen(MainUiState(loaded = true), showAddSheet = false, actions = noop)
    }

    @Test
    fun disconnectedWithClipboard() = shot("2_disconnected") {
        TropaMainScreen(loaded(CoreState.Stopped, clip = true), showAddSheet = false, actions = noop)
    }

    @Test
    fun connecting() = shot("3_connecting") {
        TropaMainScreen(loaded(CoreState.Starting), showAddSheet = false, actions = noop)
    }

    @Test
    fun checking() = shot("4a_checking") {
        TropaMainScreen(loaded(CoreState.Running, watch = watch(WatchPhase.CHECKING)), showAddSheet = false, actions = noop)
    }

    @Test
    fun connected() = shot("4_connected") {
        TropaMainScreen(loaded(CoreState.Running, watch = watch(WatchPhase.CONNECTED)), showAddSheet = false, actions = noop)
    }

    @Test
    fun noServer() = shot("4b_no_server") {
        TropaMainScreen(loaded(CoreState.Running, watch = watch(WatchPhase.NO_SERVER)), showAddSheet = false, actions = noop)
    }

    @Test
    @Config(qualifiers = "ru-w380dp-h1700dp-xxhdpi")
    fun details() = shot("7_details") {
        TropaMainScreen(
            loaded(CoreState.Running, watch = watch(WatchPhase.CONNECTED)),
            showAddSheet = false,
            actions = noop,
            details = listOf(
                ProfileDetails("Kazan", inUse = false, balanced = false, nodes = listOf(Node("proxy", "", "VLESS", "entry.example.org:443", "xhttp, tls"))),
                ProfileDetails("Kazan FreeTurn", inUse = false, balanced = false, nodes = listOf(Node("proxy", "", "WireGuard", "127.0.0.1:9000", null))),
                ProfileDetails(
                    "Kazan + FreeTurn", inUse = true, balanced = true,
                    nodes = listOf(
                        Node("bal-1-vless", "VLESS · entry.example.org", "VLESS", "entry.example.org:443", "xhttp, tls"),
                        Node("bal-1-wireguard", "WireGuard · 127.0.0.1", "WireGuard", "127.0.0.1:9000", null),
                    ),
                ),
            ),
        )
    }

    @Test
    fun log() = shot("8_log") {
        val now = System.currentTimeMillis()
        androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
            androidx.compose.foundation.layout.Column(
                modifier = androidx.compose.ui.Modifier.padding(16.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    ProbeEvent(now - 2_000, "connected", "VLESS · entry.example.org", "entry.example.org:443", "", ok = true, latencyMs = 48),
                    ProbeEvent(now - 4_000, "download", "VLESS · entry.example.org", "entry.example.org:443", "https://speed.cloudflare.com/__down?bytes=65536", ok = true, latencyMs = 310, bytes = 65536),
                    ProbeEvent(now - 5_000, "latency", "WireGuard · 127.0.0.1", "127.0.0.1:9000", "https://www.gstatic.com/generate_204", ok = false, error = "dial udp 127.0.0.1:9000: connection refused"),
                    ProbeEvent(now - 5_100, "latency", "VLESS · entry.example.org", "entry.example.org:443", "https://www.gstatic.com/generate_204", ok = true, latencyMs = 48),
                    ProbeEvent(now - 6_000, "start", "", null, "", ok = true),
                    ProbeEvent(now - 90_000, "stop", "", null, "", ok = true),
                    ProbeEvent(now - 95_000, "no_server", "", null, "", ok = false),
                ).forEach { LogEntry(it) }
            }
        }
    }

    @Test
    fun failed() = shot("5_failed") {
        TropaMainScreen(loaded(CoreState.Failed, error = "Failed to get V2Ray config"), showAddSheet = false, actions = noop)
    }

    @Test
    fun addSheet() = shot("6_add_sheet") {
        TropaMainScreen(loaded(CoreState.Stopped), showAddSheet = true, actions = noop)
    }
}
