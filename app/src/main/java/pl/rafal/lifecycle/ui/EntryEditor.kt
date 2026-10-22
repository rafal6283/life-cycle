package pl.rafal.lifecycle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Entry
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.data.Place
import pl.rafal.lifecycle.data.Source
import pl.rafal.lifecycle.util.Fmt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
fun EntryEditorDialog(entry: Entry?, defaultDay: LocalDate, onDismiss: () -> Unit) {
    val repo = LifeApp.instance.repo
    val scope = rememberCoroutineScope()
    val categories by repo.categories.collectAsState(initial = emptyList())
    val places by repo.places.collectAsState(initial = emptyList())

    val initialStart = remember {
        entry?.startAt ?: run {
            val now = System.currentTimeMillis()
            if (defaultDay == LocalDate.now()) now - 3_600_000L
            else Fmt.dayStart(defaultDay) + 12 * 3_600_000L
        }
    }
    var start by remember { mutableLongStateOf(initialStart) }
    var end by remember { mutableStateOf(entry?.endAt ?: (if (entry == null) initialStart + 3_600_000L else null)) }
    var catId by remember { mutableStateOf(entry?.categoryId) }
    var note by remember { mutableStateOf(entry?.note ?: "") }
    val canSavePlace = entry != null && entry.placeId == null && entry.lat != null && entry.source == Source.AUTO_STAY
    var savePlace by remember { mutableStateOf(false) }
    var placeName by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val place = entry?.placeId?.let { id -> places.firstOrNull { it.id == id } }

    val endValue = end
    val valid = catId != null && (endValue == null || endValue > start) &&
        (!savePlace || placeName.isNotBlank())

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 720.dp),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text(
                    if (entry == null) "Nowy wpis" else "Edytuj wpis",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (place != null) {
                    Text(
                        "Miejsce: ${place.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text("Kategoria", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                CategoryPicker(categories.filter { it.kind != Kind.UNKNOWN || it.id == catId }, catId) { catId = it }

                Spacer(Modifier.height(16.dp))
                DateTimeRow("Początek", start) { start = it }
                if (endValue == null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Koniec", Modifier.width(84.dp))
                        Text("trwa", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { end = System.currentTimeMillis() }) { Text("Zakończ teraz") }
                    }
                } else {
                    DateTimeRow("Koniec", endValue) { end = it }
                }
                if (endValue != null && endValue <= start) {
                    Text(
                        "Koniec musi być później niż początek.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("Notatka") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                if (canSavePlace) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = savePlace, onCheckedChange = { savePlace = it })
                        Text("Zapamiętaj to miejsce")
                    }
                    if (savePlace) {
                        OutlinedTextField(
                            value = placeName, onValueChange = { placeName = it },
                            label = { Text("Nazwa miejsca, np. Dom") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Text(
                            "Kolejne pobyty w tym miejscu będą od razu przypisywane do wybranej kategorii.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (entry != null) {
                        TextButton(onClick = { confirmDelete = true }) {
                            Text("Usuń", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Anuluj") }
                    Button(enabled = valid, onClick = {
                        val cid = catId ?: return@Button
                        scope.launch {
                            if (entry == null) {
                                repo.saveEntry(
                                    Entry(
                                        startAt = start, endAt = end, categoryId = cid,
                                        source = Source.MANUAL, note = note.ifBlank { null },
                                    )
                                )
                            } else {
                                // Dojazd jest wyliczany — w bazie zostaje oryginalna kategoria przejazdu.
                                val commuteInfo = pl.rafal.lifecycle.domain.Commutes.info(entry.id)
                                val cid = if (commuteInfo != null && cid == entry.categoryId) commuteInfo.originalCategoryId else cid
                                val timesChanged = start != entry.startAt || end != entry.endAt
                                val newSource = if (timesChanged && end != null) Source.MANUAL else entry.source
                                var updated = entry.copy(
                                    startAt = start, endAt = end, categoryId = cid,
                                    note = note.ifBlank { null }, source = newSource,
                                )
                                val lat = entry.lat
                                val lng = entry.lng
                                if (canSavePlace && savePlace && lat != null && lng != null) {
                                    val pid = repo.savePlace(
                                        Place(name = placeName.trim(), lat = lat, lng = lng, categoryId = cid)
                                    )
                                    updated = updated.copy(placeId = pid)
                                }
                                repo.saveEntry(updated)
                            }
                            onDismiss()
                        }
                    }) { Text("Zapisz") }
                }
            }
        }
    }

    if (confirmDelete && entry != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Usunąć wpis?") },
            text = { Text("Tego nie można cofnąć.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repo.deleteEntry(entry)
                        confirmDelete = false
                        onDismiss()
                    }
                }) { Text("Usuń", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Anuluj") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeRow(label: String, value: Long, onChange: (Long) -> Unit) {
    val zone = Fmt.zone
    val ldt = Instant.ofEpochMilli(value).atZone(zone).toLocalDateTime()
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(84.dp))
        TextButton(onClick = { pickDate = true }) {
            Text(ldt.format(DateTimeFormatter.ofPattern("d MMM yyyy", Fmt.PL)))
        }
        TextButton(onClick = { pickTime = true }) {
            Text(ldt.format(DateTimeFormatter.ofPattern("HH:mm")), fontWeight = FontWeight.SemiBold)
        }
    }

    if (pickDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = ldt.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        val d = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        onChange(d.atTime(ldt.toLocalTime()).atZone(zone).toInstant().toEpochMilli())
                    }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Anuluj") } },
        ) { DatePicker(state) }
    }

    if (pickTime) {
        val state = rememberTimePickerState(initialHour = ldt.hour, initialMinute = ldt.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange(ldt.toLocalDate().atTime(state.hour, state.minute).atZone(zone).toInstant().toEpochMilli())
                    pickTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Anuluj") } },
            text = { TimePicker(state) },
        )
    }
}
