package app.sojourn.data.travel

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.google.zxing.pdf417.PDF417Reader
import kotlin.math.abs

/**
 * Finds a boarding pass barcode anywhere on a page. ZXing reads a QR or PDF417 code wherever
 * it is in a picture, but its Aztec and Data Matrix detectors start from the middle, so the
 * code in a corner of a printed boarding pass, where airlines put it, is missed. This also
 * looks for Aztec bull's-eyes and for each patch of ink set apart by white space, and reads
 * each one cut out with the code in the middle.
 */
object BarcodeSearch {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.PDF_417, BarcodeFormat.AZTEC, BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX),
        DecodeHintType.TRY_HARDER to true,
    )
    private const val WHITE = -1
    private const val MOST_TRIES = 14

    internal data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /** The text of a barcode in an ARGB picture, wherever on it the barcode is. */
    fun read(width: Int, height: Int, argb: IntArray): String? {
        val source = RGBLuminanceSource(width, height, argb)
        decode(source)?.let { return it }
        val matrix = runCatching { HybridBinarizer(source).blackMatrix }.getOrNull() ?: return null
        val boxes = (bullseyes(matrix) + patches(matrix)).take(MOST_TRIES)
        return boxes.firstNotNullOfOrNull { decode(cut(argb, width, height, it)) }
    }

    /**
     * Every barcode in an ARGB picture: a PDF page with a pass for each passenger may have
     * several. Each is read once however many ways it's found.
     */
    fun readAll(width: Int, height: Int, argb: IntArray): List<String> {
        val source = RGBLuminanceSource(width, height, argb)
        val found = LinkedHashSet<String>()
        runCatching { GenericMultipleBarcodeReader(MultiFormatReader()).decodeMultiple(BinaryBitmap(HybridBinarizer(source)), hints) }
            .getOrNull()?.forEach { found += it.text }
        // PDF417 finds all of its own on a page, as printed passes stack them.
        runCatching { PDF417Reader().decodeMultiple(BinaryBitmap(HybridBinarizer(source)), hints) }.getOrNull()?.forEach { found += it.text }
        val matrix = runCatching { HybridBinarizer(source).blackMatrix }.getOrNull() ?: return found.toList()
        (bullseyes(matrix) + patches(matrix)).take(MOST_TRIES * 2).forEach { box -> decode(cut(argb, width, height, box))?.let(found::add) }
        return found.toList()
    }

    /** Reads the whole picture as it is. */
    fun decode(source: LuminanceSource): String? =
        runCatching { MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text }.getOrNull()

    /** [box] with room around it, white beyond the page's edge so the box stays in the middle. */
    private fun cut(argb: IntArray, width: Int, height: Int, box: Box): LuminanceSource {
        val margin = maxOf(box.width, box.height) / 6 + 8
        val left = box.left - margin
        val top = box.top - margin
        val w = box.width + 2 * margin
        val h = box.height + 2 * margin
        val out = IntArray(w * h) { WHITE }
        for (y in 0 until h) {
            val sy = top + y
            if (sy !in 0 until height) continue
            val from = maxOf(left, 0)
            val to = minOf(left + w, width)
            if (to > from) System.arraycopy(argb, sy * width + from, out, y * w + (from - left), to - from)
        }
        return RGBLuminanceSource(w, h, out)
    }

    /**
     * Middles of Aztec codes: rings of one module each, so a row through the middle crosses
     * seven equal runs (white, black, white, the black centre, white, black, white) between
     * black ones, and so does the column.
     */
    internal fun bullseyes(m: BitMatrix): List<Box> {
        val found = mutableListOf<Triple<Int, Int, Float>>()
        for (y in 0 until m.height step 2) {
            val runs = runs(m.width) { m[it, y] }
            for (i in 4 until runs.size - 4) {
                val (start, length, black) = runs[i]
                if (!black) continue
                val module = ring(runs, i) ?: continue
                val x = start + length / 2
                val column = runs(m.height) { m[x, it] }
                val j = column.indexOfFirst { (s, l, _) -> y in s until s + l }
                if (j < 4 || j >= column.size - 4 || ring(column, j) == null) continue
                val cy = column[j].let { (s, l, _) -> s + l / 2 }
                if (found.none { (fx, fy, fm) -> abs(fx - x) < 4 * fm && abs(fy - cy) < 4 * fm }) found += Triple(x, cy, module)
            }
        }
        // A boarding pass's Aztec code is 23 to 41 modules across; take room for the largest.
        return found.map { (x, y, module) ->
            val half = (22 * module).toInt()
            Box(x - half, y - half, x + half, y + half)
        }
    }

    /** The module size if the runs around [i] are a bull's-eye's, else null. */
    private fun ring(runs: List<Triple<Int, Int, Boolean>>, i: Int): Float? {
        val middle = (i - 3..i + 3).map { runs[it].second }
        val module = middle.sorted()[3].toFloat()
        if (module < 2) return null
        if (middle.any { it < module * 0.5f || it > module * 1.6f }) return null
        // The outer black ring may run into black modules beyond it, so only its start counts.
        if (runs[i - 4].second < module * 0.5f || runs[i + 4].second < module * 0.5f) return null
        return module
    }

    /** Runs of one colour along a line: start, length and whether black. */
    private inline fun runs(size: Int, black: (Int) -> Boolean): List<Triple<Int, Int, Boolean>> {
        val runs = mutableListOf<Triple<Int, Int, Boolean>>()
        var start = 0
        var colour = black(0)
        for (p in 1..size) {
            val c = p < size && black(p)
            if (p == size || c != colour) {
                runs += Triple(start, p - start, colour)
                start = p
                colour = c
            }
        }
        return runs
    }

    /**
     * Patches of ink with white space around them, largest first. A cell counts as ink when it
     * has edges both ways, so the lines framing a boarding pass don't join it to the barcode.
     */
    internal fun patches(m: BitMatrix): List<Box> {
        val cell = maxOf(4, minOf(m.width, m.height) / 200)
        val cw = m.width / cell
        val ch = m.height / cell
        if (cw < 3 || ch < 3) return emptyList()
        val ink = BooleanArray(cw * ch)
        for (cy in 0 until ch) for (cx in 0 until cw) {
            val x0 = cx * cell
            val y0 = cy * cell
            val across = (y0 until y0 + cell).any { y -> (x0 + 1 until x0 + cell).any { x -> m[x, y] != m[x - 1, y] } }
            val down = across && (x0 until x0 + cell).any { x -> (y0 + 1 until y0 + cell).any { y -> m[x, y] != m[x, y - 1] } }
            ink[cy * cw + cx] = down
        }
        // Grown by a cell so a code's modules join up, then split into connected patches.
        val grown = BooleanArray(cw * ch) { i ->
            val cx = i % cw
            val cy = i / cw
            (maxOf(cy - 1, 0)..minOf(cy + 1, ch - 1)).any { y -> (maxOf(cx - 1, 0)..minOf(cx + 1, cw - 1)).any { x -> ink[y * cw + x] } }
        }
        val seen = BooleanArray(cw * ch)
        val boxes = mutableListOf<Pair<Box, Int>>()
        val queue = ArrayDeque<Int>()
        for (startCell in grown.indices) {
            if (!grown[startCell] || seen[startCell]) continue
            var l = cw; var t = ch; var r = 0; var b = 0; var count = 0
            queue += startCell
            seen[startCell] = true
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                val x = c % cw
                val y = c / cw
                if (ink[c]) count++
                l = minOf(l, x); r = maxOf(r, x); t = minOf(t, y); b = maxOf(b, y)
                for ((nx, ny) in listOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
                    if (nx !in 0 until cw || ny !in 0 until ch) continue
                    val n = ny * cw + nx
                    if (grown[n] && !seen[n]) { seen[n] = true; queue += n }
                }
            }
            val w = r - l + 1
            val h = b - t + 1
            val long = maxOf(w, h)
            val short = minOf(w, h)
            // Big enough for a code, no longer than a PDF417's 6:1, and mostly ink.
            if (short >= 5 && long <= 6 * short && count >= w * h * 0.35) {
                boxes += Box(l * cell, t * cell, (r + 1) * cell, (b + 1) * cell) to w * h
            }
        }
        return boxes.sortedByDescending { it.second }.map { it.first }
    }
}
