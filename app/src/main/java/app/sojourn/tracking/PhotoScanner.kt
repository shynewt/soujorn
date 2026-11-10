package app.sojourn.tracking

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import app.sojourn.data.geo.CountryLocator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext


/**
 * Reads where photos were taken, straight from their EXIF data. Only the country survives the
 * scan; coordinates are dropped as soon as they've been looked up.
 */
class PhotoScanner(private val context: Context, private val locator: () -> CountryLocator) {
    suspend fun scan(from: LocalDate, to: LocalDate, progress: (done: Int, total: Int) -> Unit): List<FoundDay> = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        // Widen by a day each side: DATE_TAKEN is UTC, the EXIF date is local to the trip.
        val start = from.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val photos = mutableListOf<Pair<Long, Long>>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN),
            "${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} < ?",
            arrayOf(start.toString(), end.toString()),
            "${MediaStore.Images.Media.DATE_TAKEN} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) photos += cursor.getLong(0) to cursor.getLong(1)
        }

        // A handful of photos per day settles the country; reading thousands of files doesn't.
        val sampled = photos.groupBy { Instant.ofEpochMilli(it.second).atZone(zone).toLocalDate() }
            .values.flatMap { day -> if (day.size <= PER_DAY) day else List(PER_DAY) { i -> day[i * (day.size - 1) / (PER_DAY - 1)] } }

        val found = HashMap<LocalDate, HashMap<String, Int>>()
        val links = HashMap<LocalDate, HashMap<String, MutableList<String>>>()
        sampled.forEachIndexed { index, (id, taken) ->
            ensureActive()
            progress(index, sampled.size)
            var uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) uri = MediaStore.setRequireOriginal(uri)
            val exif = runCatching { context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) } }.getOrNull() ?: return@forEachIndexed
            val latLong = exif.latLong ?: return@forEachIndexed
            if (latLong[0] == 0.0 && latLong[1] == 0.0) return@forEachIndexed
            val country = locator().locate(latLong[0], latLong[1]) ?: return@forEachIndexed
            val date = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let(::exifDate)
                ?: Instant.ofEpochMilli(taken).atZone(zone).toLocalDate()
            if (date < from || date > to) return@forEachIndexed
            val counts = found.getOrPut(date) { HashMap() }
            counts[country] = (counts[country] ?: 0) + 1
            // Only a link to the photo is kept, never its location: the gallery keeps the file.
            links.getOrPut(date) { HashMap() }.getOrPut(country) { mutableListOf() } += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString()
        }
        progress(sampled.size, sampled.size)
        found.entries.sortedBy { it.key }.map { FoundDay(it.key, it.value, links[it.key].orEmpty()) }
    }

    /** EXIF dates read "2024:03:07 14:22:10", in the local time where the photo was taken. */
    private fun exifDate(value: String): LocalDate? = runCatching {
        LocalDate.of(value.substring(0, 4).toInt(), value.substring(5, 7).toInt(), value.substring(8, 10).toInt())
    }.getOrNull()

    private companion object {
        const val PER_DAY = 6
    }
}
