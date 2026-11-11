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
 * Writes a dated backup zip into a folder the user picked, e.g. one synced by Syncthing or a
 * cloud drive, and keeps the newest few. Nothing ever leaves the phone any other way.
 */
object AutoBackup {
    private const val WORK = "auto-backup"
    private const val KEEP = 8
    private const val PREFIX = "sojourn-auto-"
    private const val PARTIAL = "sojourn-unfinished-backup"

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
        val name = "$PREFIX${LocalDate.now()}.zip"
        folder.findFile("$PARTIAL.zip")?.delete()
        // Written under another name and renamed when complete, so a failed write never replaces a good backup.
        val file = folder.createFile("application/zip", PARTIAL) ?: error("Couldn't create the backup file")
        runCatching {
            context.contentResolver.openOutputStream(file.uri, "wt")?.use { container.backups.write(it) } ?: error("Couldn't open the backup file")
        }.onFailure {
            file.delete()
            throw it
        }
        folder.findFile(name)?.delete()
        check(file.renameTo(name)) { "Couldn't name the backup $name" }
        folder.listFiles()
            .filter { it.name?.startsWith(PREFIX) == true && it.name?.endsWith(".zip") == true }
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
