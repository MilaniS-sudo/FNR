package za.ac.richfield.fnr

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import za.ac.richfield.fnr.data.db.FnrDatabase
import za.ac.richfield.fnr.data.remote.GatewayApi
import za.ac.richfield.fnr.data.remote.GatewayClient
import za.ac.richfield.fnr.data.repository.BackupRepository
import za.ac.richfield.fnr.data.repository.FlashNoteRepository
import za.ac.richfield.fnr.data.repository.RecordingRepository
import za.ac.richfield.fnr.data.repository.ReportRepository
import za.ac.richfield.fnr.data.repository.SubjectRepository
import za.ac.richfield.fnr.data.repository.UserRepository
import za.ac.richfield.fnr.security.AppSettings
import za.ac.richfield.fnr.security.DatabaseKeyProvider
import za.ac.richfield.fnr.security.SessionManager
import za.ac.richfield.fnr.util.Notifier

class FnrApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifier.createChannels(this)
    }
}

/** Creates each repository once and hands it to the ViewModels (simple dependency injection). */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** For work that must finish even if the screen that started it closes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val session = SessionManager(appContext)
    val settings = AppSettings(appContext)

    val db: FnrDatabase by lazy { FnrDatabase.build(appContext, DatabaseKeyProvider(appContext).passphrase()) }

    val users by lazy { UserRepository(db, session, settings) }
    val subjects by lazy { SubjectRepository(db, session) }
    val recordings by lazy { RecordingRepository(appContext, db, session) }
    val notes by lazy { FlashNoteRepository(db, session) }
    val reports by lazy { ReportRepository(db, session) }
    val backups by lazy { BackupRepository(appContext, db, session, settings, recordings) }

    fun gateway(): GatewayApi = GatewayClient.create(settings.gatewayUrl, BuildConfig.APP_TOKEN, settings.deviceId)
}
