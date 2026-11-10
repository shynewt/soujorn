package app.sojourn.tracking

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.sojourn.container
import app.sojourn.data.Settings
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Writes a dated JSON backup into a folder the user picked, e.g. one synced by Syncthing or a
 * cloud drive, and keeps the newest few. Nothing ever leaves the phone any other way.
 */
object AutoBackup {
    private const val WORK = "auto-backup"
    private const val KEEP = 8

    fun schedule(context: Context, settings: Settings) {
        val work = WorkManager.getInstance(context)
        if (!settings.autoBackup || settings.backupFolder == null) {
            work.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS).build()
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Returns the written file's name. */
    suspend fun writeNow(context: Context): String {
        val container = context.container
        val folderUri = container.settings.current().backupFolder ?: error("No backup folder chosen")
        val folder = DocumentFile.fromTreeUri(context, folderUri.toUri()) ?: error("Backup folder is gone")
        if (!folder.canWrite()) error("Can't write to the backup folder")
        val name = "sojourn-${LocalDate.now()}.json"
        folder.findFile(name)?.delete()
        val file = folder.createFile("application/json", name.removeSuffix(".json")) ?: error("Couldn't create the backup file")
        val json = container.repository.exportJson()
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(json.toByteArray()) } ?: error("Couldn't open the backup file")
        folder.listFiles()
            .filter { it.name?.startsWith("sojourn-") == true && it.name?.endsWith(".json") == true }
            .sortedByDescending { it.name }
            .drop(KEEP)
            .forEach { it.delete() }
        container.settings.update { it.copy(lastBackup = System.currentTimeMillis()) }
        return file.name ?: name
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = applicationContext.container.settings.current()
        if (!settings.autoBackup || settings.backupFolder == null) return Result.success()
        // Once a week is plenty for data that changes a few times a day at most.
        val last = settings.lastBackup ?: 0
        if (System.currentTimeMillis() - last < TimeUnit.DAYS.toMillis(7) - TimeUnit.HOURS.toMillis(2)) return Result.success()
        return runCatching { AutoBackup.writeNow(applicationContext) }.fold({ Result.success() }, { Result.retry() })
    }
}
