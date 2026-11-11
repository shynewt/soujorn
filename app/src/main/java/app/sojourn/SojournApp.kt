package app.sojourn

import android.app.Application
import android.content.Context
import app.sojourn.data.BackupArchive
import app.sojourn.data.ChangeLog
import app.sojourn.data.EvidenceKeeper
import app.sojourn.data.EvidenceStore
import app.sojourn.data.Repository
import app.sojourn.data.SettingsStore
import app.sojourn.data.flights.AirportDirectory
import app.sojourn.data.db.SojournDatabase
import app.sojourn.data.travel.TravelImporter
import app.sojourn.data.geo.CountryLocator
import app.sojourn.data.geo.GeoShapes
import app.sojourn.data.geo.WorldMapData
import app.sojourn.tracking.AutoBackup
import app.sojourn.tracking.Tracking
import app.sojourn.widget.SojournWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

class SojournApp : Application() {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Background work follows the settings: whatever changes them, the schedule keeps up.
        container.scope.launch {
            container.settings.settings
                .distinctUntilChangedBy { Triple(it.tracking, it.intervalHours, it.onboarded) }
                .collect { if (it.onboarded) Tracking.schedule(this@SojournApp, it) }
        }
        container.scope.launch {
            container.settings.settings
                .distinctUntilChangedBy { it.autoBackup to it.backupFolder }
                .collect { AutoBackup.schedule(this@SojournApp, it) }
        }
    }
}

/** Everything the app shares, built once and on first use. */
class AppContainer(context: Context) {
    val context: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database = SojournDatabase.create(this.context)
    val settings = SettingsStore(this.context)
    val repository = Repository(
        database,
        settings,
        scope,
        changeLog = ChangeLog(java.io.File(this.context.filesDir, "changes.json")),
        evidenceDir = java.io.File(this.context.filesDir, "evidence").apply { mkdirs() },
    ) { SojournWidget.refresh(this.context) }

    /** Evidence files and the yearly evidence pack. */
    val evidence: EvidenceStore by lazy { EvidenceStore(this.context, requireNotNull(repository.evidenceDir)) }
    val travel: TravelImporter by lazy { TravelImporter(this.context) { airports } }
    val keeper: EvidenceKeeper by lazy { EvidenceKeeper(repository, evidence, travel) }
    val backups: BackupArchive by lazy { BackupArchive(this.context, repository, evidence) }

    /** Detailed borders, only loaded when a fix or photo needs a country. */
    val locator: CountryLocator by lazy { CountryLocator(this.context.assets.open("borders.bin").use(GeoShapes::read)) }

    /** Airports with scheduled flights, for the flight editor; load off the main thread. */
    val airports: AirportDirectory by lazy { this.context.assets.open("airports.tsv").use(AirportDirectory::read) }

    /** Simplified borders for drawing; load off the main thread. */
    val worldMap: WorldMapData by lazy { WorldMapData(this.context.assets.open("world.bin").use(GeoShapes::read)) }
}

val Context.container: AppContainer get() = (applicationContext as SojournApp).container
