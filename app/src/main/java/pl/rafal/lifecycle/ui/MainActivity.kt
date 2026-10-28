package pl.rafal.lifecycle.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.rafal.lifecycle.data.DefaultCategories
import pl.rafal.lifecycle.tracking.ActivityRecognitionHelper
import pl.rafal.lifecycle.tracking.HealthConnectHelper
import pl.rafal.lifecycle.tracking.SleepDetector
import pl.rafal.lifecycle.tracking.TrackingControl
import pl.rafal.lifecycle.tracking.WorkoutImporter
import pl.rafal.lifecycle.util.Prefs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startTab = if (handleImport(intent)) "health" else intent?.getStringExtra("tab")
        setContent {
            LifeTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    AppRoot(startTab)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // kopia / wysyłka podglądu, jeśli pora (w tle, nie blokuje)
        pl.rafal.koofr.Koofr.autoRunAsync()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleImport(intent)
    }

    /** Plik „wymiana-zdarzen” udostępniony z Rytmu Snu, Kreski lub Polar EKG. */
    private fun handleImport(i: Intent?): Boolean {
        val uri: Uri = when (i?.action) {
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            Intent.ACTION_VIEW -> i.data
            else -> null
        } ?: return false
        val app = applicationContext as pl.rafal.lifecycle.LifeApp
        app.appScope.launch {
            val msg = pl.rafal.lifecycle.domain.ExchangeImport.importUri(app, uri)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        return true
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    DAY("Pierścień", Icons.Filled.DonutLarge),
    STATS("Trendy", Icons.Filled.BarChart),
    HEALTH("Zdrowie", Icons.Filled.Favorite),
    PLACES("Miejsca", Icons.Filled.Place),
    SETTINGS("Ustawienia", Icons.Filled.Settings),
}

@Composable
fun AppRoot(startTab: String?) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var onboarded by remember { mutableStateOf(prefs.onboardingDone) }

    if (!onboarded) {
        OnboardingScreen(onDone = {
            prefs.onboardingDone = true
            onboarded = true
            TrackingControl.startIfAllowed(ctx)
        })
        return
    }

    LaunchedEffect(Unit) {
        TrackingControl.startIfAllowed(ctx)
        withContext(Dispatchers.IO) {
            try {
                SleepDetector.run(ctx)
            } catch (_: Exception) {
            }
            try {
                WorkoutImporter.run(ctx)
            } catch (_: Exception) {
            }
            try {
                pl.rafal.lifecycle.tracking.StepsImporter.run(ctx)
            } catch (_: Exception) {
            }
            try {
                pl.rafal.lifecycle.tracking.HealthSync.run(ctx)
            } catch (_: Exception) {
            }
            try {
                pl.rafal.lifecycle.widget.DobaWidget.updateAll(ctx)
            } catch (_: Exception) {
            }
        }
    }

    var tab by rememberSaveable {
        mutableStateOf(
            when (startTab) {
                "places" -> Tab.PLACES
                "health" -> Tab.HEALTH
                "stats" -> Tab.STATS
                else -> Tab.DAY
            }
        )
    }
    var quick by rememberSaveable { mutableStateOf(startTab == "quick") }
    if (quick) QuickSwitchDialog { quick = false }
    var sub by rememberSaveable { mutableStateOf<String?>(startTab?.takeIf { it.startsWith("summary") }) }
    BackHandler(enabled = sub != null) { sub = null }
    BackHandler(enabled = sub == null && tab != Tab.DAY) { tab = Tab.DAY }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (sub == null) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                sub == "categories" -> CategoriesScreen(onBack = { sub = null })
                sub == "permissions" -> PermissionsScreen(onBack = { sub = null })
                sub == "goals" -> GoalsScreen(onBack = { sub = null })
                sub == "koofr" -> pl.rafal.koofr.KoofrScreen(onBack = { sub = null })
                sub?.startsWith("summary") == true -> SummaryScreen(sub) { sub = null }
                tab == Tab.DAY -> DayScreen { tab = Tab.HEALTH }
                tab == Tab.STATS -> StatsScreen(onSummary = { sub = it }, onHealth = { tab = Tab.HEALTH })
                tab == Tab.PLACES -> PlacesScreen()
                tab == Tab.HEALTH -> HealthScreen()
                else -> SettingsScreen(onOpen = { sub = it })
            }
        }
    }
}

// ---------- Uprawnienia ----------

@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader("Uprawnienia", onBack = onBack)
        Text(
            "Doba działa w pełni automatycznie tylko wtedy, gdy ma wszystkie poniższe zgody.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        PermissionList(Modifier.padding(16.dp))
    }
}

