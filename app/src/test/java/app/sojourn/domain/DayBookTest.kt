package app.sojourn.domain

import app.sojourn.data.CountrySet
import app.sojourn.data.db.Plan
import app.sojourn.data.db.Presence
import app.sojourn.data.db.Source
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DayBookTest {
    private val today = LocalDate.of(2026, 10, 25)

    private fun day(date: String, country: String, hour: Int = 12) = LocalDate.parse(date).let {
        Presence(it, country, Source.LOCATION, it.toEpochDay() * 86_400_000 + hour * 3_600_000L, it.toEpochDay() * 86_400_000 + hour * 3_600_000L)
    }

    private fun book(rows: List<Presence>, plans: List<Plan> = emptyList(), options: CountingOptions = CountingOptions()) =
        DayBook.build(rows, plans, options, today)

    @Test
    fun travelDayCountsForBothCountriesInVisitOrder() {
        val book = book(listOf(day("2026-10-01", "ES", 18), day("2026-10-01", "PT", 9)))
        assertEquals(listOf("PT", "ES"), book[LocalDate.parse("2026-10-01")]!!.countries)
        val summary = book.summary(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-01"))
        assertEquals(1, summary.trackedDays)
        assertEquals(setOf("PT", "ES"), summary.countries.map { it.country }.toSet())
    }

    @Test
    fun countingOnlyWhereTheDayEndedCountsTheLastCountry() {
        val options = CountingOptions(policies = CountingPolicies(mapOf("PT" to DayCounting.MIDNIGHT, "ES" to DayCounting.MIDNIGHT)))
        val book = book(listOf(day("2026-10-01", "PT", 9), day("2026-10-01", "ES", 18)), options = options)
        assertEquals(listOf("PT", "ES"), book[LocalDate.parse("2026-10-01")]!!.countries)
        val summary = book.summary(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-01"))
        assertEquals(1, summary.countries.single { it.country == "ES" }.days)
        assertEquals(0, summary.countries.single { it.country == "PT" }.days)
        assertEquals(1, summary.countries.single { it.country == "PT" }.present)
    }

    @Test
    fun gapBetweenSameCountryIsInferredButNotAcrossABorder() {
        val book = book(listOf(day("2026-10-01", "PT"), day("2026-10-05", "PT"), day("2026-10-09", "ES")))
        assertEquals(DayKind.INFERRED, book[LocalDate.parse("2026-10-03")]!!.kind)
        assertNull(book[LocalDate.parse("2026-10-07")])
        assertEquals(listOf(DateRange(LocalDate.parse("2026-10-06"), LocalDate.parse("2026-10-08"))), book.gaps(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-09")))
    }

    @Test
    fun aPlanForTodayIsNotUntracked() {
        val today = LocalDate.parse("2026-10-10")
        val plan = Plan(1, "ES", today, today.plusDays(3))
        val book = DayBook.build(listOf(day("2026-10-01", "PT")) + (2..9).map { day("2026-10-%02d".format(it), "PT") }, listOf(plan), CountingOptions(), today)
        assertEquals(DayKind.PLANNED, book[today]!!.kind)
        assertEquals(emptyList<DateRange>(), book.gaps(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31")))
    }

    @Test
    fun daysKnowWhatBacksThemUp() {
        val photo = Presence(LocalDate.parse("2026-10-01"), "ES", Source.LOCATION, evidence = Source.PHOTO.bit)
        val tracked = day("2026-10-02", "ES")
        val manual = Presence(LocalDate.parse("2026-10-03"), "ES", Source.MANUAL)
        val later = day("2026-10-06", "ES")
        val book = book(listOf(photo, tracked, manual, later))
        assertEquals(Evidence.DOCUMENTED, book[photo.day]!!.evidenceOf("ES"))
        assertEquals(setOf(Source.LOCATION, Source.PHOTO), book[photo.day]!!.sources["ES"])
        assertEquals(Evidence.ASSUMED, book[LocalDate.parse("2026-10-04")]!!.evidenceOf("ES"))
        val count = book.evidence(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-06"), CountrySet(setOf("ES")))
        assertEquals(EvidenceCount(documented = 1, tracked = 2, manual = 1, assumed = 2, corroborated = 1), count)
    }

    @Test
    fun daysBetweenTwoKeptBoardingPassesAreDocumented() {
        // Three days in Poland entered by hand, flown in and out with both passes kept.
        val inbound = app.sojourn.data.db.Flight(1, "FR1", "BCN", "WAW", "ES", "PL", LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-05"), 9 * 60, 12 * 60)
        val outbound = app.sojourn.data.db.Flight(2, "FR2", "WAW", "BCN", "PL", "ES", LocalDate.parse("2026-10-08"), LocalDate.parse("2026-10-08"), 18 * 60, 21 * 60)
        val rows = listOf("2026-10-06", "2026-10-07").map { Presence(LocalDate.parse(it), "PL", Source.MANUAL) }
        val poland = CountrySet(setOf("PL"))
        val range = LocalDate.parse("2026-10-05") to LocalDate.parse("2026-10-08")
        fun build(passes: Set<Long>) = DayBook.build(rows, emptyList(), CountingOptions(), today, listOf(inbound, outbound), passes)
        assertEquals(Evidence.MANUAL, build(emptySet())[LocalDate.parse("2026-10-06")]!!.evidenceOf("PL"))
        val proven = build(setOf(1, 2))
        assertEquals(Evidence.DOCUMENTED, proven[LocalDate.parse("2026-10-06")]!!.evidenceOf("PL"))
        assertEquals(4, proven.evidence(range.first, range.second, poland).documented)
        // One pass alone proves only its own flight's day.
        assertEquals(Evidence.MANUAL, build(setOf(1))[LocalDate.parse("2026-10-07")]!!.evidenceOf("PL"))
    }

    @Test
    fun gapFillingCanBeTurnedOff() {
        val book = book(listOf(day("2026-10-01", "PT"), day("2026-10-05", "PT")), options = CountingOptions(fillGaps = false))
        assertNull(book[LocalDate.parse("2026-10-03")])
    }

    @Test
    fun plansOnlyCoverTheFutureAndNeverOverrideRecords() {
        val plan = Plan(1, "TH", LocalDate.parse("2026-10-20"), LocalDate.parse("2026-11-02"))
        val book = book(listOf(day("2026-10-25", "PT")), listOf(plan))
        assertNull(book[LocalDate.parse("2026-10-21")])
        assertEquals(DayKind.RECORDED, book[today]!!.kind)
        assertEquals(DayKind.PLANNED, book[LocalDate.parse("2026-10-26")]!!.kind)
        assertEquals(0, book.count(today.plusDays(1), LocalDate.parse("2026-11-02"), CountrySet(setOf("TH")), withPlans = false))
        assertEquals(8, book.count(today.plusDays(1), LocalDate.parse("2026-11-02"), CountrySet(setOf("TH")), withPlans = true))
    }

    @Test
    fun staysSplitAtTravelDaysWhichBelongToBoth() {
        val rows = listOf(day("2026-10-01", "PT"), day("2026-10-02", "PT"), day("2026-10-03", "PT", 9), day("2026-10-03", "ES", 20), day("2026-10-04", "ES"))
        val stays = book(rows).stays()
        assertEquals(2, stays.size)
        assertEquals(Stay("PT", LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-03"), DayKind.RECORDED), stays[0])
        assertEquals(Stay("ES", LocalDate.parse("2026-10-03"), LocalDate.parse("2026-10-04"), DayKind.RECORDED), stays[1])
    }

    @Test
    fun streakAndCurrentFollowTheLatestRecord() {
        val rows = (0..9).map { day(today.minusDays(it.toLong()).toString(), "MX") } + day(today.minusDays(10).toString(), "US")
        val book = book(rows)
        assertEquals("MX", book.current()!!.countries.last())
        assertEquals(10, book.streak("MX"))
    }
}
