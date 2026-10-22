package pl.rafal.lifecycle.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.R
import pl.rafal.lifecycle.domain.Backup
import pl.rafal.lifecycle.domain.GoalChecker
import pl.rafal.lifecycle.domain.WeeklySummary
import pl.rafal.lifecycle.widget.DobaWidget
import pl.rafal.lifecycle.ui.MainActivity
import pl.rafal.lifecycle.util.Fmt
import pl.rafal.lifecycle.util.Prefs
import java.util.concurrent.TimeUnit

class TrackingService : LifecycleService() {
    private val app get() = application as LifeApp
    private lateinit var fused: FusedLocationProviderClient
    private var highMode: Boolean? = null
    private var started = false
    private var stepSensor: StepSensor? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val locations = result.locations.toList()
            app.appScope.launch {
                for (l in locations) app.engine.onLocation(l.latitude, l.longitude, l.accuracy, l.time)
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val now = System.currentTimeMillis()
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> app.engine.onScreenOff(now)
                Intent.ACTION_SCREEN_ON -> app.appScope.launch { app.engine.onScreenOn(now) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        if (!goForeground(Notifications.tracking(this, "Śledzenie aktywne", null))) {
            stopSelf()
            return
        }
        started = true
        stepSensor = StepSensor(this).also { it.start() }
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        lifecycleScope.launch {
            app.engine.moving.collect { requestUpdates(it) }
        }
        lifecycleScope.launch {
            combine(
                app.db.entries().observeOpenAuto(), app.repo.override, app.repo.categories, app.repo.places,
            ) { auto, ov, cats, places -> Triple(ov ?: auto, cats, places) }
                .collect { (e, cats, places) ->
                    val title: String
                    val text: String?
                    if (e == null) {
                        title = "Śledzenie aktywne"
                        text = "Czekam na pierwsze dane o lokalizacji"
                    } else {
                        val cat = cats.firstOrNull { it.id == e.categoryId }
                        val place = e.placeId?.let { id -> places.firstOrNull { it.id == id } }
                        title = "Teraz: " + (place?.name ?: cat?.name ?: "—")
                        text = "od ${Fmt.time(e.startAt)}" + (if (place != null && cat != null) " · ${cat.name}" else "")
                    }
                    Notifications.updateTracking(this@TrackingService, title, text)
                    DobaWidget.updateAll(this@TrackingService)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (started) {
            ActivityRecognitionHelper.register(this)
            stepSensor?.start()
            highMode = null
            requestUpdates(app.engine.moving.value)
        }
        return START_STICKY
    }

    private fun goForeground(n: Notification): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, Notifications.TRACKING_ID, n, type)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Nie można uruchomić usługi pierwszoplanowej", e)
        false
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates(high: Boolean) {
        if (!TrackingControl.hasLocation(this)) return
        if (highMode == high) return
        highMode = high
        val request = if (high) {
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 30_000L)
                .setMinUpdateIntervalMillis(15_000L)
                .build()
        } else {
            LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 120_000L)
                .setMinUpdateIntervalMillis(60_000L)
                .build()
        }
        try {
            fused.removeLocationUpdates(callback)
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.w(TAG, "Brak uprawnień do lokalizacji", e)
        }
    }

    override fun onDestroy() {
        if (started) {
            stepSensor?.stop()
            fused.removeLocationUpdates(callback)
            try {
                unregisterReceiver(screenReceiver)
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }

    companion object {
        const val TAG = "LifeCycle"
    }
}

object TrackingControl {
    private fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun hasLocation(ctx: Context) =
        granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasFineLocation(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasBackground(ctx: Context) =
        Build.VERSION.SDK_INT < 29 || granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun hasActivity(ctx: Context) =
        Build.VERSION.SDK_INT < 29 || granted(ctx, Manifest.permission.ACTIVITY_RECOGNITION)

    fun hasNotifications(ctx: Context) =
        Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)

    fun batteryUnrestricted(ctx: Context): Boolean {
        val pm = ctx.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    @SuppressLint("BatteryLife")
    fun batteryIntent(ctx: Context) =
        Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))

    fun appSettingsIntent(ctx: Context) =
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

    fun startIfAllowed(ctx: Context) {
        if (!Prefs(ctx).tracking || !hasLocation(ctx)) return
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, TrackingService::class.java))
        } catch (e: Exception) {
            Log.w(TrackingService.TAG, "Nie można uruchomić śledzenia", e)
        }
    }

    fun stop(ctx: Context) {
        ctx.stopService(Intent(ctx, TrackingService::class.java))
        ActivityRecognitionHelper.unregister(ctx)
    }
}

