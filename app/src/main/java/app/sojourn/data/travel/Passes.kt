package app.sojourn.data.travel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ext.SdkExtensions
import androidx.annotation.RequiresExtension
import com.google.zxing.RGBLuminanceSource
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Boarding pass barcodes in pictures: a screenshot of a pass in Google Wallet or an airline
 * app, a photo of a paper one, or a PDF page. Google Wallet has no way for other apps to read
 * its passes, so a screenshot is the way in.
 */
object Barcodes {
    /** Enough for a group booking's passes, one or two passengers a page. */
    private const val MAX_PAGES = 40

    /**
     * The text of every barcode in [bitmap], wherever they are (a page with each passenger's
     * pass), trying it turned when none reads.
     */
    fun read(bitmap: Bitmap): List<String> {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        BarcodeSearch.readAll(bitmap.width, bitmap.height, pixels).takeIf { it.isNotEmpty() }?.let { return it }
        val turned = rotate(bitmap, 90f)
        return try {
            listOfNotNull(BarcodeSearch.decode(RGBLuminanceSource(turned.width, turned.height, IntArray(turned.width * turned.height).also { turned.getPixels(it, 0, turned.width, 0, 0, turned.width, turned.height) })))
        } finally {
            turned.recycle()
        }
    }

    private fun rotate(bitmap: Bitmap, degrees: Float): Bitmap =
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)

    /** Decodes an image file no bigger than needed for a barcode. */
    fun image(open: () -> InputStream): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3000) sample *= 2
        return open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }

    /**
     * Each page of a PDF, white behind, drawn large enough for the small barcode on a printed
     * A4 pass, handed to [each] one at a time so only one page is in memory.
     */
    fun <T> pdfPages(file: File, maxPages: Int = MAX_PAGES, each: (Bitmap) -> T): List<T> =
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
            (0 until minOf(renderer.pageCount, maxPages)).map { index ->
                val bitmap = renderer.openPage(index).use { page ->
                    val scale = 3000f / maxOf(page.width, page.height)
                    Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    }
                }
                try {
                    each(bitmap)
                } finally {
                    bitmap.recycle()
                }
            }
        }

    /**
     * The text printed on each page of a PDF, where Android can read it: Android 15 on, or 12
     * to 14 with its PDF module updated. Empty elsewhere.
     */
    fun pdfText(file: File, maxPages: Int = MAX_PAGES): List<String> = runCatching {
        fun open() = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        when {
            Build.VERSION.SDK_INT >= 35 -> PdfRenderer(open()).use { renderer ->
                (0 until minOf(renderer.pageCount, maxPages)).map { i -> renderer.openPage(i).use { page -> page.textContents.joinToString("\n") { it.text } } }
            }
            Build.VERSION.SDK_INT >= 31 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13 -> preVText(open(), maxPages)
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
    private fun preVText(descriptor: ParcelFileDescriptor, maxPages: Int): List<String> = PdfRendererPreV(descriptor).use { renderer ->
        (0 until minOf(renderer.pageCount, maxPages)).map { i -> renderer.openPage(i).use { page -> page.textContents.joinToString("\n") { it.text } } }
    }
}

/**
 * Apple Wallet passes (.pkpass), which airlines also send by email: a zip whose pass.json has
 * the barcode's text (a boarding pass's BCBP) and often the flight in plain fields too.
 */
object Pkpass {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun read(input: InputStream): String? {
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (entry.name == "pass.json") return zip.readBytes().decodeToString()
            }
        }
    }

    /** The barcode messages in a pass.json. */
    fun messages(passJson: String): List<String> {
        val root = runCatching { json.parseToJsonElement(passJson) as JsonObject }.getOrNull() ?: return emptyList()
        val barcodes = (root["barcodes"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("message") as? JsonPrimitive } +
            listOfNotNull((root["barcode"] as? JsonObject)?.get("message") as? JsonPrimitive)
        return barcodes.mapNotNull { it.contentOrNull }
    }
}
