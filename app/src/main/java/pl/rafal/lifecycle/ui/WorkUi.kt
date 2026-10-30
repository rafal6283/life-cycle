package pl.rafal.lifecycle.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Entry
import pl.rafal.lifecycle.domain.WorkDay
import pl.rafal.lifecycle.domain.WorkMonth
import pl.rafal.lifecycle.domain.WorkTime
import pl.rafal.lifecycle.util.Fmt
import pl.rafal.lifecycle.util.Prefs
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val WorkColor = Color(0xFFFF9F0A)

fun signedDuration(ms: Long): String =
    (if (ms >= 0) "+" else "−") + Fmt.durationShort(kotlin.math.abs(ms))

fun shareFile(ctx: Context, file: File, mime: String, title: String) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, title))
}

/** Karta na ekranie dnia: godziny pracy, przerwy i nadgodziny. */
@Composable
fun WorkDayCard(day: LocalDate, entries: List<Entry>, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val cats by LifeApp.instance.repo.categories.collectAsState(initial = emptyList())
    val wd = remember(entries, cats, day) {
        if (cats.isEmpty()) null
        else WorkTime.day(ctx, entries, cats.associateBy { it.id }, day, System.currentTimeMillis())
    } ?: return
    Column(Modifier.padding(top = 10.dp)) {
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface).clickable(onClick = onOpen).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(WorkColor), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Work, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Czas pracy", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "${Fmt.time(wd.start)}–${Fmt.time(wd.end)} · przerwy ${Fmt.durationShort(wd.breakMs)}" +
                        (if (wd.travelMs > 0) " · w trasie ${Fmt.durationShort(wd.travelMs)}" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.durationShort(wd.workedMs), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (wd.normMs > 0 || wd.overtimeMs > 0) {
                    Text(
                        signedDuration(wd.overtimeMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (wd.overtimeMs >= 0) Color(0xFF34C759) else Color(0xFFFF453A),
                    )
                }
            }
        }
    }
}

