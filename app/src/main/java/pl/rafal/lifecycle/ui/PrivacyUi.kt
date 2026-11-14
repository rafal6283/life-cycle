package pl.rafal.lifecycle.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pl.rafal.lifecycle.domain.CalendarExport
import pl.rafal.lifecycle.util.Prefs
import java.time.LocalDate

@Composable
private fun ToggleRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

/** Ustawienia: blokada aplikacji, zdjęcia, oś czasu w kalendarzu. */
@Composable
fun PrivacySettings() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var lock by remember { mutableStateOf(prefs.lockEnabled) }
    var photos by remember { mutableStateOf(prefs.photosOn) }
    var calId by remember { mutableStateOf(prefs.calendarExport) }
    var pick by remember { mutableStateOf(false) }
    val calPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) pick = true
    }

    ToggleRow("🔒 Blokada aplikacji", "Odcisk palca, twarz albo kod telefonu przy otwieraniu Doby", lock) { on ->
        if (on && !AppLock.available(ctx)) {
            Toast.makeText(ctx, "Najpierw ustaw blokadę ekranu w telefonie", Toast.LENGTH_LONG).show()
        } else {
            lock = on; prefs.lockEnabled = on
        }
    }
    HorizontalDivider(Modifier.padding(start = 16.dp))
    ToggleRow("📷 Zdjęcia z galerii", "Miniatury zdjęć przy dniach i wyjazdach (zdjęcia zostają w telefonie)", photos) {
        photos = it; prefs.photosOn = it
    }
    HorizontalDivider(Modifier.padding(start = 16.dp))
    ToggleRow(
        "📅 Oś czasu w kalendarzu",
        if (calId >= 0) "Zapisuję dni do: ${CalendarExport.calendars(ctx).firstOrNull { it.id == calId }?.name ?: "kalendarz"}"
        else "Pobyty i aktywności jako wydarzenia — przeszłość obok planów",
        calId >= 0,
    ) { on ->
        if (on) {
            if (CalendarExport.allowed(ctx)) pick = true
            else calPerm.launch(arrayOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR))
        } else {
            val old = calId
            calId = -1; prefs.calendarExport = -1
            scope.launch { CalendarExport.clear(ctx, old) }
        }
    }

    if (pick) {
        val cals = remember { CalendarExport.calendars(ctx) }
        AlertDialog(
            onDismissRequest = { pick = false },
            title = { Text("Do którego kalendarza?") },
            text = {
                Column {
                    Text(
                        "Wydarzenia Doby są oznaczone i można je w każdej chwili usunąć, wyłączając tę opcję.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    fun choose(id: Long?) {
                        pick = false
                        if (id == null) return
                        calId = id; prefs.calendarExport = id
                        scope.launch {
                            val n = CalendarExport.export(ctx, LocalDate.now().minusDays(30), LocalDate.now())
                            Toast.makeText(ctx, "Zapisano $n wydarzeń z ostatnich 30 dni", Toast.LENGTH_SHORT).show()
                        }
                    }
                    TextButton(onClick = { choose(CalendarExport.createLocal(ctx)) }) { Text("➕ Nowy kalendarz „Doba” (tylko w telefonie)") }
                    cals.filter { it.account != "Doba" }.forEach { c ->
                        TextButton(onClick = { choose(c.id) }) { Text("${c.name} · ${c.account}") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pick = false }) { Text("Anuluj") } },
        )
    }
}