object ActivityRecognitionHelper {
    private fun pendingIntent(ctx: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, 7, Intent(ctx, ActivityTransitionReceiver::class.java), flags)
    }

    @SuppressLint("MissingPermission")
    fun register(ctx: Context) {
        if (!TrackingControl.hasActivity(ctx)) return
        val types = listOf(
            DetectedActivity.IN_VEHICLE, DetectedActivity.ON_BICYCLE, DetectedActivity.RUNNING,
            DetectedActivity.WALKING, DetectedActivity.STILL,
        )
        val transitions = types.map {
            ActivityTransition.Builder()
                .setActivityType(it)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build()
        }
        try {
            ActivityRecognition.getClient(ctx)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent(ctx))
        } catch (e: Exception) {
            Log.w(TrackingService.TAG, "Rozpoznawanie aktywności niedostępne", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun unregister(ctx: Context) {
        if (!TrackingControl.hasActivity(ctx)) return
        try {
            ActivityRecognition.getClient(ctx).removeActivityTransitionUpdates(pendingIntent(ctx))
        } catch (_: Exception) {
        }
    }
}

class ActivityTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val app = context.applicationContext as LifeApp
        val pending = goAsync()
        app.appScope.launch {
            try {
                for (ev in result.transitionEvents) {
                    if (ev.transitionType != ActivityTransition.ACTIVITY_TRANSITION_ENTER) continue
                    val ageMs = (SystemClock.elapsedRealtimeNanos() - ev.elapsedRealTimeNanos) / 1_000_000
                    app.engine.onActivity(ev.activityType, System.currentTimeMillis() - ageMs)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TrackingControl.startIfAllowed(context)
    }
}

class WatchdogWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        TrackingControl.startIfAllowed(applicationContext)
        val ctx = applicationContext
        suspend fun safe(name: String, block: suspend () -> Unit) = try {
            block()
        } catch (e: Exception) {
            Log.w(TrackingService.TAG, "$name nie powiodło się", e)
        }
        safe("Wykrywanie snu") { SleepDetector.run(ctx) }
        safe("Import treningów") { WorkoutImporter.run(ctx) }
        safe("Import kroków") { StepsImporter.run(ctx) }
        safe("Sprawdzanie celów") { GoalChecker.run(ctx) }
        safe("Podsumowanie tygodnia") { WeeklySummary.maybeNotify(ctx) }
        safe("Kopia zapasowa") { Backup.autoBackup(ctx) }
        safe("Widżet") { DobaWidget.updateAll(ctx) }
        val db = (applicationContext as LifeApp).db
        val now = System.currentTimeMillis()
        db.locations().prune(now - 90L * 24 * 3_600_000)
        db.screenOff().prune(now - 30L * 24 * 3_600_000)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork("watchdog", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

object Notifications {
    private const val CH_TRACKING = "tracking"
    private const val CH_PLACES = "places"
    const val CH_GOALS = "goals"
    const val CH_SUMMARY = "summary"
    const val TRACKING_ID = 1
    private const val PLACE_ID = 2

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_TRACKING, "Śledzenie w tle", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_PLACES, "Nowe miejsca", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_GOALS, "Cele", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SUMMARY, "Podsumowanie tygodnia", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    @SuppressLint("MissingPermission")
    fun simple(ctx: Context, id: Int, channel: String, title: String, text: String, tab: String?) {
        if (!TrackingControl.hasNotifications(ctx)) return
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, tab))
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }

    fun openApp(ctx: Context, tab: String?): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java)
            .putExtra("tab", tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            ctx, (tab ?: "main").hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun tracking(ctx: Context, title: String, text: String?): Notification =
        NotificationCompat.Builder(ctx, CH_TRACKING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(openApp(ctx, null))
            .addAction(0, "Teraz robię…", openApp(ctx, "quick"))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    @SuppressLint("MissingPermission")
    fun updateTracking(ctx: Context, title: String, text: String?) {
        if (!TrackingControl.hasNotifications(ctx)) return
        NotificationManagerCompat.from(ctx).notify(TRACKING_ID, tracking(ctx, title, text))
    }

    @SuppressLint("MissingPermission")
    fun notifyNewPlace(ctx: Context) {
        if (!TrackingControl.hasNotifications(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_PLACES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Nowe miejsce")
            .setContentText("Jesteś w miejscu, którego jeszcze nie znam. Nazwij je, by lepiej liczyć czas.")
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, "places"))
            .build()
        NotificationManagerCompat.from(ctx).notify(PLACE_ID, n)
    }
}
