package app.sojourn.data.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/**
 * How a day in a country became known. The order is stored as bits in [Presence.evidence], so
 * new sources go at the end and none is ever moved or removed.
 */
enum class Source {
    LOCATION, NETWORK, PHOTO, MANUAL, IMPORT, FLIGHT;

    val bit: Int get() = 1 shl ordinal
}

/**
 * Being in [country] at some point on [day]. Only the country is ever stored, never coordinates.
 * [firstSeen] and [lastSeen] order the countries of a travel day; they are null for days that
 * were entered by hand.
 */
@Entity(tableName = "presence", primaryKeys = ["day", "country"])
data class Presence(
    val day: LocalDate,
    val country: String,
    val source: Source,
    val firstSeen: Long? = null,
    val lastSeen: Long? = null,
    /**
     * Every other source that has backed this day up since, as [Source.bit]s: photos taken
     * there, a booking, a location export. [source] stays the way it first became known.
     */
    @ColumnInfo(defaultValue = "0") val evidence: Int = 0,
)

/** Every source behind a day, the first one included. */
val Presence.sources: Set<Source> get() = Source.entries.filterTo(LinkedHashSet()) { it == source || evidence and it.bit != 0 }

@Entity(tableName = "plans")
data class Plan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val country: String,
    val start: LocalDate,
    val end: LocalDate,
    val note: String = "",
    /**
     * Nothing is planned on these days: it keeps flights from implying a stay there, after the
     * user took that country off the days. Never shown as a stay of its own.
     */
    @ColumnInfo(defaultValue = "0") val cleared: Boolean = false,
)

/**
 * A flight, entered by hand. Its countries come from the airports and are kept, so a flight
 * means the same even if the airport list changes. Times are minutes after local midnight.
 */
@Entity(tableName = "flights")
data class Flight(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val number: String,
    val origin: String,
    val destination: String,
    val originCountry: String,
    val destinationCountry: String,
    val departureDate: LocalDate,
    val arrivalDate: LocalDate,
    val departureMinute: Int? = null,
    val arrivalMinute: Int? = null,
    val note: String = "",
    /** The booking reference (PNR), as the boarding pass or booking gives it. */
    @ColumnInfo(defaultValue = "") val reference: String = "",
    /** Who flew, as the airline writes it ("DOE/JANE MRS"). */
    @ColumnInfo(defaultValue = "") val passenger: String = "",
    @ColumnInfo(defaultValue = "") val seat: String = "",
)

/** What a piece of evidence is. */
enum class DocumentKind(val label: String) {
    /** A photo taken there that day, linked in the gallery, never copied. */
    PHOTO("Photo"),

    /** A boarding pass or ticket file, kept in the app. */
    BOARDING_PASS("Boarding pass"),

    /** A calendar event, kept as it was. */
    CALENDAR_EVENT("Calendar event"),

    /** A booking email or other booking text. */
    BOOKING("Booking"),
}

/**
 * Something that shows where you were on [day]: kept so a year's evidence can be exported as
 * one pack, e.g. for a tax residence certificate. A photo is only a link to the gallery
 * ([uri]), so no coordinates are ever copied into the app; files the app keeps are in
 * [file], under the app's own storage; [text] holds a calendar event or booking as it was.
 */
@Entity(tableName = "documents")
data class Document(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: LocalDate,
    val country: String,
    val kind: DocumentKind,
    val title: String,
    val uri: String? = null,
    val file: String? = null,
    val text: String = "",
    /** For photos: other photos of the same day and country, one per line, to choose from. */
    val candidates: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    /** The last day it covers, for a period (a utility bill's, a lease's); null for one day. */
    val until: LocalDate? = null,
    /** The flight it backs up, for a boarding pass, a flight's calendar event or booking. */
    val flightId: Long? = null,
    @ColumnInfo(defaultValue = "") val note: String = "",
)

/** A photo document's other photos to choose from. */
val Document.alternatives: List<String> get() = candidates.lines().filter { it.isNotBlank() }

enum class RuleType {
    /** At most [Rule.days] days in the countries within the window. */
    LIMIT,

    /** At least [Rule.days] days in the countries within the window. */
    MINIMUM,

    /** The US substantial presence test, which weighs the two previous years. */
    US_PRESENCE,

    /**
     * A country's tax residency, made of one or more tests (Cyprus: 183 days, or 60 days with a
     * home and work there), set out in [Rule.params]. Its goal says whether to avoid or keep it.
     */
    RESIDENCY,
}

enum class RuleWindow {
    /** The last [Rule.windowDays] days, sliding with every day (Schengen 90/180). */
    ROLLING,

    /** A calendar or tax year starting on [Rule.yearStart]. */
    YEAR,

    /** A single uninterrupted stay, as most visa-free entries are limited. */
    STAY,
}

