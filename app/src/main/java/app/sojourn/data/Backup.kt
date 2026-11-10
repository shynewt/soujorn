package app.sojourn.data

import app.sojourn.data.db.Flight
import app.sojourn.data.db.Plan
import app.sojourn.data.db.Presence
import app.sojourn.data.db.Rule
import app.sojourn.data.db.RuleType
import app.sojourn.data.db.RuleWindow
import app.sojourn.data.db.Source
import app.sojourn.data.db.sources
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The backup file. It is plain JSON on purpose: readable, diffable, and easy to take to a
 * spreadsheet or another app. Unknown fields are ignored, so newer files still import.
 */
@Serializable
data class BackupFile(
    val app: String = "sojourn",
    val version: Int = 1,
    val exportedAt: String,
    val days: List<DayDto>,
    val plans: List<PlanDto>,
    val rules: List<RuleDto>,
    val settings: Settings? = null,
    val flights: List<FlightDto> = emptyList(),
)

@Serializable
data class FlightDto(
    val number: String,
    val origin: String,
    val destination: String,
    val originCountry: String,
    val destinationCountry: String,
    val departureDate: String,
    val arrivalDate: String,
    val departureMinute: Int? = null,
    val arrivalMinute: Int? = null,
    val note: String = "",
    val reference: String = "",
    val passenger: String = "",
    val seat: String = "",
)

@Serializable
data class DayDto(
    val date: String,
    val country: String,
    val source: String,
    val firstSeen: Long? = null,
    val lastSeen: Long? = null,
    /** The other sources that back the day up, by name; missing in older backups. */
    val evidence: List<String> = emptyList(),
)

@Serializable
data class PlanDto(val country: String, val start: String, val end: String, val note: String = "", val cleared: Boolean = false)

@Serializable
data class RuleDto(
    val name: String,
    val type: String,
    val countries: String,
    val days: Int,
    val window: String,
    val windowDays: Int = 180,
    val yearStart: Int = 101,
    val enabled: Boolean = true,
    /** Missing in backups made before visitor rules existed; inferred the way the migration does. */
    val forVisitors: Boolean? = null,
    val counting: String? = null,
    /** Residency tests and answers, as stored in the rule. */
    val params: String = "",
)

object BackupCodec {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(presence: List<Presence>, plans: List<Plan>, rules: List<Rule>, settings: Settings, flights: List<Flight> = emptyList()): String = json.encodeToString(
        BackupFile.serializer(),
        BackupFile(
            exportedAt = Instant.now().toString(),
            days = presence.map { DayDto(it.day.toString(), it.country, it.source.name, it.firstSeen, it.lastSeen, (it.sources - it.source).map { s -> s.name }) },
            plans = plans.map { PlanDto(it.country, it.start.toString(), it.end.toString(), it.note, it.cleared) },
            rules = rules.map { RuleDto(it.name, it.type.name, it.countries, it.days, it.window.name, it.windowDays, it.yearStart, it.enabled, it.forVisitors, it.counting, it.params) },
            // Where the backup folder lives and when checks ran belong to this device only.
            settings = settings.copy(backupFolder = null, lastBackup = null, lastCheck = null, lastCheckCountry = null, lastCheckSource = null),
            flights = flights.map {
                FlightDto(
                    it.number, it.origin, it.destination, it.originCountry, it.destinationCountry,
                    it.departureDate.toString(), it.arrivalDate.toString(), it.departureMinute, it.arrivalMinute, it.note,
                    it.reference, it.passenger, it.seat,
                )
            },
        ),
    )

    fun decode(text: String): BackupFile {
        val file = json.decodeFromString(BackupFile.serializer(), text)
        require(file.app == "sojourn") { "This isn't a Sojourn backup" }
        return file
    }

    fun BackupFile.presence() = days.map {
        Presence(
            LocalDate.parse(it.date),
            it.country,
            runCatching { Source.valueOf(it.source) }.getOrDefault(Source.IMPORT),
            it.firstSeen,
            it.lastSeen,
            it.evidence.mapNotNull { name -> Source.entries.firstOrNull { s -> s.name == name } }.fold(0) { bits, s -> bits or s.bit },
        )
    }

    fun BackupFile.planEntities() = plans.map { Plan(country = it.country, start = LocalDate.parse(it.start), end = LocalDate.parse(it.end), note = it.note, cleared = it.cleared) }

    fun BackupFile.flightEntities() = flights.map {
        Flight(
            number = it.number,
            origin = it.origin,
            destination = it.destination,
            originCountry = it.originCountry,
            destinationCountry = it.destinationCountry,
            departureDate = LocalDate.parse(it.departureDate),
            arrivalDate = LocalDate.parse(it.arrivalDate),
            departureMinute = it.departureMinute,
            arrivalMinute = it.arrivalMinute,
            note = it.note,
            reference = it.reference,
            passenger = it.passenger,
            seat = it.seat,
        )
    }

    fun BackupFile.ruleEntities() = rules.mapIndexed { index, it ->
        Rule(
            name = it.name,
            type = runCatching { RuleType.valueOf(it.type) }.getOrDefault(RuleType.LIMIT),
            countries = it.countries,
            days = it.days,
            window = RuleWindow.valueOf(it.window),
            windowDays = it.windowDays,
            yearStart = it.yearStart,
            enabled = it.enabled,
            position = index,
            forVisitors = it.forVisitors ?: (it.countries == "@schengen" || it.window == RuleWindow.STAY.name),
            counting = it.counting,
            params = it.params,
        )
    }
}
