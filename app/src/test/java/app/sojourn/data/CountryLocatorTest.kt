package app.sojourn.data

import app.sojourn.data.geo.CountryLocator
import app.sojourn.data.geo.GeoShapes
import app.sojourn.data.geo.WorldMapData
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** Runs against the real shipped border file, so a broken regeneration fails here. */
class CountryLocatorTest {
    companion object {
        private lateinit var locator: CountryLocator

        @BeforeClass
        @JvmStatic
        fun load() {
            locator = CountryLocator(File("src/main/assets/borders.bin").inputStream().use(GeoShapes::read))
        }
    }

    @Test
    fun citiesLandInTheirCountry() {
        val cities = mapOf(
            "PT" to (38.7223 to -9.1393), // Lisbon
            "ES" to (41.3874 to 2.1686), // Barcelona
            "MX" to (19.4326 to -99.1332), // Mexico City
            "TH" to (13.7563 to 100.5018), // Bangkok
            "US" to (40.7128 to -74.0060), // New York
            "AR" to (-34.6037 to -58.3816), // Buenos Aires
            "JP" to (35.6762 to 139.6503), // Tokyo
            "GE" to (41.7151 to 44.8271), // Tbilisi
            "ZA" to (-33.9249 to 18.4241), // Cape Town
            "NZ" to (-36.8485 to 174.7633), // Auckland
            "XK" to (42.6629 to 21.1655), // Pristina
        )
        cities.forEach { (code, point) -> assertEquals(code, locator.locate(point.first, point.second)) }
    }

    @Test
    fun enclavesAndMicrostatesWinOverTheirSurroundings() {
        assertEquals("VA", locator.locate(41.9029, 12.4534))
        assertEquals("SM", locator.locate(43.9424, 12.4578))
        assertEquals("MC", locator.locate(43.7384, 7.4246))
        assertEquals("LS", locator.locate(-29.3151, 27.4869))
        assertEquals("SG", locator.locate(1.3521, 103.8198))
        assertEquals("MT", locator.locate(35.8989, 14.5146))
    }

    @Test
    fun offshoreFixesSnapToTheNearbyCoastButNotFromMidOcean() {
        // A ferry a few kilometres out of Barcelona harbour.
        assertEquals("ES", locator.locate(41.33, 2.22))
        assertNull(locator.locate(30.0, -40.0))
    }

    private val map by lazy { WorldMapData(File("src/main/assets/world.bin").inputStream().use(GeoShapes::read)) }

    private fun view(vararg codes: String) = map.viewport(codes.toList(), map.width / map.height)

    @Test
    fun mapFramesEuropeWithoutOverseasTerritories() {
        // France's borders include French Guiana; it mustn't pull the view across the Atlantic.
        val europe = view("PT", "ES", "FR", "DE", "PL", "CY")
        assertTrue("width ${europe[2] / map.width}", europe[2] < map.width * 0.3f)
        val guiana = map.countries.first { it.code == "FR" }.rings.flatMap { ring -> (0 until ring.size / 2).map { ring[it * 2] } }.min()
        assertTrue(europe[0] > guiana)
    }

    @Test
    fun mapZoomsOutAsCountriesSpreadAndStaysOnTheMap() {
        val one = view("PT")
        val spread = view("US", "PT", "TH")
        assertTrue(one[2] >= map.width * 0.12f - 1e-4f)
        assertTrue(spread[2] > one[2] * 3)
        listOf(one, spread, view("NZ", "FJ"), view("IS", "NO")).forEach { (x, y, w, h) ->
            assertTrue(x >= map.minX - 1e-4f && x + w <= map.minX + map.width + 1e-4f)
            assertTrue(y >= map.minY - 1e-4f && y + h <= map.minY + map.height + 1e-4f)
        }
        assertTrue(view().contentEquals(floatArrayOf(map.minX, map.minY, map.width, map.height)))
    }

    @Test
    fun worldMapProjectsEveryCountry() {
        val map = WorldMapData(File("src/main/assets/world.bin").inputStream().use(GeoShapes::read))
        assertTrue(map.countries.size > 200)
        assertTrue(map.width / map.height in 1.8f..2.4f)
        val portugal = map.countries.first { it.code == "PT" }
        assertTrue(portugal.contains(portugal.label[0], portugal.label[1]))
    }
}
