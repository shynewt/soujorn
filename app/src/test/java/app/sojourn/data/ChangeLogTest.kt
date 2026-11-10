package app.sojourn.data

import app.sojourn.data.db.Flight
import app.sojourn.data.db.Plan
import app.sojourn.data.db.Presence
import app.sojourn.data.db.Source
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeLogTest {
    private val d = LocalDate.of(2026, 3, 4)

    @Test
    fun aChangeIsWhatItAddedAndRemoved() {
        val tracked = Presence(d, "CY", Source.LOCATION, 1, 2)
        val kept = Presence(d.plusDays(1), "ES", Source.LOCATION)
        val plan = Plan(7, "PT", d.plusDays(30), d.plusDays(40))
        val before = Tables(listOf(tracked, kept), listOf(plan), emptyList())
        val flight = Flight(3, "IB 3166", "MAD", "LHR", "ES", "GB", d, d)
        val after = Tables(listOf(Presence(d, "ES", Source.MANUAL), kept), listOf(plan.copy(end = d.plusDays(35))), listOf(flight))
        val change = Change.between(1, 0, "Set 4 Mar 2026 to Spain", before, after)
        assertEquals(listOf(LoggedDay.of(Presence(d, "ES", Source.MANUAL))), change.daysAdded)
        assertEquals(listOf(tracked), change.daysRemoved.map { it.toPresence() })
        assertEquals(listOf(plan), change.plansRemoved.map { it.toPlan() })
        assertEquals(listOf(flight), change.flightsAdded.map { it.toFlight() })
        assertEquals("1 day, 1 plan, 1 flight", change.summary)
        assertTrue(Change.between(2, 0, "Nothing", before, before).isEmpty)
    }

    @Test
    fun onlyTheLastChangesAreKept() = runBlocking {
        val log = ChangeLog(null, limit = 3)
        repeat(5) { log.add(Change(log.nextId(), it.toLong(), "Change $it", daysAdded = listOf(LoggedDay("2026-03-04", "ES", "MANUAL")))) }
        assertEquals(listOf("Change 4", "Change 3", "Change 2"), log.changes.value.map { it.label })
        log.markReverted(log.changes.value[1].id)
        assertEquals(listOf(false, true, false), log.changes.value.map { it.reverted })
    }

    @Test
    fun labelsNameTheirDays() {
        assertTrue(Change.range(d, d.plusDays(5)), Change.range(d, d.plusDays(5)).startsWith("4–9 "))
    }

    @Test
    fun backupsKeepWhatBacksEachDayUp() {
        val day = Presence(d, "ES", Source.LOCATION, 1, 2, evidence = Source.PHOTO.bit or Source.IMPORT.bit)
        val json = BackupCodec.encode(listOf(day), emptyList(), emptyList(), Settings())
        with(BackupCodec) { assertEquals(listOf(day), decode(json).presence()) }
    }
}
