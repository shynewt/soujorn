package app.sojourn.data.geo

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One closed ring of a country border, in degrees. */
class Ring(val xs: FloatArray, val ys: FloatArray) {
    val minX = xs.min()
    val maxX = xs.max()
    val minY = ys.min()
    val maxY = ys.max()
}

/** A country's borders as built by tools/build_geodata.py. */
class CountryShape(val code: String, val labelX: Float, val labelY: Float, val rings: List<Ring>) {
    val minX = rings.minOf { it.minX }
    val maxX = rings.maxOf { it.maxX }
    val minY = rings.minOf { it.minY }
    val maxY = rings.maxOf { it.maxY }

    /**
     * Even-odd test across every ring: a country's polygons never overlap, so holes (lakes,
     * enclaves) cancel out and separate islands add up without tracking which ring is which.
     */
    fun contains(x: Float, y: Float): Boolean {
        if (x < minX || x > maxX || y < minY || y > maxY) return false
        var inside = false
        for (ring in rings) {
            if (x < ring.minX || x > ring.maxX || y < ring.minY || y > ring.maxY) continue
            val xs = ring.xs
            val ys = ring.ys
            var j = xs.size - 1
            for (i in xs.indices) {
                if ((ys[i] > y) != (ys[j] > y) && x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i]) {
                    inside = !inside
                }
                j = i
            }
        }
        return inside
    }

    /** Squared distance to the nearest border segment, with longitude scaled by [lonScale]. */
    fun distanceSquared(x: Float, y: Float, lonScale: Float): Float {
        var best = Float.MAX_VALUE
        for (ring in rings) {
            val xs = ring.xs
            val ys = ring.ys
            var j = xs.size - 1
            for (i in xs.indices) {
                val ax = xs[j] * lonScale
                val ay = ys[j]
                val dx = xs[i] * lonScale - ax
                val dy = ys[i] - ay
                val px = x * lonScale - ax
                val py = y - ay
                val lengthSquared = dx * dx + dy * dy
                val t = if (lengthSquared == 0f) 0f else ((px * dx + py * dy) / lengthSquared).coerceIn(0f, 1f)
                val ex = px - t * dx
                val ey = py - t * dy
                best = min(best, ex * ex + ey * ey)
                j = i
            }
        }
        return best
    }
}

object GeoShapes {
    fun read(input: InputStream): List<CountryShape> {
        val data = DataInputStream(BufferedInputStream(input, 64 * 1024))
        val magic = ByteArray(4).also { data.readFully(it) }
        if (String(magic, Charsets.US_ASCII) != "SJGB") throw IOException("Not a border file")
        data.readUnsignedByte() // version
        val scale = Math.pow(10.0, data.readUnsignedByte().toDouble()).toFloat()
        val count = data.readUnsignedShort()
        return List(count) {
            val code = String(byteArrayOf(data.readByte(), data.readByte()), Charsets.US_ASCII)
            val labelX = data.readInt() / scale
            val labelY = data.readInt() / scale
            val rings = mutableListOf<Ring>()
            repeat(data.readUnsignedShort()) {
                repeat(data.readUnsignedShort()) {
                    val points = data.readVarint()
                    val xs = FloatArray(points)
                    val ys = FloatArray(points)
                    var x = 0
                    var y = 0
                    for (i in 0 until points) {
                        x += unzigzag(data.readVarint())
                        y += unzigzag(data.readVarint())
                        xs[i] = x / scale
                        ys[i] = y / scale
                    }
                    rings += Ring(xs, ys)
                }
            }
            CountryShape(code, labelX, labelY, rings)
        }
    }

    private fun DataInputStream.readVarint(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val byte = readUnsignedByte()
            result = result or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
    }

    private fun unzigzag(value: Int) = (value ushr 1) xor -(value and 1)
}

/** Turns coordinates into a country without any network access. */
class CountryLocator(private val shapes: List<CountryShape>) {
    fun locate(latitude: Double, longitude: Double): String? {
        val x = longitude.toFloat()
        val y = latitude.toFloat()
        // Shapes are stored smallest first, so an enclave wins over the country around it.
        shapes.firstOrNull { it.contains(x, y) }?.let { return it.code }

        // The 1:50m coastline is generalised by up to a kilometre or so, and a fix at a
        // marina or on a ferry lands in the sea. Take the nearest border within ~30 km.
        val lonScale = cos(Math.toRadians(latitude)).toFloat().coerceAtLeast(0.05f)
        val reach = COASTAL_REACH_DEGREES
        var best: String? = null
        var bestDistance = reach * reach
        for (shape in shapes) {
            if (x < shape.minX - reach / lonScale || x > shape.maxX + reach / lonScale) continue
            if (y < shape.minY - reach || y > shape.maxY + reach) continue
            val distance = shape.distanceSquared(x, y, lonScale)
            if (distance < bestDistance) {
                bestDistance = distance
                best = shape.code
            }
        }
        return best
    }

    private companion object {
        const val COASTAL_REACH_DEGREES = 0.27f
    }
}

/** Equal Earth projection (Šavrič, Patterson, Jenny 2018), scaled to unit-less map space. */
object EqualEarth {
    private const val A1 = 1.340264
    private const val A2 = -0.081106
    private const val A3 = 0.000893
    private const val A4 = 0.003796
    private val M = sqrt(3.0) / 2

