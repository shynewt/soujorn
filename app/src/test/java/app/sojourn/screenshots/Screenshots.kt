package app.sojourn.screenshots

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.runtime.CompositionLocalProvider
import app.sojourn.data.ThemeMode
import app.sojourn.data.db.Flight
import app.sojourn.data.flights.AirportDirectory
import app.sojourn.ui.flights.LocalAirports
import app.sojourn.data.db.Plan
import app.sojourn.data.db.Presence
import app.sojourn.data.db.Source
import app.sojourn.data.travel.PassengerMatch
import app.sojourn.data.geo.GeoShapes
import app.sojourn.data.geo.WorldMapData
import app.sojourn.domain.CountingOptions
import app.sojourn.domain.DayBook
import app.sojourn.domain.RuleEngine
import app.sojourn.domain.RulePreset
import app.sojourn.domain.Traveller
import app.sojourn.ui.theme.SojournTheme
import java.io.File
import java.time.LocalDate

const val PHONE = "w411dp-h891dp-xxhdpi"

// TALL_PHONE fits a whole scrolling page in one capture.
const val TALL_PHONE = "w411dp-h2000dp-xxhdpi"

fun ComposeContentTestRule.screenshot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
    setContent {
        SojournTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
            CompositionLocalProvider(LocalAirports provides Sample.airports) { content() }
        }
    }
    mainClock.advanceTimeBy(2_000)
    waitForIdle()
    val bitmap = onRoot().captureToImage().asAndroidBitmap()
    val directory = File("build/screenshots").apply { mkdirs() }
    File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
}

/** A year of a nomad's life: winter in the US and Mexico, spring in Spain, summer in Portugal. */
object Sample {
    val today: LocalDate = LocalDate.now()
    private val year = today.year

    private fun stay(country: String, from: LocalDate, to: LocalDate) =
        generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to && it <= today }.map { Presence(it, country, Source.LOCATION, 0, 0) }.toList()

    private fun d(month: Int, day: Int, y: Int = year) = LocalDate.of(y, month, day)

    val presence: List<Presence> =
        stay("US", d(3, 1, year - 1), d(8, 31, year - 1)) +
            stay("AR", d(9, 1, year - 1), d(10, 20, year - 1)) +
            stay("BR", d(10, 21, year - 1), d(11, 30, year - 1)) +
            stay("US", d(12, 1, year - 1), d(2, 10)) +
            stay("MX", d(2, 11), d(4, 20)) +
            stay("ES", d(4, 21), d(6, 10)) +
            stay("ME", d(6, 11), d(8, 25)) +
            stay("GB", d(9, 3), d(9, 20)) +
            stay("FR", d(9, 21), d(9, 24)) +
            stay("PT", d(9, 25), today)

    val plans = listOf(
        Plan(1, "TH", today.plusDays(21), today.plusDays(55), "Chiang Mai co-living"),
        Plan(2, "VN", today.plusDays(56), today.plusDays(70)),
        Plan(3, "US", today.plusDays(71), today.plusDays(84), "Holidays with family"),
    )

    val airports: AirportDirectory by lazy { File("src/main/assets/airports.tsv").inputStream().use(AirportDirectory::read) }

    val flights = listOf(
        Flight(1, "TK1760", "LIS", "IST", "PT", "TR", today.plusDays(9), today.plusDays(9), 7 * 60 + 5, 13 * 60 + 40),
        Flight(2, "TK68", "IST", "BKK", "TR", "TH", today.plusDays(21), today.plusDays(22), 20 * 60 + 10, 9 * 60 + 35),
        Flight(3, "VN606", "BKK", "SGN", "TH", "VN", today.plusDays(56), today.plusDays(56), 11 * 60 + 15, 12 * 60 + 45),
        Flight(4, "FR8337", "BCN", "BGY", "ES", "IT", today.minusDays(40), today.minusDays(40), 6 * 60 + 30, 8 * 60),
    )

    val book = DayBook.build(presence, plans, CountingOptions(), today, flights)

    /** A German living in Portugal: Schengen doesn't apply, home and Spain's tax line do. */
    val traveller = Traveller(citizenships = setOf("DE"), residencePermits = emptySet())

    val rules = listOf(
        RulePreset.KEEP_RESIDENCY.template("PT").copy(id = 1),
        RulePreset.AVOID_RESIDENCY.template("ES").copy(id = 2),
        RulePreset.VISA_STAY.template("TH").copy(id = 3),
        RulePreset.SCHENGEN.template().copy(id = 4),
    )

    val statuses = rules.map { RuleEngine.evaluate(it, book, traveller) }.sortedBy { !it.applies }

    /** A Cypriot year with a departure day, and Spain's count with sporadic absences. */
    val residencyStatuses: List<app.sojourn.domain.RuleStatus> by lazy {
        val rows = stay("CY", d(1, 5), d(3, 10)) + stay("GR", d(3, 10), d(3, 14)) + stay("CY", d(3, 14), d(4, 20)) +
            stay("ES", d(4, 20), d(5, 30)) + stay("FR", d(5, 31), d(6, 6)) + stay("ES", d(6, 7), d(7, 20))
        val book = DayBook.build(rows, emptyList(), CountingOptions(), today)
        val cyprus = RulePreset.AVOID_RESIDENCY.template("CY").let { rule ->
            val params = app.sojourn.domain.ResidencyParams.decode(rule.params)!!.copy(answers = mapOf("cy_work" to true))
            rule.copy(id = 10, params = params.encode())
        }
        listOf(cyprus, RulePreset.AVOID_RESIDENCY.template("ES").copy(id = 11), RulePreset.KEEP_RESIDENCY.template("PT").copy(id = 12))
            .map { RuleEngine.evaluate(it, book, traveller) }
    }

    val trips = listOf(
        app.sojourn.ui.imports.TripItem(app.sojourn.data.travel.FoundTrip(app.sojourn.data.travel.TripKind.FLIGHT, today.plusDays(9), number = "TP 1351", from = "LCA", to = "LIS", departureMinute = 605, passenger = "DOE/JANE MRS", source = "Calendar: Flight to Lisbon (TP 1351)"), "CY", "PT", PassengerMatch.MATCH, false),
        app.sojourn.ui.imports.TripItem(app.sojourn.data.travel.FoundTrip(app.sojourn.data.travel.TripKind.FLIGHT, today.plusDays(30), number = "IB 3100", from = "MAD", to = "LIS", passenger = "DOE/ROSA MRS", source = "Confirmación de reserva"), "ES", "PT", PassengerMatch.DIFFERENT, false),
        app.sojourn.ui.imports.TripItem(app.sojourn.data.travel.FoundTrip(app.sojourn.data.travel.TripKind.TRAIN, today.minusDays(40), number = "9014", from = "Paris Gare du Nord", to = "London St Pancras", departureMinute = 493, source = "Eurostar booking"), "FR", "GB", PassengerMatch.UNKNOWN, false),
        app.sojourn.ui.imports.TripItem(app.sojourn.data.travel.FoundTrip(app.sojourn.data.travel.TripKind.FLIGHT, today.minusDays(40), number = "FR 8337", from = "BCN", to = "BGY", source = "Boarding pass"), "ES", "IT", PassengerMatch.UNKNOWN, true),
    )

    val map: WorldMapData by lazy { WorldMapData(File("src/main/assets/world.bin").inputStream().use(GeoShapes::read)) }

}
