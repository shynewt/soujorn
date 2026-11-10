package app.sojourn.data

import android.app.Application
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sojourn.data.db.MIGRATION_1_2
import app.sojourn.data.db.MIGRATION_2_3
import app.sojourn.data.db.MIGRATION_3_4
import app.sojourn.data.db.MIGRATION_4_5
import app.sojourn.data.db.SojournDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Upgrades keep every day and rule; rules made before visitor limits get the flag inferred. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SojournDatabase::class.java)

    @Test
    fun version1To2() {
        helper.createDatabase(NAME, 1).apply {
            execSQL("INSERT INTO presence (day, country, source, firstSeen, lastSeen) VALUES (20000, 'ES', 'LOCATION', 1, 2)")
            execSQL("INSERT INTO rules (id, name, type, countries, days, window, windowDays, yearStart, enabled, position) VALUES (1, 'Schengen 90/180', 'LIMIT', '@schengen', 90, 'ROLLING', 180, 101, 1, 0)")
            execSQL("INSERT INTO rules (id, name, type, countries, days, window, windowDays, yearStart, enabled, position) VALUES (2, 'Thailand stay', 'LIMIT', 'TH', 60, 'STAY', 180, 101, 1, 1)")
            execSQL("INSERT INTO rules (id, name, type, countries, days, window, windowDays, yearStart, enabled, position) VALUES (3, 'Spain tax residency', 'LIMIT', 'ES', 183, 'YEAR', 180, 101, 1, 2)")
            close()
        }
        val db = helper.runMigrationsAndValidate(NAME, 2, true, MIGRATION_1_2)
        db.query("SELECT id, forVisitors FROM rules ORDER BY id").use { cursor ->
            val flags = buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getInt(1)) }
            assertEquals(listOf(1L to 1, 2L to 1, 3L to 0), flags)
        }
        db.query("SELECT COUNT(*) FROM presence").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun version2To3AddsFlights() {
        helper.createDatabase(NAME, 2).apply {
            execSQL("INSERT INTO presence (day, country, source, firstSeen, lastSeen) VALUES (20000, 'CY', 'LOCATION', 1, 2)")
            close()
        }
        val db = helper.runMigrationsAndValidate(NAME, 3, true, MIGRATION_2_3)
        db.execSQL("INSERT INTO flights (number, origin, destination, originCountry, destinationCountry, departureDate, arrivalDate, note) VALUES ('TP1234', 'LIS', 'LCA', 'PT', 'CY', 20400, 20400, '')")
        db.query("SELECT COUNT(*) FROM flights").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun version3To4KeepsRulesAndPlans() {
        helper.createDatabase(NAME, 3).apply {
            execSQL("INSERT INTO plans (id, country, start, `end`, note) VALUES (1, 'TH', 20400, 20430, 'Chiang Mai')")
            execSQL("INSERT INTO rules (id, name, type, countries, days, window, windowDays, yearStart, enabled, position, forVisitors) VALUES (1, 'Spain tax residency', 'LIMIT', 'ES', 183, 'YEAR', 180, 101, 1, 0, 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(NAME, 4, true, MIGRATION_3_4)
        db.query("SELECT cleared FROM plans").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT counting IS NULL, params FROM rules").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
            assertEquals("", cursor.getString(1))
        }
    }

    @Test
    fun version4To5KeepsDaysAndAddsEvidence() {
        helper.createDatabase(NAME, 4).apply {
            execSQL("INSERT INTO presence (day, country, source, firstSeen, lastSeen) VALUES (20000, 'ES', 'LOCATION', 1, 2)")
            close()
        }
        val db = helper.runMigrationsAndValidate(NAME, 5, true, MIGRATION_4_5)
        db.query("SELECT country, evidence FROM presence").use { cursor ->
            cursor.moveToFirst()
            assertEquals("ES", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
        }
        db.execSQL("INSERT INTO documents (day, country, kind, title, text, candidates, addedAt) VALUES (20000, 'ES', 'PHOTO', 'Photo', '', '', 0)")
        db.query("SELECT COUNT(*) FROM documents").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1, cursor.getInt(0))
        }
    }

    private companion object {
        const val NAME = "migration-test.db"
    }
}
