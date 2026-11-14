package pl.rafal.lifecycle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.rafal.lifecycle.domain.Mileage
import pl.rafal.lifecycle.domain.MileageMonth
import pl.rafal.lifecycle.domain.WorkTime
import pl.rafal.lifecycle.util.Fmt
import pl.rafal.lifecycle.util.Prefs
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
fun MileageScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var monthKey by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    val ym = YearMonth.parse(monthKey)
    var tick by remember { mutableIntStateOf(0) }
    val data by produceState<MileageMonth?>(null, monthKey, tick) { value = null; value = Mileage.month(ctx, ym) }
    var owner by remember { mutableStateOf(prefs.carOwner) }
    var plate by remember { mutableStateOf(prefs.carPlate) }
    var cc by remember { mutableStateOf(if (prefs.carEngineCc > 0) prefs.carEngineCc.toString() else "") }
    var rate by remember { mutableStateOf(if (prefs.mileageRate > 0) String.format(Fmt.PL, "%.2f", prefs.mileageRate) else "") }
    var home by remember { mutableStateOf(prefs.mileageHomeTrips) }
    var showCar by remember { mutableStateOf(prefs.carPlate.isBlank()) }
    val dF = remember { DateTimeFormatter.ofPattern("EEE d.MM", Fmt.PL) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { ScreenHeader("Kilometrówka", onBack = onBack) }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { monthKey = ym.minusMonths(1).toString() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Poprzedni") }
                Text(WorkTime.monthTitle(ym), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { monthKey = ym.plusMonths(1).toString() }, enabled = ym < YearMonth.now()) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Następny")
                }
            }
        }
        val m = data
        item {
            TrendCard {
                if (m == null) Text("Liczę…") else {
                    Text(
                        "${String.format(Fmt.PL, "%.1f", m.km)} km · ${String.format(Fmt.PL, "%.2f zł", m.amount)}",
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${m.trips.size} przejazdów służbowych · stawka ${String.format(Fmt.PL, "%.2f", m.rate)} zł/km",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch { shareFile(ctx, Mileage.pdfFile(ctx, m), "application/pdf", "Kilometrówka — ${WorkTime.monthTitle(ym)}") }
                        }) { Text("PDF") }
                        OutlinedButton(onClick = {
                            scope.launch { shareFile(ctx, Mileage.csvFile(ctx, m), "text/csv", "Kilometrówka — ${WorkTime.monthTitle(ym)}") }
                        }) { Text("CSV") }
                        OutlinedButton(onClick = { showCar = !showCar }) { Text("Pojazd") }
                    }
                }
            }
        }
        if (showCar) {
            item {
                TrendCard {
                    Text("Dane do ewidencji", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(owner, { owner = it; prefs.carOwner = it }, label = { Text("Imię i nazwisko") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(plate, { plate = it.uppercase(); prefs.carPlate = plate }, label = { Text("Nr rejestracyjny") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        cc, { v -> cc = v.filter { it.isDigit() }.take(5); prefs.carEngineCc = cc.toIntOrNull() ?: 0; tick++ },
                        label = { Text("Pojemność silnika (cm³)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        rate, { v -> rate = v; prefs.mileageRate = v.replace(',', '.').toFloatOrNull() ?: 0f; tick++ },
                        label = { Text("Stawka zł/km (puste = ${String.format(Fmt.PL, "%.2f", Mileage.defaultRate(cc.toIntOrNull() ?: 0))})") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Dojazd z domu do pierwszego klienta i powrót")
                            Text(
                                "Przy pracy w terenie. Codzienny dojazd do stałego miejsca pracy się nie liczy.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(home, { home = it; prefs.mileageHomeTrips = it; tick++ })
                    }
                    Text(
                        "Stawki maksymalne: auto ≤ 900 cm³ — 0,89 zł/km, powyżej 900 cm³ — 1,15 zł/km. " +
                            "Doba zlicza przejazdy samochodem w godzinach pracy; sprawdź listę przed wydrukiem.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (m != null) {
            if (m.trips.isEmpty()) {
                item { Text("Brak przejazdów samochodem w czasie pracy w tym miesiącu.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(m.trips, key = { it.start }) { t ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${t.from} → ${t.to}", maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${t.day.format(dF)} ${Fmt.time(t.start)}–${Fmt.time(t.end)} · ${t.purpose}" + if (t.driving) " · 🚗 za kierownicą" else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(String.format(Fmt.PL, "%.1f km", t.km), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
