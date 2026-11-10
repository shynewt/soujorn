package app.sojourn

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import app.sojourn.data.TrackingMode
import app.sojourn.tracking.Tracking
import app.sojourn.ui.LocalIncomingFiles
import app.sojourn.ui.LocalIncomingText
import app.sojourn.ui.SojournRoot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Files shared to the app or opened with it, waiting for the import screen. */
    private val incoming = MutableStateFlow<List<Uri>>(emptyList())

    /** Text shared to the app (a booking email's text and HTML), waiting for the import screen. */
    private val incomingText = MutableStateFlow<Pair<String, String?>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) receive(intent)
        setContent { CompositionLocalProvider(LocalIncomingFiles provides incoming, LocalIncomingText provides incomingText) { SojournRoot() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    private fun receive(intent: Intent) {
        val uris = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            incoming.value = uris
            return
        }
        // Shared text without a file: an email's body, a confirmation page, a pass's data.
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            val html = intent.getStringExtra(Intent.EXTRA_HTML_TEXT)
            if (!text.isNullOrBlank() || !html.isNullOrBlank()) incomingText.value = text.orEmpty() to html
        }
    }

    override fun onResume() {
        super.onResume()
        // Opening the app is a free moment to check: location access is foreground now, so this
        // works even without "allow all the time". Skipped when the last check is recent.
        lifecycleScope.launch {
            val settings = container.settings.current()
            val stale = System.currentTimeMillis() - (settings.lastCheck ?: 0) > RECHECK_AFTER_MS
            if (settings.onboarded && settings.tracking != TrackingMode.OFF && stale) Tracking.checkNow(this@MainActivity)
        }
    }

    private companion object {
        const val RECHECK_AFTER_MS = 30 * 60 * 1000L
    }
}
