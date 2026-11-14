package pl.rafal.lifecycle.ui

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Entry
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.data.Place
import pl.rafal.lifecycle.domain.CalendarHints
import pl.rafal.lifecycle.domain.FieldWork
import pl.rafal.lifecycle.domain.Poi
import pl.rafal.lifecycle.domain.PoiHints
import pl.rafal.lifecycle.domain.Review
import pl.rafal.lifecycle.domain.ReviewItem
import pl.rafal.lifecycle.domain.Search
import pl.rafal.lifecycle.domain.Timeline
import pl.rafal.lifecycle.domain.VisitStats
import pl.rafal.lifecycle.util.Fmt
import pl.rafal.lifecycle.util.Prefs
import java.time.LocalDate

// ------------------------------------------------------------------------------------- przegląd dnia

/** Baner na ekranie dnia: ile rzeczy czeka na uzupełnienie. */
@Composable
fun ReviewBanner(day: LocalDate, entries: List<Entry>, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val n by produceState(0, day, entries) { value = Review.forDay(ctx, day).size }
    if (n == 0) return
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0A84FF).copy(alpha = 0.12f)).clickable(onClick = onOpen).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🧩", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Przejrzyj dzień", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "$n ${if (n == 1) "rzecz" else if (n in 2..4) "rzeczy" else "rzeczy"} do uzupełnienia — zajmie chwilę",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        FilledTonalButton(onClick = onOpen) { Text("Start") }
    }
}

@Composable
fun ReviewScreen(day: LocalDate, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = LifeApp.instance
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var skipped by remember { mutableStateOf(setOf<Long>()) }
    var done by remember { mutableIntStateOf(0) }
    val items by produceState<List<ReviewItem>?>(null, day, tick) { value = Review.forDay(ctx, day) }
    val categories by app.repo.categories.collectAsState(initial = emptyList())
    val list = items?.filter { it.start !in skipped }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Przegląd · ${Fmt.dayTitle(day)}", onBack = onBack)
        if (list == null) {
            Text("Szukam luk…", Modifier.padding(24.dp))
            return@Column
        }
        val total = done + list.size
        if (total > 0) {
            LinearProgressIndicator(
                progress = { done.toFloat() / total },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
        }
        val cur = list.firstOrNull()
        if (cur == null) {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("✅", style = MaterialTheme.typography.displayMedium)
                Spacer(Modifier.height(12.dp))
                Text("Dzień kompletny", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (done > 0) "Uzupełniono: $done" else "Nie ma luk ani nieznanych miejsc.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onBack) { Text("Gotowe") }
            }
            return@Column
        }
        AnimatedContent(cur, label = "review") { item ->
            ReviewCard(
                item, categories,
                onSkip = { skipped = skipped + item.start },
                onSave = { catId, note, rememberName ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            when (item) {
                                is ReviewItem.Gap -> Review.fillGap(ctx, item, catId, note)
                                is ReviewItem.Unknown -> {
                                    val e = item.entry
                                    app.repo.saveEntry(e.copy(categoryId = catId, note = note ?: e.note))
                                    val lat = e.lat
                                    val lng = e.lng
                                    if (rememberName != null && lat != null && lng != null) {
                                        app.repo.savePlace(Place(name = rememberName, lat = lat, lng = lng, categoryId = catId))
                                    }
                                }
                            }
                        }
                        done++
                        tick++
                    }
                },
            )
        }
    }
}