@Entity(tableName = "rules")
data class Rule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: RuleType,
    val countries: String,
    val days: Int,
    val window: RuleWindow,
    val windowDays: Int = 180,
    /** Month * 100 + day the year window starts on: 101 for calendar years, 406 for the UK. */
    val yearStart: Int = 101,
    val enabled: Boolean = true,
    val position: Int = 0,
    /**
     * A limit for visitors, like Schengen 90/180 or a visa-free stay: days in countries where
     * the user is a citizen or holds a residence permit don't count. Tax rules count every day.
     */
    @ColumnInfo(defaultValue = "0") val forVisitors: Boolean = false,
    /** How days count for this rule, by [app.sojourn.domain.DayCounting] name; null for the countries' own. */
    val counting: String? = null,
    /** Residency tests, answers and options as JSON ([app.sojourn.domain.ResidencyParams]); empty otherwise. */
    @ColumnInfo(defaultValue = "") val params: String = "",
)

class Converters {
    @TypeConverter fun fromDate(date: LocalDate): Long = date.toEpochDay()

    @TypeConverter fun toDate(epochDay: Long): LocalDate = LocalDate.ofEpochDay(epochDay)

    @TypeConverter fun fromSource(source: Source): String = source.name

    @TypeConverter fun toSource(name: String): Source = Source.valueOf(name)

    @TypeConverter fun fromRuleType(type: RuleType): String = type.name

    @TypeConverter fun toRuleType(name: String): RuleType = RuleType.valueOf(name)

    @TypeConverter fun fromWindow(window: RuleWindow): String = window.name

    @TypeConverter fun toWindow(name: String): RuleWindow = RuleWindow.valueOf(name)

    @TypeConverter fun fromDocumentKind(kind: DocumentKind): String = kind.name

    @TypeConverter fun toDocumentKind(name: String): DocumentKind = DocumentKind.valueOf(name)
}

@Dao
interface PresenceDao {
    @Query("SELECT * FROM presence ORDER BY day")
    fun observeAll(): Flow<List<Presence>>

    @Query("SELECT * FROM presence ORDER BY day")
    suspend fun all(): List<Presence>

    @Query("SELECT * FROM presence WHERE day = :day")
    suspend fun onDay(day: LocalDate): List<Presence>

    @Query("SELECT day FROM presence WHERE day BETWEEN :from AND :to")
    suspend fun daysBetween(from: LocalDate, to: LocalDate): List<LocalDate>

    @Query("SELECT * FROM presence WHERE day BETWEEN :from AND :to ORDER BY day")
    suspend fun between(from: LocalDate, to: LocalDate): List<Presence>

    /** Puts the days from [from] to [to] back to exactly [rows], as they were before an edit. */
    @Transaction
    suspend fun restoreRange(from: LocalDate, to: LocalDate, rows: List<Presence>) {
        clear(from, to)
        upsert(rows)
    }

    @Upsert
    suspend fun upsert(presence: List<Presence>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissing(presence: List<Presence>)

    @Query("DELETE FROM presence WHERE day BETWEEN :from AND :to")
    suspend fun clear(from: LocalDate, to: LocalDate)

    @Query("DELETE FROM presence WHERE day = :day AND country = :country")
    suspend fun remove(day: LocalDate, country: String)

    @Query("DELETE FROM presence WHERE country = :country AND day BETWEEN :from AND :to")
    suspend fun removeCountry(from: LocalDate, to: LocalDate, country: String)

    @Query("DELETE FROM presence")
    suspend fun clearAll()

    /** Moves the days from [from] to [to] from one country to another, keeping travel days' other half. */
    @Transaction
    suspend fun swapCountry(from: LocalDate, to: LocalDate, old: String, rows: List<Presence>) {
        removeCountry(from, to, old)
        insertMissing(rows)
    }

    /** Replaces whatever was known about the days from [from] to [to] with one country. */
    @Transaction
    suspend fun replaceRange(from: LocalDate, to: LocalDate, rows: List<Presence>) {
        clear(from, to)
        upsert(rows)
    }

    @Query("DELETE FROM presence WHERE day BETWEEN :from AND :to AND country != :country")
    suspend fun removeOthers(from: LocalDate, to: LocalDate, country: String)

    /**
     * Leaves [country] alone on the days from [from] to [to]. Days already recorded there keep
     * how they were recorded (a tracked day stays tracked); the others get [rows].
     */
    @Transaction
    suspend fun setCountry(from: LocalDate, to: LocalDate, country: String, rows: List<Presence>) {
        removeOthers(from, to, country)
        insertMissing(rows)
    }

    @Query("UPDATE presence SET evidence = evidence | :bits WHERE day = :day AND country = :country")
    suspend fun addEvidence(day: LocalDate, country: String, bits: Int): Int

    /**
     * Adds what [rows] found: as supporting evidence where the same country is already
     * recorded, as new days where nothing is known (or anywhere, unless [onlyEmpty]).
     * Returns how many days were added and how many already there were backed up.
     */
    @Transaction
    suspend fun addFound(rows: List<Presence>, onlyEmpty: Boolean): Pair<Int, Int> {
        var added = 0
        var backed = 0
        val known = if (rows.isEmpty()) emptySet() else daysBetween(rows.minOf { it.day }, rows.maxOf { it.day }).toSet()
        for (row in rows) {
            if (addEvidence(row.day, row.country, row.source.bit) > 0) {
                backed++
            } else if (!onlyEmpty || row.day !in known) {
                insertMissing(listOf(row))
                added++
            }
        }
        return added to backed
    }
}

@Dao
interface PlanDao {
    @Query("SELECT * FROM plans ORDER BY start")
    fun observeAll(): Flow<List<Plan>>

