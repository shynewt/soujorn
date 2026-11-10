package app.sojourn.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import app.sojourn.domain.CountingPolicies
import app.sojourn.domain.DayCounting
import kotlinx.serialization.Serializable

enum class TrackingMode {
    /** Nothing runs in the background; days are entered by hand or from photos. */
    OFF,

    /** Reads the country of the mobile network. Needs no location permission at all. */
    NETWORK,

    /** A coarse location fix every few hours, with the mobile network as a fallback. */
    LOCATION,
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * How the calendar shows days: a month at a time, a whole year as month cards or rows, or the
 * list of stays. COMPACT is kept so stored choices still read; the calendar shows it as GRID.
 */
enum class CalendarView(val label: String) { MONTH("Month"), GRID("Grid"), COMPACT("Compact"), ROWS("Rows"), LIST("List") }

@Serializable
data class Settings(
    val onboarded: Boolean = false,
    val tracking: TrackingMode = TrackingMode.OFF,
    val intervalHours: Int = 6,
    /**
     * For countries Sojourn has no rules for: a travel day counts for every country visited
     * (true), or only where the day ended (false).
     */
    val countAllCountries: Boolean = true,
    /** The user's own choice of day counting for some countries, over Sojourn's built-in rules. */
    val counting: Map<String, DayCounting> = emptyMap(),
    val calendarView: CalendarView = CalendarView.MONTH,
    /**
     * Names the user travels under, as on tickets ("Jane Doe", "DOE/JANE"), so bookings made for
     * someone else aren't imported as the user's trips.
     */
    val travellerNames: Set<String> = emptySet(),
    /** Days between two records of the same country are assumed spent there. */
    val fillGaps: Boolean = true,
    val citizenships: Set<String> = emptySet(),
    /** Countries the user may live in (residence permit, long-stay visa); visitor limits skip them. */
    val residencePermits: Set<String> = emptySet(),
    val taxResidence: String? = null,
    val usTaxpayer: Boolean = false,
    val limitAlerts: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** 1 = Monday ... 7 = Sunday, or 0 for the locale's own. */
    val weekStart: Int = 0,
    val autoBackup: Boolean = false,
    val backupFolder: String? = null,
    val lastBackup: Long? = null,
    val lastCheck: Long? = null,
    val lastCheckCountry: String? = null,
    val lastCheckSource: String? = null,
) {
    val policies: CountingPolicies
        get() = CountingPolicies(counting, if (countAllCountries) DayCounting.ANY_PART else DayCounting.MIDNIGHT)
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private object Keys {
        val onboarded = booleanPreferencesKey("onboarded")
        val tracking = stringPreferencesKey("tracking")
        val interval = intPreferencesKey("interval_hours")
        val countAll = booleanPreferencesKey("count_all_countries")
        val fillGaps = booleanPreferencesKey("fill_gaps")
        val citizenships = stringSetPreferencesKey("citizenships")
        val residencePermits = stringSetPreferencesKey("residence_permits")
        val taxResidence = stringPreferencesKey("tax_residence")
        val usTaxpayer = booleanPreferencesKey("us_taxpayer")
        val limitAlerts = booleanPreferencesKey("limit_alerts")
        val theme = stringPreferencesKey("theme")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val weekStart = intPreferencesKey("week_start")
        val autoBackup = booleanPreferencesKey("auto_backup")
        val backupFolder = stringPreferencesKey("backup_folder")
        val lastBackup = longPreferencesKey("last_backup")
        val lastCheck = longPreferencesKey("last_check")
        val lastCheckCountry = stringPreferencesKey("last_check_country")
        val lastCheckSource = stringPreferencesKey("last_check_source")
        val alerted = stringSetPreferencesKey("alerted")
        val counting = stringSetPreferencesKey("counting")
        val calendarView = stringPreferencesKey("calendar_view")
        val travellerNames = stringSetPreferencesKey("traveller_names")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { it.toSettings() }

    /** Alert keys already shown, so a limit warning is posted once rather than every check. */
    val alerted: Flow<Set<String>> = context.dataStore.data.map { it[Keys.alerted] ?: emptySet() }

    suspend fun current(): Settings = settings.first()

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { prefs -> prefs.write(transform(prefs.toSettings())) }
    }

    suspend fun setAlerted(keys: Set<String>) {
        context.dataStore.edit { it[Keys.alerted] = keys }
    }

    private fun Preferences.toSettings(): Settings {
        val defaults = Settings()
        return Settings(
            onboarded = this[Keys.onboarded] ?: defaults.onboarded,
            tracking = this[Keys.tracking]?.let { runCatching { TrackingMode.valueOf(it) }.getOrNull() } ?: defaults.tracking,
            intervalHours = this[Keys.interval] ?: defaults.intervalHours,
            countAllCountries = this[Keys.countAll] ?: defaults.countAllCountries,
            counting = this[Keys.counting].orEmpty().mapNotNull { entry ->
                val (code, policy) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                runCatching { code to DayCounting.valueOf(policy) }.getOrNull()
            }.toMap(),
            calendarView = this[Keys.calendarView]?.let { runCatching { CalendarView.valueOf(it) }.getOrNull() } ?: defaults.calendarView,
            travellerNames = this[Keys.travellerNames] ?: defaults.travellerNames,
            fillGaps = this[Keys.fillGaps] ?: defaults.fillGaps,
            citizenships = this[Keys.citizenships] ?: defaults.citizenships,
            residencePermits = this[Keys.residencePermits] ?: defaults.residencePermits,
            taxResidence = this[Keys.taxResidence],
            usTaxpayer = this[Keys.usTaxpayer] ?: defaults.usTaxpayer,
            limitAlerts = this[Keys.limitAlerts] ?: defaults.limitAlerts,
            theme = this[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: defaults.theme,
            dynamicColor = this[Keys.dynamicColor] ?: defaults.dynamicColor,
            weekStart = this[Keys.weekStart] ?: defaults.weekStart,
            autoBackup = this[Keys.autoBackup] ?: defaults.autoBackup,
            backupFolder = this[Keys.backupFolder],
            lastBackup = this[Keys.lastBackup],
            lastCheck = this[Keys.lastCheck],
            lastCheckCountry = this[Keys.lastCheckCountry],
            lastCheckSource = this[Keys.lastCheckSource],
        )
    }

    private fun MutablePreferences.write(s: Settings) {
        this[Keys.onboarded] = s.onboarded
        this[Keys.tracking] = s.tracking.name
        this[Keys.interval] = s.intervalHours
        this[Keys.countAll] = s.countAllCountries
        this[Keys.counting] = s.counting.map { (code, policy) -> "$code=${policy.name}" }.toSet()
        this[Keys.calendarView] = s.calendarView.name
        this[Keys.travellerNames] = s.travellerNames
        this[Keys.fillGaps] = s.fillGaps
        this[Keys.citizenships] = s.citizenships
        this[Keys.residencePermits] = s.residencePermits
        setOrRemove(Keys.taxResidence, s.taxResidence)
        this[Keys.usTaxpayer] = s.usTaxpayer
        this[Keys.limitAlerts] = s.limitAlerts
        this[Keys.theme] = s.theme.name
        this[Keys.dynamicColor] = s.dynamicColor
        this[Keys.weekStart] = s.weekStart
        this[Keys.autoBackup] = s.autoBackup
        setOrRemove(Keys.backupFolder, s.backupFolder)
        setOrRemove(Keys.lastBackup, s.lastBackup)
        setOrRemove(Keys.lastCheck, s.lastCheck)
        setOrRemove(Keys.lastCheckCountry, s.lastCheckCountry)
        setOrRemove(Keys.lastCheckSource, s.lastCheckSource)
    }

    private fun <T> MutablePreferences.setOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }
}
