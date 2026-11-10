package app.sojourn.data.travel

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Printed boarding passes as PDF pages draw them: an A4 page with a frame, lines of text and
 * the barcode off to one side.
 */
class BarcodeSearchTest {
    private val bcbp = "M1SMITH/JOHN MR       EABC123 BCNBUDW6 2271 314Y012A0045 100"
    private val width = 1650
    private val height = 2335

    private fun page(format: BarcodeFormat, x: Int, y: Int, module: Int, frame: Boolean = true, data: String = bcbp, pixels: IntArray? = null): IntArray {
        val text = pixels == null
        val pixels = pixels ?: IntArray(width * height) { -1 }
        fun fill(l: Int, t: Int, w: Int, h: Int, colour: Int = 0xFF000000.toInt()) {
            for (py in maxOf(t, 0) until minOf(t + h, height)) for (px in maxOf(l, 0) until minOf(l + w, width)) pixels[py * width + px] = colour
        }
        if (frame) {
            // The boarding pass's frame, and a rule across under its heading.
            fill(60, 60, width - 120, 4); fill(60, 1100, width - 120, 4); fill(60, 60, 4, 1044); fill(width - 64, 60, 4, 1044)
            fill(60, 300, width - 120, 3)
        }
        // Lines of "words" all over: the passenger, flight, gate, seat, and the small print below.
        val random = Random(7)
        for (line in 0 until if (text) 40 else 0) {
            var px = 100
            val py = 120 + line * 54
            if (py in y - 30..y + 400) continue
            while (px < width - 200) {
                val word = random.nextInt(30, 140)
                for (letter in 0 until word step 14) fill(px + letter, py + random.nextInt(0, 6), 9, random.nextInt(14, 24))
                px += word + random.nextInt(18, 40)
            }
        }
        val code = symbol(format, data)
        // Printed on white, with a quiet zone around it.
        fill(x - 3 * module, y - 3 * module, (code.width + 6) * module, (code.height + 6) * module, -1)
        for (cy in 0 until code.height) for (cx in 0 until code.width) {
            if (code[cx, cy]) fill(x + cx * module, y + cy * module, module, module)
        }
        return pixels
    }

    /** The bare symbol, one bit per module. */
    private fun symbol(format: BarcodeFormat, data: String): BitMatrix {
        val hints = mapOf(EncodeHintType.MARGIN to 0)
        return MultiFormatWriter().encode(data, format, 0, 0, hints)
    }

    @Test
    fun aztecCodeInTheCornerOfAPrintedPassIsFound() {
        val pixels = page(BarcodeFormat.AZTEC, x = 1180, y = 140, module = 6)
        // As read before: the whole page at once misses it, as ZXing's Aztec detector starts in the middle.
        assertNull(BarcodeSearch.decode(RGBLuminanceSource(width, height, pixels)))
        assertEquals(bcbp, BarcodeSearch.read(width, height, pixels))
    }

    @Test
    fun aztecCodeLowDownWithoutAFrame() {
        val pixels = page(BarcodeFormat.AZTEC, x = 150, y = 1600, module = 5, frame = false)
        assertEquals(bcbp, BarcodeSearch.read(width, height, pixels))
    }

    @Test
    fun qrCodeAnywhereIsFound() {
        assertEquals(bcbp, BarcodeSearch.read(width, height, page(BarcodeFormat.QR_CODE, x = 1150, y = 150, module = 6)))
    }

    @Test
    fun pdf417AcrossThePassIsFound() {
        assertEquals(bcbp, BarcodeSearch.read(width, height, page(BarcodeFormat.PDF_417, x = 300, y = 800, module = 3)))
    }

    @Test
    fun dataMatrixOffToOneSideIsFound() {
        assertEquals(bcbp, BarcodeSearch.read(width, height, page(BarcodeFormat.DATA_MATRIX, x = 1250, y = 150, module = 7)))
    }

    @Test
    fun aPageWithoutACodeGivesNothing() {
        val pixels = page(BarcodeFormat.AZTEC, x = 0, y = 0, module = 1).also { p -> for (i in 0 until 300 * width) p[i] = -1 }
        assertNull(BarcodeSearch.read(width, height, pixels))
    }

    @Test
    fun everyPassengersCodeOnOnePageIsRead() {
        val second = bcbp.replace("SMITH/JOHN MR  ", "SMITH/ANNA MRS ")
        for (format in listOf(BarcodeFormat.AZTEC, BarcodeFormat.QR_CODE, BarcodeFormat.PDF_417)) {
            val module = if (format == BarcodeFormat.PDF_417) 3 else 6
            // PDF417 is wide: it starts further left to fit the page.
            val x = if (format == BarcodeFormat.PDF_417) 200 else 1100
            val first = page(format, x = x, y = 140, module = module, frame = false)
            val both = page(format, x = x, y = 1500, module = module, frame = false, data = second, pixels = first)
            assertEquals(format.name, setOf(bcbp, second), BarcodeSearch.readAll(width, height, both).toSet())
        }
    }
}
