package pl.rafal.lifecycle.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.domain.FieldWork
import pl.rafal.lifecycle.util.Fmt
import pl.rafal.lifecycle.util.Prefs
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

val FieldColor = Color(0xFF0A84FF)

private fun km(v: Double) = String.format(Fmt.PL, "%.1f km", v)

private fun hhmm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

// ---------------------------------------------------------------------------
// Karta na ekranie dnia
// ---------------------------------------------------------------------------

@Composable
fun FieldDayCard(day: LocalDate, refreshKey: Any, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val r by produceState<FieldWork.DayReport?>(null, day, refreshKey) { value = FieldWork.report(ctx, day) }
    val rep = r ?: return
    if (rep.visits.isEmpty()) return
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface).clickable(onClick = onOpen).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(FieldColor), contentAlignment = Alignment.Center) {
            CatIcon("handyman", Color.White, 18.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Praca w terenie", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "${rep.visits.size} ${visitsWord(rep.visits.size)} · u klientów ${Fmt.durationShort(rep.visitMs)} · " +
                    "w drodze ${Fmt.durationShort(rep.driveMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(km(rep.km), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (rep.spanMs > 0) {
                Text(
                    "${Fmt.time(rep.workStart!!)}–${Fmt.time(rep.workEnd!!)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun visitsWord(n: Int) = when {
    n == 1 -> "wizyta"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "wizyty"
    else -> "wizyt"
}

// ---------------------------------------------------------------------------
// Raport: dzień i kilometrówka miesięczna
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FieldReportDialog(initialDay: LocalDate, onDismiss: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var day by remember { mutableStateOf(initialDay) }
    var ym by remember { mutableStateOf(YearMonth.from(initialDay)) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Zamknij") }
                    Text("Praca w terenie", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    listOf("Dzień", "Kilometrówka").forEachIndexed { i, l ->
                        SegmentedButton(tab == i, { tab = i }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(l) }
                    }
                }
                if (tab == 0) FieldDayView(day) { day = it } else FieldMonthView(ym, { ym = it }) { d -> day = d; tab = 0 }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun FieldDayView(day: LocalDate, onDay: (LocalDate) -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<pl.rafal.lifecycle.data.Entry?>(null) }
    val rep by produceState<FieldWork.DayReport?>(null, day, refresh) {
        value = null
        value = FieldWork.report(ctx, day)
    }
    var notClient by remember { mutableStateOf<Pair<List<pl.rafal.lifecycle.data.Entry>, String>?>(null) }
    val fieldScope = rememberCoroutineScope()
    val frequent by produceState<List<FieldWork.Frequent>>(emptyList(), refresh) { value = FieldWork.frequent(ctx) }
    DayNav(day, onDay)
    frequent.take(3).forEach { f ->
        FrequentCard(f, onSave = { addr -> notClient = f.entries to addr }, onClient = {
            FieldWork.dismiss(ctx, f.key)
            refresh++
        })
        Spacer(Modifier.height(8.dp))
    }
    notClient?.let { (entries, addr) ->
        NotClientDialog(entries.size, addr, onDismiss = { notClient = null }) { catId, name ->
            notClient = null
            fieldScope.launch {
                FieldWork.reassign(ctx, entries, catId, name)
                refresh++
            }
        }
    }
    val r = rep
    if (r == null) {
        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (r.visits.isEmpty()) {
        Text(
            "Brak wizyt u klientów tego dnia.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(32.dp),
        )
        return
    }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
        FieldStat("wizyty", "${r.visits.size}", Modifier.weight(1f))
        FieldStat("u klientów", Fmt.durationShort(r.visitMs), Modifier.weight(1f))
        FieldStat("w drodze", Fmt.durationShort(r.driveMs), Modifier.weight(1f))
        FieldStat("przejechane", km(r.km), Modifier.weight(1f))
    }
    if (r.workStart != null && r.workEnd != null) {
        Text(
            "Wyjazd ${Fmt.time(r.workStart)} · powrót ${Fmt.time(r.workEnd)} · razem ${Fmt.durationShort(r.spanMs)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    SectionTitle("Przebieg dnia")
    Column(
        Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface).padding(vertical = 6.dp)
    ) {
        // Przeplatane: przejazd → wizyta → przejazd → …
        val items = (r.trips.map { it.start to it } + r.visits.map { it.start to it }).sortedBy { it.first }.map { it.second }
        var visitNo = 0
        items.forEach { item ->
            when (item) {
                is FieldWork.Trip -> Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.DirectionsCar, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${Fmt.time(item.start)}–${Fmt.time(item.end)} · ${Fmt.durationShort(item.end - item.start)}" +
                            (if (item.stops > 0) " · postoje: ${item.stops}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        item.distanceM?.let { km(it / 1000) } ?: "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is FieldWork.Visit -> {
                    visitNo++
                    val no = visitNo
                    val addr by produceState<String?>(null, item.lat, item.lng) {
                        value = if (item.lat != null && item.lng != null) FieldWork.address(ctx, item.lat, item.lng) else null
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { editing = item.entry }.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(FieldColor), contentAlignment = Alignment.Center) {
                            Text("$no", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.name ?: addr ?: "Wizyta u klienta",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                listOfNotNull("${Fmt.time(item.start)}–${Fmt.time(item.end)}", addr?.takeIf { item.name != null })
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(Fmt.durationShort(item.end - item.start), fontWeight = FontWeight.SemiBold)
                            Text(
                                "To nie klient",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { notClient = listOf(item.entry) to (addr ?: "") }
                                    .padding(vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    Text(
        "Dotknij wizyty, by dopisać klienta lub numer zlecenia (notatka) albo zapisać stałe miejsce klienta.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
    editing?.let { EntryEditorDialog(entry = it, defaultDay = day, onDismiss = { editing = null; refresh++ }) }
}

@Composable
private fun FieldStat(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FieldMonthView(ym: YearMonth, onMonth: (YearMonth) -> Unit, onDay: (LocalDate) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rows by produceState<List<FieldWork.MonthRow>?>(null, ym) {
        value = null
        value = FieldWork.month(ctx, ym)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val n = try {
                FieldWork.exportCsv(ctx, uri, ym)
            } catch (e: Exception) {
                -1
            }
            Toast.makeText(ctx, if (n >= 0) "Zapisano $n przejazdów" else "Nie udało się zapisać pliku", Toast.LENGTH_SHORT).show()
        }
    }
    val title = ym.format(DateTimeFormatter.ofPattern("LLLL yyyy", Fmt.PL)).replaceFirstChar { it.titlecase(Fmt.PL) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onMonth(ym.minusMonths(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Poprzedni") }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        IconButton(onClick = { onMonth(ym.plusMonths(1)) }, enabled = ym < YearMonth.now()) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Następny")
        }
    }
    val r = rows
    if (r == null) {
        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (r.isEmpty()) {
        Text(
            "Brak dni z wizytami w tym miesiącu.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(32.dp),
        )
        return
    }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
        FieldStat("dni pracy", "${r.size}", Modifier.weight(1f))
        FieldStat("wizyty", "${r.sumOf { it.visits }}", Modifier.weight(1f))
        FieldStat("u klientów", Fmt.durationShort(r.sumOf { it.visitMs }), Modifier.weight(1f))
        FieldStat("kilometry", km(r.sumOf { it.km }), Modifier.weight(1f))
    }
    Column(
        Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface).padding(vertical = 4.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
            listOf("Dzień" to 1.4f, "Wizyty" to 1f, "Czas" to 1.2f, "km" to 1f).forEach { (h, w) ->
                Text(h, Modifier.weight(w), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val f = DateTimeFormatter.ofPattern("EEE d.MM", Fmt.PL)
        r.forEach { row ->
            Row(
                Modifier.fillMaxWidth().clickable { onDay(row.day) }.padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(row.day.format(f), Modifier.weight(1.4f), style = MaterialTheme.typography.bodyMedium)
                Text("${row.visits}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(Fmt.durationShort(row.spanMs), Modifier.weight(1.2f), style = MaterialTheme.typography.bodyMedium)
                Text(String.format(Fmt.PL, "%.1f", row.km), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    Button(
        onClick = { exportLauncher.launch("kilometrowka-${ym}.csv") },
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) { Text("Eksportuj kilometrówkę do CSV") }
    Text(
        "Plik otworzysz w Excelu lub Arkuszach: data, godziny, skąd–dokąd, kilometry i cel (klient z notatki, nazwa miejsca albo adres).",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
}

// ---------------------------------------------------------------------------
// Ustawienia trybu
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FieldWorkSettings(onReport: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(prefs.fieldWork) }
    var days by remember { mutableStateOf(prefs.fieldDays) }
    var start by remember { mutableIntStateOf(prefs.fieldStart) }
    var end by remember { mutableIntStateOf(prefs.fieldEnd) }
    var pick by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Praca w terenie", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Nowe miejsca w godzinach pracy to wizyty u klienta — bez powiadomień i bez dodawania do listy miejsc.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.Switch(on, { on = it; prefs.fieldWork = it })
        }
        if (on) {
            Spacer(Modifier.height(10.dp))
            Text("Dni pracy", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Pn", "Wt", "Śr", "Cz", "Pt", "So", "Nd").forEachIndexed { i, l ->
                    val d = i + 1
                    FilterChip(d in days, {
                        days = if (d in days) days - d else days + d
                        prefs.fieldDays = days
                    }, { Text(l) })
                }
            }
            var minMin by remember { mutableIntStateOf(prefs.fieldMinMinutes) }
            Text("Wizyta trwa co najmniej", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(5, 10, 15, 20, 30, 45).forEach { m ->
                    FilterChip(minMin == m, { minMin = m; prefs.fieldMinMinutes = m }, { Text("$m min") })
                }
            }
            Text(
                "Krótsze postoje (sklep, stacja) to „Postój w trasie” — nie liczą się jako wizyty.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Godziny", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { pick = "start" }) { Text(hhmm(start), fontWeight = FontWeight.SemiBold) }
                Text("–")
                TextButton(onClick = { pick = "end" }) { Text(hhmm(end), fontWeight = FontWeight.SemiBold) }
            }
            Row {
                TextButton(onClick = onReport) { Text("Raport i kilometrówka") }
                TextButton(onClick = {
                    scope.launch {
                        val n = FieldWork.applyToPast(ctx, 30)
                        Toast.makeText(ctx, "Oznaczono $n wcześniejszych wizyt", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Oznacz z 30 dni") }
            }
        }
    }

    pick?.let { which ->
        val cur = if (which == "start") start else end
        val state = rememberTimePickerState(initialHour = cur / 60, initialMinute = cur % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pick = null },
            confirmButton = {
                TextButton(onClick = {
                    val v = state.hour * 60 + state.minute
                    if (which == "start") { start = v; prefs.fieldStart = v } else { end = v; prefs.fieldEnd = v }
                    pick = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pick = null }) { Text("Anuluj") } },
            text = { TimePicker(state) },
        )
    }
}

// ---------------------------------------------------------------------------
// Szkoła / przedszkole — karta dnia
// ---------------------------------------------------------------------------

@Composable
fun SchoolDayCard(day: LocalDate, refreshKey: Any) {
    val app = LifeApp.instance
    val runs by produceState<List<pl.rafal.lifecycle.domain.SchoolRuns.Run>>(emptyList(), day, refreshKey) {
        val from = Fmt.dayStart(day)
        val to = Fmt.dayStart(day.plusDays(1))
        val cats = app.db.categories().getAll().associateBy { it.id }
        value = pl.rafal.lifecycle.domain.SchoolRuns.detect(app.repo.getRange(from, to), cats).first
            .filter { it.start in from until to }
    }
    if (runs.isEmpty()) return
    val cat = remember { mutableStateOf<pl.rafal.lifecycle.data.Category?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { cat.value = app.db.categories().byKind(Kind.SCHOOL) }
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryBadge(cat.value, 30.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Szkoła / przedszkole", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                runs.joinToString(" · ") { (if (it.dropOff) "zawiezienie " else "odbiór ") + Fmt.time(it.start) },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Fmt.durationShort(runs.sumOf { it.end - it.start }), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            runs.mapNotNull { it.distanceM }.takeIf { it.isNotEmpty() }?.let {
                Text(km(it.sum() / 1000), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}


// ---------------------------------------------------------------------------
// „To nie klient” i częste postoje
// ---------------------------------------------------------------------------

@Composable
private fun FrequentCard(f: FieldWork.Frequent, onSave: (String) -> Unit, onClient: () -> Unit) {
    val ctx = LocalContext.current
    val addr by produceState<String?>(null, f.key) { value = FieldWork.address(ctx, f.lat, f.lng) }
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFFFCC00).copy(alpha = 0.15f)).padding(14.dp)
    ) {
        Text(
            "Częsty postój: ${addr ?: "to samo miejsce"}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Byłeś tu ${f.visits} razy w ${f.days} różne dni (ostatnie 30 dni). To raczej sklep, hurtownia albo stacja niż klient?",
            style = MaterialTheme.typography.bodySmall,
        )
        Row {
            TextButton(onClick = { onSave(addr ?: "") }) { Text("Zapisz jako miejsce") }
            TextButton(onClick = onClient) { Text("To klient") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotClientDialog(count: Int, address: String, onDismiss: () -> Unit, onSave: (Long, String?) -> Unit) {
    val categories by LifeApp.instance.repo.categories.collectAsState(initial = emptyList())
    val shop = categories.firstOrNull { it.name == "Zakupy" }?.id
    var cat by remember(shop) { mutableStateOf(shop) }
    var keep by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf(address) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count > 1) "To nie klient ($count wizyt)" else "To nie klient") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Co to było?", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                CategoryPicker(
                    categories.filter { it.kind !in setOf(Kind.FIELD, Kind.UNKNOWN, Kind.COMMUTE, Kind.STOP) },
                    cat,
                ) { cat = it }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(keep, { keep = it })
                    Text("Zapamiętaj to miejsce na stałe")
                }
                if (keep) {
                    androidx.compose.material3.OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Nazwa, np. Castorama Otwock") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Wcześniejsze i kolejne postoje tutaj dostaną tę kategorię zamiast „Serwis u klienta”.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = cat != null && (!keep || name.isNotBlank()),
                onClick = { cat?.let { onSave(it, if (keep) name else null) } },
            ) { Text("Zapisz") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}