@Composable
private fun ReviewCard(
    item: ReviewItem,
    categories: List<pl.rafal.lifecycle.data.Category>,
    onSkip: () -> Unit,
    onSave: (Long, String?, String?) -> Unit,
) {
    val ctx = LocalContext.current
    val app = LifeApp.instance
    var catId by remember(item) { mutableStateOf<Long?>(null) }
    var note by remember(item) { mutableStateOf("") }
    var keepPlace by remember(item) { mutableStateOf(true) }
    var placeName by remember(item) { mutableStateOf("") }
    val unknown = item as? ReviewItem.Unknown
    val lat = unknown?.entry?.lat
    val lng = unknown?.entry?.lng
    // Co było przed i po luce — łatwiej sobie przypomnieć.
    val context by produceState<Pair<String?, String?>>(null to null, item) {
        value = withContext(Dispatchers.Default) {
            val entries = app.repo.getRange(item.start - 6 * 3_600_000L, item.end + 6 * 3_600_000L)
            val cats = app.db.categories().getAll().associateBy { it.id }
            val places = app.db.places().getAll().associateBy { it.id }
            val segs = Timeline.resolve(entries, item.start - 6 * 3_600_000L, item.end + 6 * 3_600_000L, System.currentTimeMillis())
            fun label(e: Entry?) = e?.let { x -> x.placeId?.let { places[it]?.name } ?: cats[x.categoryId]?.name }
            label(segs.lastOrNull { it.end <= item.start + 60_000L && it.entry.id != unknown?.entry?.id }?.entry) to
                label(segs.firstOrNull { it.start >= item.end - 60_000L && it.entry.id != unknown?.entry?.id }?.entry)
        }
    }
    val address by produceState<String?>(null, lat, lng) {
        value = if (lat != null && lng != null) FieldWork.address(ctx, lat, lng) else null
    }
    val pois by produceState<List<Poi>?>(null, lat, lng) {
        value = if (lat != null && lng != null) PoiHints.near(ctx, lat, lng)?.filter { it.inside || it.distanceM <= 80 } ?: emptyList() else emptyList()
    }
    val hint by produceState<pl.rafal.lifecycle.domain.CalendarHint?>(null, item) {
        value = CalendarHints.events(ctx, item.start, item.end).firstOrNull()
    }
    LaunchedEffect(address, hint, pois) {
        if (placeName.isBlank()) placeName = hint?.title ?: pois?.firstOrNull()?.label ?: address?.substringBefore(',') ?: ""
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            if (unknown != null) "Gdzie byłeś?" else "Brak danych — co robiłeś?",
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
        )
        Text(
            "${Fmt.time(item.start)}–${Fmt.time(item.end)} · ${Fmt.duration(item.end - item.start)}",
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        )
        val (before, after) = context
        if (before != null || after != null) {
            Text(
                listOfNotNull(before?.let { "przed: $it" }, after?.let { "po: $it" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        hint?.let {
            Text("📅 W kalendarzu: ${it.title}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (lat != null && lng != null) {
            Spacer(Modifier.height(10.dp))
            OsmMap(
                key = unknown.entry.id,
                modifier = Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(16.dp)),
            ) { m ->
                m.overlays.add(Marker(m).apply {
                    position = GeoPoint(lat, lng)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setInfoWindow(null)
                })
                m.controller.setZoom(17.0)
                m.controller.setCenter(GeoPoint(lat, lng))
            }
            address?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(6.dp))
            PoiChips(pois) { p ->
                placeName = p.label
                PoiHints.suggestCategory(p, categories)?.let { catId = it.id }
            }
        }
        Spacer(Modifier.height(12.dp))
        CategoryPicker(categories.filter { it.kind != Kind.UNKNOWN }, catId) { catId = it }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(note, { note = it }, label = { Text("Notatka (opcjonalnie)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (unknown != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(keepPlace, { keepPlace = it })
                Text("Zapamiętaj to miejsce")
            }
            if (keepPlace) {
                OutlinedTextField(placeName, { placeName = it }, label = { Text("Nazwa miejsca") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onSkip) { Text("Pomiń") }
            Spacer(Modifier.weight(1f))
            Button(
                enabled = catId != null && (unknown == null || !keepPlace || placeName.isNotBlank()),
                onClick = {
                    onSave(catId!!, note.trim().ifBlank { null }, if (unknown != null && keepPlace) placeName.trim() else null)
                },
            ) { Text("Zapisz i dalej") }
        }
    }
}

// ------------------------------------------------------------------------------------- wyszukiwarka

@Composable
fun SearchScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var q by rememberSaveable { mutableStateOf("") }
    val res by produceState<List<VisitStats>?>(null, q) {
        if (q.isNotEmpty()) kotlinx.coroutines.delay(250)
        value = Search.run(ctx, q)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { ScreenHeader("Kiedy ostatnio…?", onBack = onBack) }
        item {
            OutlinedTextField(
                q, { q = it }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text("np. fryzjer, cmentarz, mama, siłownia") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        val r = res
        when {
            r == null -> item { Text("Szukam…", Modifier.padding(20.dp)) }
            q.isBlank() && r.isEmpty() -> item {
                Text(
                    "Wpisz nazwę miejsca, kategorii albo słowo z notatki. Tu pojawią się też miejsca, w których " +
                        "bywasz regularnie, a dawno Cię nie było.",
                    Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            r.isEmpty() -> item { Text("Nic nie znaleziono.", Modifier.padding(20.dp)) }
            else -> {
                if (q.isBlank()) item { SectionTitle("Dawno Cię tam nie było") }
                items(r, key = { "${it.placeId}-${it.categoryId}-${it.title}" }) { s -> VisitCard(s) }
            }
        }
    }
}

@Composable
private fun VisitCard(s: VisitStats) {
    TrendCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (s.subtitle.isNotBlank()) Text(s.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (s.overdue) {
                Text("⏰ dawno", color = Color(0xFFFF9F0A), style = MaterialTheme.typography.labelLarge)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(Search.gapText(s), style = MaterialTheme.typography.bodyLarge)
        Text(
            "${Fmt.dateTime(s.last)} · łącznie ${s.visits} ${if (s.visits == 1) "raz" else "razy"} w ${s.days} dni · ${Fmt.durationShort(s.totalMs)}" +
                " · od ${Fmt.dayTitle(Fmt.toLocalDate(s.first))}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (s.recent.size > 1) {
            Text(
                "Ostatnie: " + s.recent.drop(1).take(5).joinToString(", ") { Fmt.dayTitle(Fmt.toLocalDate(it)) },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ------------------------------------------------------------------------------------- kontrola śledzenia

private data class Check(val ok: Boolean, val title: String, val detail: String, val fix: (() -> Unit)?)

@Composable
fun TrackingHealthScreen(onBack: () -> Unit, onPermissions: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val checks = remember(tick) { checks(ctx, onPermissions) }
    val gaps by produceState<List<Triple<LocalDate, Long, Int>>?>(null, tick) {
        value = withContext(Dispatchers.Default) {
            val app = LifeApp.instance
            val first = app.db.entries().firstStart() ?: return@withContext emptyList()
            val now = System.currentTimeMillis()
            (0 until 7).map { LocalDate.now().minusDays(it.toLong()) }.mapNotNull { d ->
                val from = maxOf(Fmt.dayStart(d), first)
                val to = minOf(Fmt.dayStart(d.plusDays(1)), now)
                if (to <= from) return@mapNotNull null
                val segs = Timeline.resolve(app.repo.getRange(from, to), from, to, now)
                val g = Review.gaps(segs, from, to, 60 * 60_000L)
                Triple(d, g.sumOf { it.second - it.first }, g.size)
            }
        }
    }
    val bad = checks.count { !it.ok }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { ScreenHeader("Kontrola śledzenia", onBack = onBack) }
        item {
            TrendCard {
                Text(
                    if (bad == 0) "✅ Wszystko gotowe do śledzenia" else "⚠️ $bad ${if (bad == 1) "rzecz wymaga" else "rzeczy wymagają"} uwagi",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                )
                Text(
                    "Luki w osi czasu prawie zawsze biorą się z usypiania aplikacji przez system albo z uprawnień.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(checks) { c -> CheckRow(c) }
        if (Build.MANUFACTURER.equals("samsung", true)) {
            item { SectionTitle("Samsung — jak nie dać się uśpić") }
            item {
                TrendCard {
                    listOf(
                        "Ustawienia → Aplikacje → Doba → Bateria → zaznacz „Bez ograniczeń”.",
                        "Ustawienia → Bateria → Limity użycia w tle → „Aplikacje nigdy nieusypiane” → dodaj Dobę.",
                        "Tamże wyłącz „Usypiaj nieużywane aplikacje”.",
                        "Ustawienia → Aplikacje → Doba → Uprawnienia → Lokalizacja → „Zezwalaj zawsze”, z dokładną lokalizacją.",
                        "Nie zamykaj Doby przyciskiem „Zamknij wszystkie” w ostatnich aplikacjach (albo zablokuj ją tam kłódką).",
                    ).forEachIndexed { i, s ->
                        Text("${i + 1}. $s", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { openAppDetails(ctx) }) { Text("Ustawienia Doby") }
                        FilledTonalButton(onClick = { openSamsungBattery(ctx) }) { Text("Bateria") }
                    }
                }
            }
        }
        item { SectionTitle("Luki w ostatnim tygodniu (ponad 1 h)") }
        val g = gaps
        if (g == null) item { Text("Liczę…", Modifier.padding(20.dp)) }
        else items(g) { (d, ms, n) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text(Fmt.dayTitle(d), Modifier.weight(1f))
                Text(
                    if (n == 0) "bez luk" else "$n × · ${Fmt.durationShort(ms)}",
                    color = if (ms > 3 * 3_600_000L) Color(0xFFFF453A) else if (n > 0) Color(0xFFFF9F0A) else Color(0xFF34C759),
                )
            }
        }
    }
}

@Composable
private fun CheckRow(c: Check) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
            Icon(
                if (c.ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, null,
                tint = if (c.ok) Color(0xFF34C759) else Color(0xFFFF9F0A),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title, style = MaterialTheme.typography.bodyLarge)
            Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!c.ok && c.fix != null) TextButton(onClick = c.fix) { Text("Napraw") }
    }
}

private fun ago(ms: Long): String {
    val m = (System.currentTimeMillis() - ms) / 60_000
    return when {
        ms <= 0 -> "nigdy"
        m < 1 -> "przed chwilą"
        m < 60 -> "$m min temu"
        m < 48 * 60 -> "${m / 60} h temu"
        else -> "${m / 1440} dni temu"
    }
}

fun openAppDetails(ctx: Context) {
    ctx.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private fun openSamsungBattery(ctx: Context) {
    val tries = listOf(
        Intent().setClassName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        Intent("com.samsung.android.sm.ACTION_BATTERY"),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )
    for (i in tries) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
        } catch (_: Exception) {
        }
    }
    openAppDetails(ctx)
}

@android.annotation.SuppressLint("BatteryLife")
private fun checks(ctx: Context, onPermissions: () -> Unit): List<Check> {
    fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    val prefs = Prefs(ctx)
    val engine = ctx.getSharedPreferences("engine", Context.MODE_PRIVATE)
    val lastFix = engine.getLong("lastFix", 0L)
    val watchdog = engine.getLong("watchdogAt", 0L)
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val out = mutableListOf<Check>()
    out += Check(prefs.tracking, "Śledzenie włączone", if (prefs.tracking) "Doba zapisuje miejsca i ruch" else "Włącz w Ustawieniach", null)
    out += Check(has(Manifest.permission.ACCESS_FINE_LOCATION), "Dokładna lokalizacja", "Potrzebna do rozpoznawania miejsc", onPermissions)
    val bg = Build.VERSION.SDK_INT < 29 || has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    out += Check(bg, "Lokalizacja „zawsze”", if (bg) "Działa także przy zgaszonym ekranie" else "Bez tego Doba widzi tylko czas, gdy jest otwarta", onPermissions)
    val ar = Build.VERSION.SDK_INT < 29 || has(Manifest.permission.ACTIVITY_RECOGNITION)
    out += Check(ar, "Rozpoznawanie aktywności", "Chodzenie, jazda, rower", onPermissions)
    val notif = Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS)
    out += Check(notif, "Powiadomienia", "Stałe powiadomienie chroni usługę przed zamknięciem", onPermissions)
    val battery = pm.isIgnoringBatteryOptimizations(ctx.packageName)
    out += Check(
        battery, "Bez optymalizacji baterii",
        if (battery) "System nie usypia Doby" else "System może usypiać Dobę — to najczęstsza przyczyna luk",
    ) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            openAppDetails(ctx)
        }
    }
    if (Build.VERSION.SDK_INT >= 28) {
        val restricted = am.isBackgroundRestricted
        out += Check(!restricted, "Praca w tle dozwolona", if (restricted) "Praca w tle jest ograniczona — zdejmij ograniczenie" else "Bez ograniczeń", { openAppDetails(ctx) })
    }
    val fixOk = lastFix > System.currentTimeMillis() - 3 * 3_600_000L
    out += Check(fixOk, "Ostatnia lokalizacja", ago(lastFix), null)
    val wdOk = watchdog > System.currentTimeMillis() - 2 * 3_600_000L
    out += Check(wdOk, "Zadania w tle (co 15 min)", "ostatnio ${ago(watchdog)}", null)
    return out
}
