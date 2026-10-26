package pl.rafal.lifecycle.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.ScreenDay
import pl.rafal.lifecycle.domain.Period
import pl.rafal.lifecycle.domain.PeriodType
import pl.rafal.lifecycle.tracking.ScreenTime
import pl.rafal.lifecycle.util.Fmt
import pl.rafal.lifecycle.util.Prefs
import java.time.LocalDate
import java.time.format.DateTimeFormatter

val ScreenColor = Color(0xFF5856D6)

@Composable
fun rememberAppIcon(pkg: String): ImageBitmap? {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) {
            try {
                ctx.packageManager.getApplicationIcon(pkg).toBitmap(72, 72).asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }
    return bmp
}

@Composable
fun AppIcon(pkg: String, size: androidx.compose.ui.unit.Dp) {
    val icon = rememberAppIcon(pkg)
    if (icon != null) {
        Image(icon, null, Modifier.size(size).clip(RoundedCornerShape(size / 4)))
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(ScreenColor.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PhoneAndroid, null, tint = ScreenColor, modifier = Modifier.size(size * 0.6f))
        }
    }
}

/** Karta na ekranie dnia. Bez zgody — zaproszenie (do odrzucenia), wyłączona w ustawieniach — nic. */
@Composable
fun ScreenTimeCard(day: LocalDate, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val enabled = remember(tick) { prefs.screenTime }
    val allowed = remember(tick) { ScreenTime.hasPermission(ctx) }
    val scope = rememberCoroutineScope()
    val data by remember(day) { LifeApp.instance.db.screen().observe(day.toEpochDay(), day.toEpochDay() + 1) }
        .collectAsState(initial = emptyList())
    androidx.compose.runtime.LaunchedEffect(tick, allowed) { if (allowed) ScreenTime.sync(ctx) }
    if (!enabled) return
    if (!allowed) {
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(ScreenColor.copy(alpha = 0.12f)).padding(14.dp)
        ) {
            Text("Czas przed ekranem", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Ile czasu spędzasz w telefonie, ile razy go odblokowujesz i w jakich aplikacjach — " +
                    "te same dane co w Cyfrowej równowadze. Wymaga zgody „Dostęp do danych o użyciu”.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                TextButton(onClick = { ctx.startActivity(ScreenTime.settingsIntent(ctx)) }) { Text("Włącz") }
                TextButton(onClick = { prefs.screenTime = false; tick++ }) { Text("Nie, dziękuję") }
            }
        }
        return
    }
    val d = data.firstOrNull() ?: return
    val apps = d.appList()
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface).clickable(onClick = onOpen).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(ScreenColor), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PhoneAndroid, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Czas przed ekranem", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "${d.unlocks} odblokowań · ${d.notifs} powiadomień" + (d.firstUse?.let { " · od ${Fmt.time(it)}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(Fmt.durationShort(d.screenMs), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        HourBars24(d.hourList(), ScreenColor, 40.dp, day)
        Spacer(Modifier.height(6.dp))
        apps.take(3).forEach { (pkg, ms) -> AppRow(pkg, ms, d.screenMs, small = true) }
    }
}