    @Query("SELECT * FROM plans ORDER BY start")
    suspend fun all(): List<Plan>

    @Upsert
    suspend fun upsert(plan: Plan): Long

    @Upsert
    suspend fun upsertAll(plans: List<Plan>)

    @Query("DELETE FROM plans WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM plans WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<Long>)

    @Query("DELETE FROM plans")
    suspend fun clearAll()

    /** Replaces [removed] plans with [added] ones in one go, so the plan never shows half an edit. */
    @Transaction
    suspend fun rewrite(removed: List<Long>, added: List<Plan>) {
        if (removed.isNotEmpty()) deleteAll(removed)
        upsertAll(added)
    }
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY position, id")
    fun observeAll(): Flow<List<Rule>>

    @Query("SELECT * FROM rules ORDER BY position, id")
    suspend fun all(): List<Rule>

    @Upsert
    suspend fun upsert(rule: Rule): Long

    @Upsert
    suspend fun upsertAll(rules: List<Rule>)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM rules")
    suspend fun clearAll()
}

/** Adds [Rule.forVisitors], set for the Schengen and per-stay rules made before it existed. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rules ADD COLUMN forVisitors INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE rules SET forVisitors = 1 WHERE countries = '@schengen' OR window = 'STAY'")
    }
}

/** Adds flights. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `flights` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `number` TEXT NOT NULL, " +
                "`origin` TEXT NOT NULL, `destination` TEXT NOT NULL, `originCountry` TEXT NOT NULL, `destinationCountry` TEXT NOT NULL, " +
                "`departureDate` INTEGER NOT NULL, `arrivalDate` INTEGER NOT NULL, `departureMinute` INTEGER, `arrivalMinute` INTEGER, " +
                "`note` TEXT NOT NULL)",
        )
    }
}

/** Adds the sources that back up each day, and the documents that show them. */
/** Flights keep their booking reference, passenger and seat; evidence can cover a period and name its flight. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE flights ADD COLUMN reference TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE flights ADD COLUMN passenger TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE flights ADD COLUMN seat TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE documents ADD COLUMN until INTEGER")
        db.execSQL("ALTER TABLE documents ADD COLUMN flightId INTEGER")
        db.execSQL("ALTER TABLE documents ADD COLUMN note TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE presence ADD COLUMN evidence INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `documents` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `day` INTEGER NOT NULL, " +
                "`country` TEXT NOT NULL, `kind` TEXT NOT NULL, `title` TEXT NOT NULL, `uri` TEXT, `file` TEXT, `text` TEXT NOT NULL, " +
                "`candidates` TEXT NOT NULL, `addedAt` INTEGER NOT NULL)",
        )
    }
}

/** Adds cleared plans, and per-rule counting and residency tests. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE plans ADD COLUMN cleared INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE rules ADD COLUMN counting TEXT")
        db.execSQL("ALTER TABLE rules ADD COLUMN params TEXT NOT NULL DEFAULT ''")
    }
}

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY day, kind, id")
    fun observeAll(): Flow<List<Document>>

    @Query("SELECT * FROM documents WHERE day BETWEEN :from AND :to ORDER BY day, kind, id")
    suspend fun between(from: LocalDate, to: LocalDate): List<Document>

    @Query("SELECT * FROM documents WHERE day = :day AND country = :country AND kind = :kind")
    suspend fun find(day: LocalDate, country: String, kind: DocumentKind): List<Document>

    @Upsert
    suspend fun upsert(document: Document): Long

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM documents WHERE file = :file")
    suspend fun usingFile(file: String): Int

    @Query("DELETE FROM documents")
    suspend fun clearAll()
}

@Dao
interface FlightDao {
    @Query("SELECT * FROM flights ORDER BY departureDate, departureMinute")
    fun observeAll(): Flow<List<Flight>>

    @Query("SELECT * FROM flights ORDER BY departureDate, departureMinute")
    suspend fun all(): List<Flight>

    @Upsert
    suspend fun upsert(flight: Flight): Long

    @Upsert
    suspend fun upsertAll(flights: List<Flight>)

    @Query("DELETE FROM flights WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM flights")
    suspend fun clearAll()
}

@Database(entities = [Presence::class, Plan::class, Rule::class, Flight::class, Document::class], version = 6)
@TypeConverters(Converters::class)
abstract class SojournDatabase : RoomDatabase() {
    abstract fun presence(): PresenceDao

    abstract fun plans(): PlanDao

    abstract fun rules(): RuleDao

    abstract fun flights(): FlightDao

    abstract fun documents(): DocumentDao

    companion object {
        fun create(context: Context): SojournDatabase =
            Room.databaseBuilder(context, SojournDatabase::class.java, "sojourn.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build()
    }
}
