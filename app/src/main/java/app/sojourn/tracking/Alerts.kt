package app.sojourn.tracking

import app.sojourn.data.db.RuleType
import app.sojourn.data.db.RuleWindow

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.sojourn.MainActivity
import app.sojourn.R
import app.sojourn.container
import app.sojourn.domain.Level
import app.sojourn.domain.RuleEngine
import kotlinx.coroutines.flow.first

/** Posts a notification when a rule turns to caution or alert, once per change. */
object Alerts {
    private const val CHANNEL = "limits"

    suspend fun check(context: Context) {
        val container = context.container
        val book = container.repository.currentBook()
        val rules = container.repository.rules.first().filter { it.enabled }
        val traveller = container.repository.traveller.first()
        val statuses = rules.map { RuleEngine.evaluate(it, book, traveller) }
        val active = statuses.filter { it.applies && it.level != Level.GOOD }
            // A rolling window moves every day, so its start would make a "new" warning daily;
            // a year or a stay is the same warning until it starts over.
            .associateBy { "${it.rule.id}:${it.level}:${if (it.rule.window == RuleWindow.ROLLING && it.rule.type != RuleType.RESIDENCY) "rolling" else it.window.start}" }

        val shown = container.settings.alerted.first()
        val fresh = active.filterKeys { it !in shown }
        // Forget keys that no longer apply, so a rule that recovers can warn again later.
        container.settings.setAlerted(shown.intersect(active.keys) + fresh.keys)
        if (fresh.isEmpty()) return
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        fresh.values.forEach { status ->
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_sojourn)
                .setContentTitle("${status.rule.name}: ${status.headline}")
                .setContentText(status.note ?: status.detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(status.detail, status.note).joinToString("\n")))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(if (status.level == Level.ALERT) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(status.rule.id.toInt(), notification) }
        }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Limits and residency", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "When you get close to a stay limit or a tax residency test"
                },
            )
        }
    }
}
