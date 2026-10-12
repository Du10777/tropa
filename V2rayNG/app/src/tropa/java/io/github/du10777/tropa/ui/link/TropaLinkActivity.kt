package io.github.du10777.tropa.ui.link

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.du10777.tropa.ui.main.TropaMainActivity

/**
 * Entry point for links (xsub://, v2rayng://install-sub) and text shared to Tropa: hands the text
 * over to the main screen, which adds the connection and shows the result.
 */
class TropaLinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (!text.isNullOrBlank()) {
            startActivity(
                Intent(this, TropaMainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_TEXT, text)
            )
        }
        finish()
    }

    companion object {
        const val EXTRA_TEXT = "io.github.du10777.tropa.extra.IMPORT_TEXT"
    }
}