@Composable
fun PermissionList(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    val onGranted: () -> Unit = {
        tick++
        TrackingControl.startIfAllowed(ctx)
        ActivityRecognitionHelper.register(ctx)
    }
    val multiLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onGranted() }
    val singleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onGranted() }
    val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { tick++ }

    var hcGranted by remember { mutableStateOf(false) }
    LaunchedEffect(tick) { hcGranted = HealthConnectHelper.hasPermission(ctx) }

    fun open(intent: Intent) {
        try {
            ctx.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            ctx.startActivity(TrackingControl.appSettingsIntent(ctx))
        }
    }

    val loc = remember(tick) { TrackingControl.hasFineLocation(ctx) }
    val bg = remember(tick) { TrackingControl.hasBackground(ctx) }
    val act = remember(tick) { TrackingControl.hasActivity(ctx) }
    val notif = remember(tick) { TrackingControl.hasNotifications(ctx) }
    val battery = remember(tick) { TrackingControl.batteryUnrestricted(ctx) }
    val hcStatus = remember(tick) { HealthConnectHelper.status(ctx) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PermissionRow(
            Icons.Filled.MyLocation, "Lokalizacja",
            "Rozpoznaje miejsca, w których przebywasz.", loc, "Zezwól",
        ) {
            multiLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        PermissionRow(
            Icons.Filled.LocationOn, "Lokalizacja „Zawsze”",
            "Potrzebna, by śledzić czas przy zamkniętej aplikacji. Wybierz „Zezwalaj zawsze”.",
            bg, "Ustaw", enabled = loc,
        ) {
            if (Build.VERSION.SDK_INT >= 29) singleLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        PermissionRow(
            Icons.Filled.DirectionsRun, "Aktywność fizyczna",
            "Liczy kroki i odróżnia chodzenie, bieganie, rower i jazdę samochodem.", act, "Zezwól",
        ) {
            if (Build.VERSION.SDK_INT >= 29) singleLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        PermissionRow(
            Icons.Filled.Notifications, "Powiadomienia",
            "Informuje o nowych miejscach do nazwania.", notif, "Zezwól",
        ) {
            if (Build.VERSION.SDK_INT >= 33) singleLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        PermissionRow(
            Icons.Filled.BatteryChargingFull, "Bez ograniczeń baterii",
            "Bez tego Android usypia śledzenie w tle.", battery, "Wyłącz optymalizację",
        ) {
            open(TrackingControl.batteryIntent(ctx))
        }
        PermissionRow(
            Icons.Filled.Bedtime, "Sen, treningi i kroki z Health Connect",
            when (hcStatus) {
                HealthConnectClient.SDK_AVAILABLE -> "Czyta sen, treningi i kroki z zegarka lub innej aplikacji. Bez tego sen wykrywany jest z nocnej przerwy w używaniu telefonu."
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "Health Connect wymaga aktualizacji."
                else -> "Health Connect nie jest dostępny na tym telefonie — sen będzie wykrywany automatycznie."
            },
            hcGranted,
            if (hcStatus == HealthConnectClient.SDK_AVAILABLE) "Połącz" else "Zainstaluj",
            enabled = hcStatus != HealthConnectClient.SDK_UNAVAILABLE,
        ) {
            if (hcStatus == HealthConnectClient.SDK_AVAILABLE) {
                hcLauncher.launch(HealthConnectHelper.PERMISSIONS + pl.rafal.lifecycle.tracking.HealthSync.PERMISSIONS)
            } else {
                open(
                    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    text: String,
    granted: Boolean,
    action: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (granted) Icons.Filled.CheckCircle else icon, null,
            tint = if (granted) Color(0xFF34C759) else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!granted) {
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onClick, enabled = enabled) { Text(action) }
        }
    }
}

// ---------- Ekran powitalny ----------

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        val demo = DefaultCategories.list.take(7).map { Color(it.color) to 1f }
        Box(Modifier.size(170.dp), contentAlignment = Alignment.Center) {
            ProportionRing(demo, Modifier.fillMaxSize(), stroke = 22.dp)
        }
        Text("Doba", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(
            "Automatycznie zapisuje, gdzie i jak spędzasz czas — dom, praca, sen, podróże i sport. Bez klikania.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
        )
        PermissionList(Modifier.padding(16.dp))
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp),
        ) { Text("Zaczynamy") }
        Text(
            "Uprawnienia możesz zmienić później w Ustawieniach.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp).clickable { },
        )
    }
}
