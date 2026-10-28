package pl.rafal.lifecycle.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.ExtEvent
import pl.rafal.lifecycle.data.HealthSample
import pl.rafal.lifecycle.domain.DayValue
import pl.rafal.lifecycle.domain.ExchangeImport
import pl.rafal.lifecycle.domain.HealthAgg
import pl.rafal.lifecycle.domain.Metric
import pl.rafal.lifecycle.tracking.HealthConnectHelper
import pl.rafal.lifecycle.tracking.HealthSync
import pl.rafal.lifecycle.util.Fmt
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val DAY_MS = 24 * 3_600_000L

@Composable
fun HealthScreen() {
    val ctx = LocalContext.current
    val db = LifeApp.instance.db
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    val from = Fmt.dayStart(today.minusDays(29))
    val to = Fmt.dayStart(today.plusDays(1))
    // Rok wstecz: rzadkie pomiary (waga, skład ciała) też mają swoją kartę.
    val yearFrom = Fmt.dayStart(today.minusDays(365))
    val samples by remember(yearFrom) { db.health().observeAll(yearFrom, to) }.collectAsState(initial = null)
    var heightDialog by remember { mutableStateOf(false) }
    val hiddenExt by pl.rafal.lifecycle.domain.ExtVisibility.hidden.collectAsState()
    val extAll by remember(from) { db.ext().observe(Fmt.dayStart(today.minusDays(31)), to) }.collectAsState(initial = emptyList())
    val ext = remember(extAll, hiddenExt) { pl.rafal.lifecycle.domain.ExtVisibility.filter(extAll, hiddenExt) }
    val sourcesAll by remember { db.ext().observeSources() }.collectAsState(initial = emptyList())
    val sources = remember(sourcesAll, hiddenExt) {
        sourcesAll.filter { pl.rafal.lifecycle.domain.ExtVisibility.key(it.source, it.label) !in hiddenExt }
    }
    var syncing by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Metric?>(null) }
    var extDetail by remember { mutableStateOf<Pair<String, String>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    val hcStatus = remember(tick) { HealthConnectHelper.status(ctx) }
    val granted by produceState<Set<String>>(emptySet(), tick) { value = HealthConnectHelper.grantedSet(ctx) }
    val missing = remember(granted) { HealthSync.PERMISSIONS.filter { it !in granted && it != HealthSync.HISTORY } }
    val noneGranted = missing.size >= HealthSync.PERMISSIONS.size - 1

    fun sync(full: Boolean) {
        scope.launch {
            syncing = true
            val n = withContext(Dispatchers.IO) {
                try {
                    HealthSync.run(ctx, full)
                } catch (e: Exception) {
                    -1
                }
            }
            syncing = false
            if (full) Toast.makeText(ctx, if (n >= 0) "Pobrano $n pomiarów" else "Błąd synchronizacji", Toast.LENGTH_SHORT).show()
        }
    }

    val permLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        tick++
        sync(true)
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { Toast.makeText(ctx, ExchangeImport.importUri(ctx, uri), Toast.LENGTH_LONG).show() }
    }
    LaunchedEffect(Unit) { sync(false) }

    val byMetric = remember(samples) { samples.orEmpty().groupBy { it.metric } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ScreenHeader("Zdrowie") {
                if (syncing) {
                    CircularProgressIndicator(Modifier.size(22.dp).padding(2.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                } else {
                    IconButton(onClick = { sync(true) }) { Icon(Icons.Filled.Sync, "Synchronizuj") }
                }
            }
        }

        if (hcStatus == HealthConnectClient.SDK_AVAILABLE && noneGranted) {
            item {
                Column(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)).padding(16.dp)
                ) {
                    Text("Połącz z Health Connect", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Tętno, ciśnienie, waga i skład ciała (np. z wagi Fitdays), saturacja, kalorie i inne pomiary " +
                            "z zegarka, wagi czy ciśnieniomierza. Zaznacz te, które masz — Doba pokaże tylko parametry z danymi.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        permLauncher.launch(HealthSync.PERMISSIONS + HealthConnectHelper.PERMISSIONS)
                    }) { Text("Nadaj zgody") }
                }
            }
        } else if (hcStatus != HealthConnectClient.SDK_AVAILABLE) {
            item {
                Text(
                    "Health Connect nie jest dostępny na tym telefonie.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }

        val withData = Metric.entries.filter { byMetric[it.key].orEmpty().isNotEmpty() }
        if (samples != null && withData.isEmpty() && !noneGranted) {
            item {
                Text(
                    "Brak pomiarów z ostatniego roku. Jeśli zegarek lub waga zapisują dane do Health Connect, " +
                        "dotknij ikony synchronizacji.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }
        val needHeight = Metric.WEIGHT in withData && Metric.HEIGHT !in withData && Metric.BMI !in withData
        pl.rafal.lifecycle.domain.MetricGroup.entries.forEach { g ->
            val inGroup = withData.filter { it.group == g }
            if (inGroup.isEmpty()) return@forEach
            item(key = "g_" + g.name) { SectionTitle(g.label) }
            items(inGroup, key = { it.key }) { m ->
                MetricCard(m, byMetric[m.key].orEmpty(), today) { detail = m }
                Spacer(Modifier.height(10.dp))
            }
            if (g == pl.rafal.lifecycle.domain.MetricGroup.BODY && needHeight) {
                item(key = "height") {
                    Row(
                        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .clickable { heightDialog = true }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Podaj wzrost, aby Doba liczyła BMI", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text("Ustaw", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
        if (hcStatus == HealthConnectClient.SDK_AVAILABLE && !noneGranted) {
            item(key = "perms") {
                androidx.compose.material3.TextButton(
                    onClick = { permLauncher.launch(HealthSync.PERMISSIONS + HealthConnectHelper.PERMISSIONS) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Text(
                        if (missing.isEmpty()) "Zgody Health Connect: wszystkie nadane"
                        else "Zgody Health Connect · nieudostępnione typy danych: ${missing.size}",
                    )
                }
            }
        }

        item { SectionTitle("Z innych aplikacji") }
        val groups = sources.distinctBy { it.source to it.label }
        items(groups, key = { it.source + "|" + it.label }) { s ->
            ExtCard(s.source, s.label, ext, today, s.n, s.last) { extDetail = s.source to s.label }
            Spacer(Modifier.height(10.dp))
        }
        item {
            Column(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { fileLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.FileOpen, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text("Importuj plik", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    (if (pl.rafal.lifecycle.domain.ExtVisibility.FARTS in hiddenExt) "Liczniki z Kreski albo epizody AF z Polar EKG. "
                    else "Wiatry z Rytmu Snu, liczniki z Kreski albo epizody AF z Polar EKG. ") +
                        "W tamtej aplikacji wybierz eksport i „Udostępnij → Doba” albo zapisz plik i wskaż go tutaj. " +
                        "Ponowny import zastępuje poprzednie dane z tego źródła.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    detail?.let { m -> MetricDetailDialog(m) { detail = null } }
    if (heightDialog) HeightDialog(onDismiss = { heightDialog = false }) { cm ->
        pl.rafal.lifecycle.util.Prefs(ctx).heightCm = cm
        heightDialog = false
        scope.launch { withContext(Dispatchers.IO) { HealthSync.deriveBody(ctx) } }
    }
    extDetail?.let { (src, label) -> ExtDetailDialog(src, label) { extDetail = null } }
}

private fun dayKeys(today: LocalDate, n: Int) = (n - 1 downTo 0).map { today.minusDays(it.toLong()) }

@Composable
private fun MetricCard(m: Metric, samples: List<HealthSample>, today: LocalDate, onClick: () -> Unit) {
    val latest = samples.maxByOrNull { it.time } ?: return
    val daily = remember(samples) { HealthAgg.daily(m, samples) }
    val color = Color(m.color)
    val sum = m.agg == pl.rafal.lifecycle.domain.DayAgg.SUM
    // Gęste dane: 30 ostatnich dni; rzadkie (np. waga raz w tygodniu): ostatnie 12 pomiarów.
    val recent30 = daily.count { it.day > today.minusDays(30) }
    val sparkDays = if (recent30 >= 5 || sum) dayKeys(today, 30) else daily.takeLast(12).map { it.day }
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                Icon(CatIcons.get(m.icon), null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(m.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (m.derived) {
                    Text("wyliczane w Dobie", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                if (sum) (if (daily.lastOrNull()?.day == today) "dziś" else daily.lastOrNull()?.let { Fmt.dayTitle(it.day) } ?: "")
                else Fmt.dateTime(latest.time),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            val main = when (m.agg) {
                pl.rafal.lifecycle.domain.DayAgg.SUM -> daily.lastOrNull()?.let { m.format(it.value) } ?: "0"
                pl.rafal.lifecycle.domain.DayAgg.MINMAX -> daily.lastOrNull()?.let { m.format(it.value) } ?: "—"
                else -> if (m == Metric.BP) "${m.format(latest.value)}/${m.format(latest.v2 ?: 0.0)}" else m.format(latest.value)
            }
            Text(main, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            Text(
                m.unit + if (m == Metric.HR) " śr." else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp),
            )
            Spacer(Modifier.weight(1f))
            if (m == Metric.HR) {
                daily.lastOrNull()?.let { d ->
                    Text(
                        "${m.format(d.min ?: 0.0)}–${m.format(d.max ?: 0.0)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (m.agg == pl.rafal.lifecycle.domain.DayAgg.LAST) {
                // Zmiana od poprzedniego pomiaru (jak w aplikacji wagi)
                val prev = samples.filter { it.time < latest.time }.maxByOrNull { it.time }
                if (prev != null) {
                    val d = latest.value - prev.value
                    val hb = pl.rafal.lifecycle.domain.HealthTrends.higherIsBetter(m)
                    val col = when {
                        hb == null || kotlin.math.abs(d) < Math.pow(10.0, -m.decimals.toDouble()) -> MaterialTheme.colorScheme.onSurfaceVariant
                        (d > 0) == hb -> Color(0xFF34C759)
                        else -> Color(0xFFFF3B30)
                    }
                    Text(
                        (if (d >= 0) "▲ " else "▼ ") + m.format(kotlin.math.abs(d)) + " ${m.unit} od ${Fmt.date(prev.time)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = col,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Sparkline(daily, sparkDays, color, m == Metric.BP, sum)
    }
}

@Composable
private fun HeightDialog(onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wzrost") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { c -> c.isDigit() }.take(3) },
                label = { Text("cm") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                ),
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = (text.toIntOrNull() ?: 0) in 100..230,
                onClick = { onSave(text.toInt()) },
            ) { Text("Zapisz") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

@Composable
private fun Sparkline(daily: List<DayValue>, days: List<LocalDate>, color: Color, second: Boolean, bars: Boolean) {
    val t = entrance(daily.size)
    val map = daily.associateBy { it.day }
    Canvas(Modifier.fillMaxWidth().height(44.dp)) {
        val vals = days.mapNotNull { map[it] }
        if (vals.isEmpty()) return@Canvas
        val lo = if (bars) 0.0 else vals.minOf { minOf(it.value, it.v2 ?: it.value) }
        val hi = vals.maxOf { it.value }
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val slot = size.width / days.size
        fun y(v: Double) = (size.height - 4f) - ((v - lo) / span * (size.height - 8f)).toFloat()
        if (bars) {
            days.forEachIndexed { i, d ->
                val v = map[d]?.value ?: return@forEachIndexed
                val h = (size.height * (v / hi)).toFloat() * stagger(t, i, days.size)
                drawRect(color.copy(alpha = 0.8f), Offset(i * slot + slot * 0.2f, size.height - h), Size(slot * 0.6f, h))
            }
            return@Canvas
        }
        fun line(sel: (DayValue) -> Double?, c: Color) {
            val path = Path()
            var started = false
            days.forEachIndexed { i, d ->
                val v = map[d]?.let(sel) ?: return@forEachIndexed
                val x = i * slot + slot / 2
                if (!started) { path.moveTo(x, y(v)); started = true } else path.lineTo(x, y(v))
            }
            drawPath(path, c, style = Stroke(2.dp.toPx()), alpha = t)
            days.forEachIndexed { i, d ->
                val v = map[d]?.let(sel) ?: return@forEachIndexed
                drawCircle(c, 2.5.dp.toPx() * t, Offset(i * slot + slot / 2, y(v)))
            }
        }
        line({ it.value }, color)
        if (second) line({ it.v2 }, color.copy(alpha = 0.55f))
    }
}

@Composable
private fun ExtCard(
    source: String, label: String, ext: List<ExtEvent>, today: LocalDate, n: Int, last: Long, onClick: () -> Unit,
) {
    val counts = remember(ext, source, label) { ExchangeImport.dailyCounts(ext, source, label) }
    val days = dayKeys(today, 30)
    val color = when (source) {
        ExchangeImport.SRC_NIGHT -> Color(0xFF8E44AD)
        ExchangeImport.SRC_ECG -> Color(0xFFFF2D55)
        else -> Color(0xFFFF9F0A)
    }
    val title = when (source) {
        ExchangeImport.SRC_NIGHT -> "Wiatry nocne"
        ExchangeImport.SRC_ECG -> "Epizody AF"
        else -> "Licznik $label"
    }
    val recent = days.takeLast(7).map { counts[it] ?: 0 }
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                Icon(CatIcons.get(if (source == ExchangeImport.SRC_ECG) "heart" else "air"), null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "${ExchangeImport.sourceLabel(source, label)} · import ${Fmt.dateTime(last)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${counts[today] ?: 0}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            Text(
                if (source == ExchangeImport.SRC_NIGHT) "ostatniej nocy" else "dziś",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "7 dni: śr. ${(recent.sum() / 7.0).let { String.format(Fmt.PL, "%.1f", it) }}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Sparkline(
            days.mapNotNull { d -> counts[d]?.let { DayValue(d, it.toDouble(), null, null, null) } },
            days, color, second = false, bars = true,
        )
    }
}

// ---------------------------------------------------------------------------
// Szczegóły pomiaru
// ---------------------------------------------------------------------------

@Composable
private fun FullDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Zamknij") }
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                content()
            }
        }
    }
}

private val ranges = listOf(1 to "Dzień", 7 to "7 dni", 30 to "30 dni", 90 to "90 dni", 365 to "Rok")

@Composable
private fun RangeChips(days: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ranges.forEach { (d, l) -> FilterChip(days == d, { onChange(d) }, { Text(l) }) }
    }
}

@Composable
private fun MetricDetailDialog(m: Metric, onDismiss: () -> Unit) {
    val db = LifeApp.instance.db
    var days by remember { mutableIntStateOf(if (m.agg == pl.rafal.lifecycle.domain.DayAgg.LAST) 90 else 1) }
    var day by remember { mutableStateOf(LocalDate.now()) }
    var picked by remember { mutableStateOf<LocalDate?>(null) }
    val today = LocalDate.now()
    val from = Fmt.dayStart(today.minusDays(days - 1L))
    val samples by remember(m, from) { db.health().observe(m.key, from, Fmt.dayStart(today.plusDays(1))) }
        .collectAsState(initial = emptyList())
    val daily = remember(samples) { HealthAgg.daily(m, samples) }
    val color = Color(m.color)

    FullDialog(m.label, onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            RangeChips(days) { days = it; picked = null }
            Spacer(Modifier.height(8.dp))
            if (days == 1) {
                MetricDayView(m, day) { day = it }
                Spacer(Modifier.height(24.dp))
                return@Column
            }
            TrendCard {
                if (daily.isEmpty()) {
                    Text("Brak pomiarów w tym okresie.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    MetricChart(m, daily, dayKeys(today, days), color, days) { picked = it }
                    val p = picked
                    if (p != null) {
                        androidx.compose.material3.TextButton(onClick = { day = p; days = 1; picked = null }) {
                            Text("Pokaż szczegóły dnia ${Fmt.dayTitle(p).lowercase()} →")
                        }
                    }
                }
            }
            HourProfileCard(m, samples, days, from, Fmt.dayStart(today.plusDays(1)))
            if (daily.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                val vals = daily.map { it.value }
                Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    val sum = m.agg == pl.rafal.lifecycle.domain.DayAgg.SUM
                    StatBox(if (sum) "średnio / dzień" else "średnia", m.format(vals.average()), Modifier.weight(1f))
                    StatBox("min", m.format(daily.minOf { it.min ?: it.value }), Modifier.weight(1f))
                    StatBox("max", m.format(daily.maxOf { it.max ?: it.value }), Modifier.weight(1f))
                    if (sum) StatBox("razem", m.format(vals.sum()), Modifier.weight(1f))
                    else if (daily.size >= 2) {
                        val diff = daily.last().value - daily.first().value
                        StatBox("zmiana", (if (diff >= 0) "+" else "") + m.format(diff), Modifier.weight(1f))
                    }
                }
                if (m == Metric.BP) {
                    Text(
                        "Linia jasna: rozkurczowe. Średnio ${m.format(vals.average())}/${
                            m.format(daily.mapNotNull { it.v2 }.average())
                        } mmHg.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                SectionTitle("Ostatnie pomiary")
                Column(
                    Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    val rows = if (m.agg == pl.rafal.lifecycle.domain.DayAgg.SUM || m == Metric.HR) {
                        daily.reversed().take(30).map { d ->
                            Fmt.dayTitle(d.day) to (
                                if (m == Metric.HR) "${m.format(d.value)} bpm (${m.format(d.min ?: 0.0)}–${m.format(d.max ?: 0.0)})"
                                else m.format(d.value) + " " + m.unit
                                )
                        }
                    } else {
                        samples.sortedByDescending { it.time }.take(30).map { Fmt.dateTime(it.time) to m.formatSample(it) }
                    }
                    rows.forEach { (a, b) ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)) {
                            Text(a, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(b, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Wykres dzienny: słupki (sumy) albo linia z pasmem min–max; dotyk pokazuje wartość. */
@Composable
private fun MetricChart(
    m: Metric, daily: List<DayValue>, days: List<LocalDate>, color: Color, key: Any,
    onPick: (LocalDate?) -> Unit = {},
) {
    val t = entrance(key)
    val sel = rememberSelection(key)
    val pick by androidx.compose.runtime.rememberUpdatedState(onPick)
    LaunchedEffect(sel.index) { pick(days.getOrNull(sel.index)) }
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val tipStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.surface)
    val tipBg = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val map = daily.associateBy { it.day }
    val bars = m.agg == pl.rafal.lifecycle.domain.DayAgg.SUM
    val dayF = DateTimeFormatter.ofPattern("d.MM")
    val leftPx = with(androidx.compose.ui.platform.LocalDensity.current) { 36.dp.toPx() }
    Canvas(
        Modifier.fillMaxWidth().height(220.dp).chartTouch(sel, days.size) { o, w ->
            if (o.x < leftPx) -1 else ((o.x - leftPx) / ((w - leftPx) / days.size)).toInt()
        }
    ) {
        val left = 36.dp.toPx()
        val bottom = 18.dp.toPx()
        val top = 26.dp.toPx()
        val chartH = size.height - bottom - top
        val w = size.width - left
        val slot = w / days.size
        val present = days.mapNotNull { map[it] }
        if (present.isEmpty()) return@Canvas
        var lo = if (bars) 0.0 else present.minOf { minOf(it.min ?: it.value, it.v2 ?: it.value) }
        var hi = present.maxOf { maxOf(it.max ?: it.value, it.value) }
        if (hi - lo < 1e-6) { hi += 1; lo -= if (bars) 0.0 else 1.0 }
        val pad = if (bars) 0.0 else (hi - lo) * 0.1
        lo -= pad; hi += pad
        fun y(v: Double) = top + chartH - ((v - lo) / (hi - lo) * chartH).toFloat()
        for (k in 0..3) {
            val v = lo + (hi - lo) * k / 3
            val yy = y(v)
            drawLine(grid.copy(alpha = 0.5f), Offset(left, yy), Offset(size.width, yy), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val tl = measurer.measure(m.format(v), style)
            drawText(tl, topLeft = Offset(0f, yy - tl.size.height / 2f))
        }
        val labelEvery = maxOf(1, days.size / 6)
        days.forEachIndexed { i, d ->
            if (i % labelEvery == 0) {
                val tl = measurer.measure(d.format(dayF), style)
                drawText(tl, topLeft = Offset(left + i * slot + slot / 2 - tl.size.width / 2f, size.height - bottom + 3.dp.toPx()))
            }
        }
        fun xOf(i: Int) = left + i * slot + slot / 2
        if (bars) {
            days.forEachIndexed { i, d ->
                val v = map[d]?.value ?: return@forEachIndexed
                val h = (y(lo) - y(v)) * stagger(t, i, days.size)
                drawRect(color.copy(alpha = barAlpha(i, sel, 0.85f)), Offset(left + i * slot + slot * 0.15f, y(lo) - h), Size(slot * 0.7f, h))
            }
        } else {
            // Pasmo min–max
            days.forEachIndexed { i, d ->
                val dv = map[d] ?: return@forEachIndexed
                val mn = dv.min ?: return@forEachIndexed
                val mx = dv.max ?: return@forEachIndexed
                if (mx - mn < 1e-6 || m == Metric.BP) return@forEachIndexed
                val bw = (slot * 0.5f).coerceAtMost(10.dp.toPx())
                drawRoundRect(
                    color.copy(alpha = 0.18f * t), Offset(xOf(i) - bw / 2, y(mx)), Size(bw, y(mn) - y(mx)),
                    androidx.compose.ui.geometry.CornerRadius(bw / 2, bw / 2),
                )
            }
            fun line(sel2: (DayValue) -> Double?, c: Color) {
                val path = Path()
                var started = false
                days.forEachIndexed { i, d ->
                    val v = map[d]?.let(sel2) ?: return@forEachIndexed
                    if (!started) { path.moveTo(xOf(i), y(v)); started = true } else path.lineTo(xOf(i), y(v))
                }
                drawPath(path, c, style = Stroke(2.5.dp.toPx()), alpha = t)
                days.forEachIndexed { i, d ->
                    val v = map[d]?.let(sel2) ?: return@forEachIndexed
                    drawCircle(c, (if (i == sel.last && sel.alpha.value > 0f) 5.dp else 3.dp).toPx() * t, Offset(xOf(i), y(v)))
                }
            }
            line({ it.value }, color)
            if (m == Metric.BP) line({ it.v2 }, color.copy(alpha = 0.5f))
        }
        val i = sel.last
        if (i in days.indices) {
            val dv = map[days[i]]
            val text = days[i].format(dayF) + ": " + when {
                dv == null -> "brak"
                m == Metric.BP -> "${m.format(dv.value)}/${m.format(dv.v2 ?: 0.0)}"
                m == Metric.HR -> "${m.format(dv.value)} (${m.format(dv.min ?: 0.0)}–${m.format(dv.max ?: 0.0)})"
                else -> m.format(dv.value)
            } + " " + m.unit
            tooltip(measurer, text, xOf(i), dv?.let { y(it.max ?: it.value) } ?: (top + chartH), tipStyle, tipBg, sel.alpha.value, grid, top + chartH)
        }
    }
}

@Composable
private fun ExtDetailDialog(source: String, label: String, onDismiss: () -> Unit) {
    val db = LifeApp.instance.db
    var days by remember { mutableIntStateOf(30) }
    var day by remember { mutableStateOf(LocalDate.now()) }
    var picked by remember { mutableStateOf<LocalDate?>(null) }
    val today = LocalDate.now()
    val ext by remember(days) { db.ext().observe(Fmt.dayStart(today.minusDays(maxOf(days, 2) + 1L)), Fmt.dayStart(today.plusDays(1))) }
        .collectAsState(initial = emptyList())
    val counts = remember(ext) { ExchangeImport.dailyCounts(ext, source, label) }
    val keys = dayKeys(today, days)
    val daily = keys.mapNotNull { d -> counts[d]?.let { DayValue(d, it.toDouble(), null, null, null) } }
    val color = when (source) {
        ExchangeImport.SRC_NIGHT -> Color(0xFF8E44AD)
        ExchangeImport.SRC_ECG -> Color(0xFFFF2D55)
        else -> Color(0xFFFF9F0A)
    }
    // Rozkład godzinowy (tylko zdarzenia z godziną)
    val hours = remember(ext) {
        val h = IntArray(24)
        ext.filter { it.source == source && it.label == label && it.type != "window" && it.type != "day_total" }
            .forEach { h[java.time.Instant.ofEpochMilli(it.start).atZone(Fmt.zone).hour]++ }
        h.toList()
    }
    FullDialog(ExchangeImport.sourceLabel(source, label), onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            RangeChips(days) { days = it; picked = null }
            Spacer(Modifier.height(8.dp))
            if (days == 1) {
                ExtDayView(source, label, day, color) { day = it }
                Spacer(Modifier.height(24.dp))
                return@Column
            }
            val metric = Metric.FLOORS // tylko do formatowania liczb całkowitych
            TrendCard {
                if (daily.isEmpty()) Text("Brak danych w tym okresie.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    MetricChart(Metric.FLOORS, daily, keys, color, days) { picked = it }
                    val p = picked
                    if (p != null) {
                        androidx.compose.material3.TextButton(onClick = { day = p; days = 1; picked = null }) {
                            Text("Pokaż ${if (source == ExchangeImport.SRC_NIGHT) "noc" else "dzień"} ${Fmt.dayTitle(p).lowercase()} →")
                        }
                    }
                }
            }
            if (daily.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                val vals = keys.map { counts[it] ?: 0 }
                Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                    StatBox("średnio / dzień", String.format(Fmt.PL, "%.1f", vals.average()), Modifier.weight(1f))
                    StatBox("max", metric.format(vals.max().toDouble()), Modifier.weight(1f))
                    StatBox("razem", metric.format(vals.sum().toDouble()), Modifier.weight(1f))
                    StatBox("dni bez", "${vals.count { it == 0 }}", Modifier.weight(1f))
                }
            }
            if (hours.any { it > 0 }) {
                SectionTitle("O której godzinie")
                TrendCard { HourBars(hours, color) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MetricChartCounts(daily: List<DayValue>, days: List<LocalDate>, color: Color, key: Any) {
    MetricChart(Metric.FLOORS, daily, days, color, key)
}

@Composable
private fun HourBars(hours: List<Int>, color: Color) {
    val t = entrance(hours)
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val bottom = 16.dp.toPx()
        val h = size.height - bottom
        val max = (hours.maxOrNull() ?: 1).coerceAtLeast(1)
        val slot = size.width / 24
        hours.forEachIndexed { i, v ->
            val bh = h * v / max * stagger(t, i, 24)
            drawRect(color.copy(alpha = 0.85f), Offset(i * slot + slot * 0.15f, h - bh), Size(slot * 0.7f, bh))
        }
        for (k in listOf(0, 6, 12, 18)) {
            val tl = measurer.measure(k.toString(), style)
            drawText(tl, topLeft = Offset(k * slot, h + 2.dp.toPx()))
        }
    }
}

// ---------------------------------------------------------------------------
// Karta „Zdrowie dziś” na ekranie dnia
// ---------------------------------------------------------------------------

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun HealthTodayCard(day: LocalDate, onOpen: () -> Unit) {
    val db = LifeApp.instance.db
    val from = Fmt.dayStart(day)
    val to = Fmt.dayStart(day.plusDays(1))
    val samples by remember(from) { db.health().observeAll(from - 6 * DAY_MS, to) }.collectAsState(initial = emptyList())
    val hiddenExt by pl.rafal.lifecycle.domain.ExtVisibility.hidden.collectAsState()
    val extAll by remember(from) { db.ext().observe(from - DAY_MS, to) }.collectAsState(initial = emptyList())
    val ext = remember(extAll, hiddenExt) { pl.rafal.lifecycle.domain.ExtVisibility.filter(extAll, hiddenExt) }
    val chips = remember(samples, ext) {
        val out = mutableListOf<Pair<Metric?, String>>()
        val byM = samples.groupBy { it.metric }
        fun today(m: Metric) = byM[m.key].orEmpty().filter { it.time in from until to }
        today(Metric.HR).takeIf { it.isNotEmpty() }?.let { l ->
            out += Metric.HR to "♥ ${Metric.HR.format(l.map { it.value }.average())} bpm"
        }
        today(Metric.RESTING_HR).lastOrNull()?.let { out += Metric.RESTING_HR to "spocz. ${Metric.RESTING_HR.format(it.value)}" }
        today(Metric.ACTIVE_KCAL).sumOf { it.value }.takeIf { it > 0 }?.let { out += Metric.ACTIVE_KCAL to "${Metric.ACTIVE_KCAL.format(it)} kcal" }
        today(Metric.BP).lastOrNull()?.let { out += Metric.BP to "${Metric.BP.format(it.value)}/${Metric.BP.format(it.v2 ?: 0.0)}" }
        byM[Metric.WEIGHT.key].orEmpty().maxByOrNull { it.time }?.let { out += Metric.WEIGHT to "${Metric.WEIGHT.format(it.value)} kg" }
        today(Metric.SPO2).lastOrNull()?.let { out += Metric.SPO2 to "SpO₂ ${Metric.SPO2.format(it.value)}%" }
        val farts = ExchangeImport.dailyCounts(ext, ExchangeImport.SRC_NIGHT, "")[day]
        if (farts != null) out += null to "💨 $farts w nocy"
        out
    }
    if (chips.isEmpty()) return
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface).clickable(onClick = onOpen).padding(14.dp)
    ) {
        Text("Zdrowie", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            chips.forEach { (m, text) ->
                val c = m?.let { Color(it.color) } ?: Color(0xFF8E44AD)
                Text(
                    text,
                    style = MaterialTheme.typography.labelLarge,
                    color = c,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}
