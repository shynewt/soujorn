package app.sojourn.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.sojourn.container
import app.sojourn.data.Settings
import app.sojourn.data.TrackingMode
import java.time.Instant
import java.util.concurrent.TimeUnit

object Tracking {
    private const val PERIODIC = "track"
    private const val NOW = "track-now"

    /** Brings the background schedule in line with the settings; safe to call any time. */
    fun schedule(context: Context, settings: Settings) {
        val work = WorkManager.getInstance(context)
        if (settings.tracking == TrackingMode.OFF) {
            work.cancelUniqueWork(PERIODIC)
            return
        }
        val hours = settings.intervalHours.coerceIn(1, 24).toLong()
        val request = PeriodicWorkRequestBuilder<TrackWorker>(hours, TimeUnit.HOURS, 30, TimeUnit.MINUTES).build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** One check soon, e.g. when the app opens or the time zone changes. */
    fun checkNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<TrackWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }
}

class TrackWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = applicationContext.container
        val settings = container.settings.current()
        if (settings.tracking == TrackingMode.OFF) return Result.success()

        val probe = LocationProbe(applicationContext) { container.locator }
        val sighting = (if (settings.tracking == TrackingMode.LOCATION) probe.locate() else null) ?: probe.networkCountry()
        if (sighting != null) {
            container.repository.record(sighting.country, sighting.source, Instant.ofEpochMilli(sighting.time))
            container.settings.update {
                it.copy(lastCheck = System.currentTimeMillis(), lastCheckCountry = sighting.country, lastCheckSource = sighting.source.name)
            }
        }
        if (settings.limitAlerts) Alerts.check(applicationContext)
        return Result.success()
    }
}

/**
 * A changed time zone is the cheapest hint that a border was crossed, so it triggers a check
 * right away instead of waiting for the next scheduled one. The broadcast is exempt from the
 * background limits on manifest receivers.
 */
class TimeZoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) Tracking.checkNow(context)
    }
}