@Composable
private fun AppRow(pkg: String, ms: Long, total: Long, small: Boolean) {
    val ctx = LocalContext.current
    val label = remember(pkg) { ScreenTime.appLabel(ctx, pkg) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = if (small) 3.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(pkg, if (small) 22.dp else 30.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!small) {
                Spacer(Modifier.height(3.dp))
                ShareBar(if (total > 0) ms.toFloat() / total else 0f, ScreenColor)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(Fmt.durationShort(ms), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HourBars24(hours: List<Long>, color: Color, height: androidx.compose.ui.unit.Dp, key: Any) {
    val t = entrance(key)
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(height + 14.dp)) {
        val h = size.height - 14.dp.toPx()
        val slot = size.width / 24
        val max = (hours.maxOrNull() ?: 0L).coerceAtLeast(60_000L)
        hours.forEachIndexed { i, v ->
            drawRect(track, Offset(i * slot + slot * 0.15f, h - 2f), Size(slot * 0.7f, 2f))
            if (v > 0) {
                val bh = h * v / max * stagger(t, i, 24)
                drawRect(color, Offset(i * slot + slot * 0.15f, h - bh), Size(slot * 0.7f, bh))
            }
        }
        for (k in listOf(0, 6, 12, 18)) {
            val tl = measurer.measure("$k", style)
            drawText(tl, topLeft = Offset(k * slot, h + 1.dp.toPx()))
        }
    }
}

// ---------------------------------------------------------------------------
// Szczegóły
// ---------------------------------------------------------------------------

@Composable
fun ScreenTimeDialog(initial: LocalDate, onDismiss: () -> Unit) {
    var day by remember { mutableStateOf(initial) }
    val db = LifeApp.instance.db
    val today = LocalDate.now()
    val month by remember { db.screen().observe(today.minusDays(29).toEpochDay(), today.toEpochDay() + 1) }
        .collectAsState(initial = emptyList())
    val d = month.firstOrNull { it.day == day.toEpochDay() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Zamknij") }
                    Text("Czas przed ekranem", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                DayNav(day) { day = it }
                if (d == null) {
                    Text(
                        "Brak danych z tego dnia. Android przechowuje szczegóły użycia tylko kilka dni wstecz — " +
                            "Doba zapisuje je od chwili włączenia.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp),
                    )
                } else {
                    Text(
                        Fmt.durationShort(d.screenMs),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
                        ScreenStat("odblokowania", "${d.unlocks}", Modifier.weight(1f))
                        ScreenStat("włączenia ekranu", "${d.screenOns}", Modifier.weight(1f))
                        ScreenStat("powiadomienia", "${d.notifs}", Modifier.weight(1f))
                        ScreenStat("pierwsze użycie", d.firstUse?.let { Fmt.time(it) } ?: "—", Modifier.weight(1f))
                    }
                    SectionTitle("Godzina po godzinie")
                    TrendCard { HourBars24(d.hourList(), ScreenColor, 120.dp, d.day) }
                    val apps = d.appList()
                    if (apps.isNotEmpty()) {
                        SectionTitle("Aplikacje")
                        Column(
                            Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.surface).padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            apps.take(20).forEach { (pkg, ms) -> AppRow(pkg, ms, d.screenMs, small = false) }
                        }
                    }
                }
                if (month.size >= 2) {
                    SectionTitle("Ostatnie 30 dni")
                    TrendCard {
                        DailyScreenBars(month, today.minusDays(29), 30, day) { day = it }
                        val avg = month.sumOf { it.screenMs } / month.size
                        Text(
                            "Średnio ${Fmt.durationShort(avg)} dziennie · ${month.sumOf { it.unlocks } / month.size} odblokowań",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ScreenStat(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Słupki dzienne czasu przed ekranem z linią średniej; dotknięcie wybiera dzień. */
@Composable
fun DailyScreenBars(days: List<ScreenDay>, start: LocalDate, n: Int, selected: LocalDate?, onPick: (LocalDate) -> Unit = {}) {
    val t = entrance(days.size to start)
    val sel = rememberSelection(start)
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val tipStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.surface)
    val tipBg = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val map = days.associateBy { it.day }
    val keys = (0 until n).map { start.plusDays(it.toLong()) }
    val f = DateTimeFormatter.ofPattern("d.MM")
    androidx.compose.runtime.LaunchedEffect(sel.index) { keys.getOrNull(sel.index)?.let(onPick) }
    Canvas(Modifier.fillMaxWidth().height(150.dp).chartTouch(sel, n) { o, w -> (o.x / (w / n)).toInt() }) {
        val bottom = 16.dp.toPx()
        val top = 22.dp.toPx()
        val h = size.height - bottom - top
        val slot = size.width / n
        val max = (days.maxOfOrNull { it.screenMs } ?: 0L).coerceAtLeast(3_600_000L)
        val avg = if (days.isEmpty()) 0L else days.sumOf { it.screenMs } / days.size
        keys.forEachIndexed { i, d ->
            val v = map[d.toEpochDay()]?.screenMs ?: return@forEachIndexed
            val bh = h * v / max * stagger(t, i, n)
            val base = if (d == selected) 1f else 0.75f
            drawRect(ScreenColor.copy(alpha = barAlpha(i, sel, base)), Offset(i * slot + slot * 0.15f, top + h - bh), Size(slot * 0.7f, bh))
        }
        val ay = top + h - h * avg / max
        drawLine(grid, Offset(0f, ay), Offset(size.width, ay), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
        val every = maxOf(1, n / 6)
        keys.forEachIndexed { i, d ->
            if (i % every == 0) {
                val tl = measurer.measure(d.format(f), style)
                drawText(tl, topLeft = Offset(i * slot + slot / 2 - tl.size.width / 2f, top + h + 2.dp.toPx()))
            }
        }
        val i = sel.last
        if (i in keys.indices) {
            val v = map[keys[i].toEpochDay()]
            val text = keys[i].format(f) + ": " + (v?.let { "${Fmt.durationShort(it.screenMs)} · ${it.unlocks} odbl." } ?: "brak")
            tooltip(measurer, text, i * slot + slot / 2, top + h - h * (v?.screenMs ?: 0L) / max, tipStyle, tipBg, sel.alpha.value, grid, top + h)
        }
    }
}

// ---------------------------------------------------------------------------
// Trendy
// ---------------------------------------------------------------------------

@Composable
fun ScreenTrendSection(period: Period) {
    val db = LifeApp.instance.db
    val prev = period.prev()
    val all by remember(period) { db.screen().observe(prev.start.toEpochDay(), period.end.toEpochDay()) }
        .collectAsState(initial = emptyList())
    val cur = all.filter { it.day >= period.start.toEpochDay() }
    if (cur.isEmpty()) return
    val pr = all.filter { it.day < period.start.toEpochDay() }
    val avg = cur.sumOf { it.screenMs } / cur.size
    val prevAvg = if (pr.isEmpty()) null else pr.sumOf { it.screenMs } / pr.size
    val apps = cur.flatMap { it.appList() }.groupBy { it.first }.mapValues { (_, l) -> l.sumOf { it.second } }
        .toList().sortedByDescending { it.second }.take(5)
    SectionTitle("Czas przed ekranem")
    TrendCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(Fmt.durationShort(avg), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("średnio dziennie · ${cur.size} dni", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                if (prevAvg != null && prevAvg > 0) {
                    val d = avg - prevAvg
                    Text(
                        (if (d >= 0) "▲ " else "▼ ") + Fmt.durationShort(kotlin.math.abs(d)),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (d > 0) Color(0xFFFF3B30) else Color(0xFF34C759),
                    )
                }
                Text(
                    "${cur.sumOf { it.unlocks } / cur.size} odblokowań / dzień",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (period.type != PeriodType.YEAR) {
            Spacer(Modifier.height(8.dp))
            val n = (period.end.toEpochDay() - period.start.toEpochDay()).toInt()
            DailyScreenBars(cur, period.start, n, null)
        }
        if (apps.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            apps.forEach { (pkg, ms) -> AppRow(pkg, ms / cur.size, avg, small = false) }
        }
    }
}