/** Ewidencja czasu pracy: miesiąc, dni, saldo nadgodzin, klienci i eksport. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var monthKey by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    val ym = YearMonth.parse(monthKey)
    var tick by remember { mutableIntStateOf(0) }
    val data by produceState<WorkMonth?>(null, monthKey, tick) {
        value = null
        value = WorkTime.month(ctx, ym)
    }
    var norm by remember { mutableIntStateOf(prefs.workNormMin) }
    var days by remember { mutableStateOf(prefs.workDays) }
    var travel by remember { mutableStateOf(prefs.workCountTravel) }
    var showSettings by remember { mutableStateOf(false) }
    val dF = remember { DateTimeFormatter.ofPattern("EEE d.MM", Fmt.PL) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { ScreenHeader("Czas pracy", onBack = onBack) }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { monthKey = ym.minusMonths(1).toString() }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Poprzedni miesiąc")
                }
                Text(
                    WorkTime.monthTitle(ym), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                IconButton(onClick = { monthKey = ym.plusMonths(1).toString() }, enabled = ym < YearMonth.now()) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Następny miesiąc")
                }
            }
        }
        val m = data
        if (m == null) {
            item { Text("Liczę…", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            item {
                TrendCard {
                    Row {
                        Stat("Przepracowano", Fmt.durationShort(m.workedMs), Modifier.weight(1f))
                        Stat("Norma do dziś", Fmt.durationShort(m.normMs), Modifier.weight(1f))
                        Stat(
                            "Saldo", signedDuration(m.balanceMs), Modifier.weight(1f),
                            if (m.balanceMs >= 0) Color(0xFF34C759) else Color(0xFFFF453A),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${m.days.size} dni pracy" + (m.days.takeIf { it.isNotEmpty() }?.let {
                            " · średnio ${Fmt.durationShort(m.workedMs / it.size)} · zaczynasz ok. ${avgTime(it.map { d -> d.start })}"
                        } ?: "") + (m.days.sumOf { it.km }.takeIf { it > 0 }?.let { " · ${Fmt.distance(it)} w pracy" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                val f = WorkTime.pdfFile(ctx, m)
                                shareFile(ctx, f, "application/pdf", "Czas pracy — ${WorkTime.monthTitle(ym)}")
                            }
                        }) { Text("PDF") }
                        OutlinedButton(onClick = {
                            scope.launch {
                                val f = WorkTime.csvFile(ctx, m)
                                shareFile(ctx, f, "text/csv", "Czas pracy — ${WorkTime.monthTitle(ym)}")
                            }
                        }) { Text("CSV") }
                        OutlinedButton(onClick = { showSettings = !showSettings }) { Text("Ustawienia") }
                    }
                }
            }
            if (showSettings) {
                item { SectionTitle("Ustawienia ewidencji") }
                item {
                    TrendCard {
                        Text("Norma dzienna", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(4, 6, 7, 8, 9, 10, 12).forEach { h ->
                                FilterChip(selected = norm == h * 60, onClick = {
                                    norm = h * 60; prefs.workNormMin = norm; tick++
                                }, label = { Text("$h h") })
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Dni pracujące", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Pn", "Wt", "Śr", "Cz", "Pt", "So", "Nd").forEachIndexed { i, l ->
                                val d = i + 1
                                FilterChip(selected = d in days, onClick = {
                                    days = if (d in days) days - d else days + d
                                    prefs.workDays = days; tick++
                                }, label = { Text(l) })
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Przejazdy w trakcie pracy to praca", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Np. jazda między klientami. Dojazd z domu i do domu się nie liczy.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = travel, onCheckedChange = { travel = it; prefs.workCountTravel = it; tick++ })
                        }
                        Text(
                            "Czas pracy to pobyty w kategoriach „Praca” i „Wizyta u klienta” — od pierwszego do ostatniego. " +
                                "Wyjście na obiad czy sprawy prywatne liczą się jako przerwa.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (m.days.isEmpty()) {
                item {
                    Text(
                        "Brak dni pracy w tym miesiącu. Zapisz miejsce pracy w zakładce Miejsca (kategoria „Praca”) " +
                            "albo włącz pracę w terenie w ustawieniach.",
                        Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item { SectionTitle("Dni") }
                item {
                    Column(
                        Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        m.days.reversed().forEach { d -> WorkDayRow(d, dF) }
                    }
                }
            }
            if (m.clients.isNotEmpty()) {
                item { SectionTitle("Klienci · ${m.clients.size}") }
                items(m.clients, key = { it.key }) { c ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            Text(
                                "${c.visits} ${if (c.visits == 1) "wizyta" else if (c.visits in 2..4) "wizyty" else "wizyt"} · " +
                                    "dni: ${c.days.joinToString(", ") { it.dayOfMonth.toString() }}" +
                                    (if (c.travelMs > 0) " · dojazdy ${Fmt.durationShort(c.travelMs)}" else "") +
                                    (if (c.km > 0) " · ${Fmt.distance(c.km)}" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(Fmt.durationShort(c.visitMs), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

private fun avgTime(times: List<Long>): String {
    val mins = times.map { val z = java.time.Instant.ofEpochMilli(it).atZone(Fmt.zone); z.hour * 60 + z.minute }
    val a = mins.average().toInt()
    return "%d:%02d".format(a / 60, a % 60)
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, color: Color = Color.Unspecified) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WorkDayRow(d: WorkDay, f: DateTimeFormatter) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(d.day.format(f).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${Fmt.time(d.start)}–${Fmt.time(d.end)} · przerwy ${Fmt.durationShort(d.breakMs)}" +
                    (if (d.travelMs > 0) " · trasa ${Fmt.durationShort(d.travelMs)}" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Fmt.durationShort(d.workedMs), fontWeight = FontWeight.SemiBold)
            Text(
                signedDuration(d.overtimeMs),
                style = MaterialTheme.typography.labelSmall,
                color = if (d.overtimeMs >= 0) Color(0xFF34C759) else Color(0xFFFF453A),
            )
        }
    }
}

@Suppress("unused")
private suspend fun toast(ctx: Context, s: String) = withContext(Dispatchers.Main) {
    Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
}
