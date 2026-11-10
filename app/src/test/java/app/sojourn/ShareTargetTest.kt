package app.sojourn

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Sojourn shows up in "Share" and "Open with" for location files, and not for everything. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class ShareTargetTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun handles(intent: Intent) =
        context.packageManager.queryIntentActivities(intent.setPackage(context.packageName), 0).any { it.activityInfo.name == MainActivity::class.java.name }

    private val file = Uri.parse("content://com.example.files/ride.gpx")

    @Test
    fun opensLocationFiles() {
        listOf("application/gpx+xml", "application/vnd.ant.fit", "application/vnd.google-earth.kmz", "application/geo+json", "application/octet-stream").forEach {
            assertTrue(it, handles(Intent(Intent.ACTION_VIEW).setDataAndType(file, it)))
        }
    }

    @Test
    fun receivesSharedFiles() {
        assertTrue(handles(Intent(Intent.ACTION_SEND).setType("application/gpx+xml").putExtra(Intent.EXTRA_STREAM, file)))
        assertTrue(handles(Intent(Intent.ACTION_SEND_MULTIPLE).setType("application/vnd.garmin.tcx+xml")))
    }

    @Test
    fun staysOutOfUnrelatedShares() {
        assertTrue(!handles(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "hello")))
        assertTrue(!handles(Intent(Intent.ACTION_SEND).setType("image/jpeg")))
    }
}