    fun project(longitude: Float, latitude: Float, out: FloatArray) {
        val lambda = Math.toRadians(longitude.toDouble())
        val theta = kotlin.math.asin(M * kotlin.math.sin(Math.toRadians(latitude.toDouble())))
        val t2 = theta * theta
        val t6 = t2 * t2 * t2
        out[0] = (lambda * cos(theta) / (M * (A1 + 3 * A2 * t2 + t6 * (7 * A3 + 9 * A4 * t2)))).toFloat()
        out[1] = -(theta * (A1 + A2 * t2 + t6 * (A3 + A4 * t2))).toFloat()
    }
}

/** Country outlines projected once, ready to be scaled onto a canvas. */
class WorldMapData(shapes: List<CountryShape>) {
    /**
     * [focus] bounds the country's main landmass (minX, minY, maxX, maxY): rings of at least a
     * fifth of its largest. It leaves out far-off territories, like French Guiana for France or
     * Svalbard for Norway, which would otherwise pull a European map out across an ocean.
     */
    class Country(val code: String, val rings: List<FloatArray>, val label: FloatArray, val area: Float, val focus: FloatArray) {
        fun contains(x: Float, y: Float): Boolean {
            var inside = false
            for (ring in rings) {
                val n = ring.size / 2
                var j = n - 1
                for (i in 0 until n) {
                    val xi = ring[i * 2]
                    val yi = ring[i * 2 + 1]
                    val xj = ring[j * 2]
                    val yj = ring[j * 2 + 1]
                    if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                    j = i
                }
            }
            return inside
        }
    }

    val countries: List<Country>
    val minX: Float
    val minY: Float
    val width: Float
    val height: Float

    init {
        val point = FloatArray(2)
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        countries = shapes.map { shape ->
            var area = 0f
            val ringAreas = mutableListOf<Float>()
            val rings = shape.rings.map { ring ->
                val out = FloatArray(ring.xs.size * 2)
                for (i in ring.xs.indices) {
                    EqualEarth.project(ring.xs[i], ring.ys[i], point)
                    out[i * 2] = point[0]
                    out[i * 2 + 1] = point[1]
                    x0 = min(x0, point[0])
                    x1 = max(x1, point[0])
                    y0 = min(y0, point[1])
                    y1 = max(y1, point[1])
                }
                val ringArea = polygonArea(out)
                ringAreas += ringArea
                area += ringArea
                out
            }
            val largest = ringAreas.maxOrNull() ?: 0f
            val focus = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            rings.forEachIndexed { index, ring ->
                if (ringAreas[index] < largest * MAINLAND_SHARE) return@forEachIndexed
                for (i in 0 until ring.size / 2) {
                    focus[0] = min(focus[0], ring[i * 2])
                    focus[1] = min(focus[1], ring[i * 2 + 1])
                    focus[2] = max(focus[2], ring[i * 2])
                    focus[3] = max(focus[3], ring[i * 2 + 1])
                }
            }
            EqualEarth.project(shape.labelX, shape.labelY, point)
            Country(shape.code, rings, point.copyOf(), area, focus)
        }
        minX = x0
        minY = y0
        width = x1 - x0
        height = y1 - y0
    }

    /**
     * The part of the map to show for [codes], as (left, top, width, height) in map space: their
     * mainlands with room around them, at the canvas [aspect], and never past the map's edges.
     * Few countries close together zoom in; spread out, the view widens up to the whole world.
     */
    fun viewport(codes: Collection<String>, aspect: Float): FloatArray {
        val world = floatArrayOf(minX, minY, width, height)
        val shown = countries.filter { it.code in codes }
        if (shown.isEmpty()) return world
        val left = shown.minOf { it.focus[0] }
        val top = shown.minOf { it.focus[1] }
        val right = shown.maxOf { it.focus[2] }
        val bottom = shown.maxOf { it.focus[3] }
        val pad = max(right - left, bottom - top) * PADDING
        // Never closer than about a region's width, so one small country keeps its neighbours.
        var w = max(right - left + pad * 2, width * MIN_SPAN)
        var h = bottom - top + pad * 2
        if (w / h < aspect) w = h * aspect else h = w / aspect
        if (w > width) {
            w = width
            h = w / aspect
        }
        if (h > height) {
            h = height
            w = h * aspect
        }
        val x = ((left + right) / 2 - w / 2).coerceIn(minX, minX + width - w)
        val y = ((top + bottom) / 2 - h / 2).coerceIn(minY, minY + height - h)
        return floatArrayOf(x, y, w, h)
    }

    private companion object {
        const val MAINLAND_SHARE = 0.2f
        const val PADDING = 0.15f
        const val MIN_SPAN = 0.12f
    }

    private fun polygonArea(ring: FloatArray): Float {
        var sum = 0f
        val n = ring.size / 2
        var j = n - 1
        for (i in 0 until n) {
            sum += ring[j * 2] * ring[i * 2 + 1] - ring[i * 2] * ring[j * 2 + 1]
            j = i
        }
        return kotlin.math.abs(sum) / 2
    }
}
