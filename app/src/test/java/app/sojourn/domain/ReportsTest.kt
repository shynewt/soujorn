package app.sojourn.domain

import app.sojourn.data.BackupCodec
import app.sojourn.data.BackupCodec.flightEntities
import app.sojourn.data.BackupCodec.planEntities
import app.sojourn.data.BackupCodec.presence
import app.sojourn.data.BackupCodec.ruleEntities
import app.sojourn.data.Settings
import app.sojourn.data.TrackingMode
import app.sojourn.data.db.Plan
import app.sojourn.data.db.Presence
import app.sojourn.data.db.Source
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportsTest {
    private val today = LocalDate.of(2026, 10, 25)

    private fun stay(country: String, from: String, to: String) =
        generateSequence(LocalDate.parse(from)) { it.plusDays(1) }.takeWhile { it <= LocalDate.parse(to) }.map { Presence(it, country, Source.MANUAL) }.toList()

    @Test
    fun feiePicksTheQualifyingPeriodCoveringMostOfTheTaxYear() {
        // Abroad from mid-February 2025 on, with a two-week US visit in the summer.
        val rows = stay("US", "2025-01-01", "2025-02-14") + stay("MX", "2025-02-15", "2025-07-01") + stay("US", "2025-07-02", "2025-07-15") +
            stay("PT", "2025-07-16", "2026-10-25")
        val book = DayBook.build(rows, emptyList(), CountingOptions(), today)
        val report = Reports.feie(book, 2025)
        assertTrue(report.qualifies)
        // Starting any later than 25 Jan covers less of 2025; starting earlier misses 330.
        assertEquals(LocalDate.parse("2025-01-25"), report.period.start)
        assertEquals(330, report.fullDaysAbroad)
        assertEquals(341, report.qualifyingDaysInYear)
        assertEquals(listOf("US", "MX", "US", "PT"), report.rows.map { it.country })
    }

    @Test
    fun feieReportsShortfall() {
        val rows = stay("US", "2025-01-01", "2025-03-31") + stay("FR", "2025-04-01", "2025-12-31") + stay("US", "2026-01-01", "2026-10-25")
        val report = Reports.feie(DayBook.build(rows, emptyList(), CountingOptions(), today), 2025)
        assertFalse(report.qualifies)
        assertEquals(275, report.fullDaysAbroad)
    }

    @Test
    fun travelHistoryListsTripsNewestFirstAndSkipsHome() {
        val rows = stay("JP", "2023-04-01", "2023-04-10") + stay("CA", "2024-05-01", "2024-05-03") + stay("JP", "2025-11-01", "2025-11-05") + stay("US", "2025-01-01", "2025-01-31")
        val history = Reports.travelHistory(DayBook.build(rows, emptyList(), CountingOptions(fillGaps = false), today), today.minusYears(5), exclude = setOf("US"))
        assertEquals(listOf("JP", "CA"), history.map { it.country })
        assertEquals(2, history.first().trips.size)
        assertEquals(LocalDate.parse("2025-11-01"), history.first().trips.first().start)
    }

    @Test
    fun backupRoundTripsEverything() {
        val presence = listOf(Presence(LocalDate.parse("2026-01-02"), "PT", Source.LOCATION, 10, 20), Presence(LocalDate.parse("2026-01-03"), "ES", Source.PHOTO))
        val plans = listOf(Plan(5, "TH", LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-20"), "Bangkok"))
        val rules = listOf(RulePreset.SCHENGEN.template(), RulePreset.AVOID_RESIDENCY.template("GB"))
        val settings = Settings(onboarded = true, tracking = TrackingMode.NETWORK, citizenships = setOf("US", "IE"), backupFolder = "content://x", lastCheck = 5)
        val flights = listOf(app.sojourn.data.db.Flight(7, "TP1234", "LIS", "LCA", "PT", "CY", LocalDate.parse("2026-11-12"), LocalDate.parse("2026-11-12"), 605, 1000, "window seat"))
        val decoded = BackupCodec.decode(BackupCodec.encode(presence, plans, rules, settings, flights))
        assertEquals(flights.map { it.copy(id = 0) }, decoded.flightEntities())
        assertEquals(presence, decoded.presence())
        assertEquals(plans.map { it.copy(id = 0) }, decoded.planEntities())
        assertEquals(rules.mapIndexed { i, r -> r.copy(position = i) }, decoded.ruleEntities())
        assertEquals(setOf("US", "IE"), decoded.settings!!.citizenships)
        assertNull("device-only settings stay out of backups", decoded.settings!!.backupFolder)
    }

    @Test
    fun oldBackupsGetTheVisitorFlagInferred() {
        val json = """{"app":"sojourn","exportedAt":"x","days":[],"plans":[],"rules":[
            {"name":"Schengen 90/180","type":"LIMIT","countries":"@schengen","days":90,"window":"ROLLING","windowDays":180},
            {"name":"PT tax","type":"LIMIT","countries":"PT","days":183,"window":"YEAR"}]}"""
        assertEquals(listOf(true, false), BackupCodec.decode(json).ruleEntities().map { it.forVisitors })
    }

    @Test
    fun backupIgnoresUnknownFields() {
        val json = """{"app":"sojourn","version":9,"exportedAt":"x","days":[{"date":"2026-01-01","country":"PT","source":"TELEPATHY","mood":"great"}],"plans":[],"rules":[],"future":true}"""
        val decoded = BackupCodec.decode(json)
        assertEquals(Source.IMPORT, decoded.presence().single().source)
    }
}
