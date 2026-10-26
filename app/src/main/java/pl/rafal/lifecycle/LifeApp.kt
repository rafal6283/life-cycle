package pl.rafal.lifecycle

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import pl.rafal.lifecycle.data.AppDatabase
import pl.rafal.lifecycle.data.Repository
import pl.rafal.lifecycle.tracking.Notifications
import pl.rafal.lifecycle.tracking.TrackingEngine
import pl.rafal.lifecycle.tracking.WatchdogWorker
import java.io.File

class LifeApp : Application() {
    lateinit var db: AppDatabase
        private set
    lateinit var repo: Repository
        private set
    lateinit var engine: TrackingEngine
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = AppDatabase.build(this)
        repo = Repository(db)
        engine = TrackingEngine(this, db, repo)
        Notifications.createChannels(this)

        Configuration.getInstance().apply {
            load(this@LifeApp, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }

        appScope.launch {
            repo.ensureDefaults()
            repo.categoryByKind(pl.rafal.lifecycle.data.Kind.COMMUTE)
            repo.categoryByKind(pl.rafal.lifecycle.data.Kind.FIELD)
            repo.categoryByKind(pl.rafal.lifecycle.data.Kind.SCHOOL)
            repo.categoryByKind(pl.rafal.lifecycle.data.Kind.STOP)
        }
        pl.rafal.lifecycle.tracking.StepsRecorder.fixedSince(this)
        WatchdogWorker.schedule(this)
    }

    companion object {
        lateinit var instance: LifeApp
            private set
    }
}
