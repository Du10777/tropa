package io.github.du10777.tropa.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.du10777.tropa.data.Connection
import io.github.du10777.tropa.data.SubscriptionInfo
import io.github.du10777.tropa.service.CoreState
import io.github.du10777.tropa.ui.main.ClipOffer
import io.github.du10777.tropa.ui.main.MainActions
import io.github.du10777.tropa.ui.main.MainUiState
import io.github.du10777.tropa.ui.main.TropaMainScreen
import io.github.du10777.tropa.ui.theme.TropaTheme
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

    private fun loaded(core: CoreState, clip: Boolean = false, error: String? = null) = MainUiState(
        loaded = true,
        connections = listOf(connection),
        active = connection,
        core = core,
        error = error,
        connectedSince = System.currentTimeMillis() - 493_000,
        clipOffer = if (clip) ClipOffer("x", "xsub://abc") else null,
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
    fun connected() = shot("4_connected") {
        TropaMainScreen(loaded(CoreState.Running), showAddSheet = false, actions = noop)
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
