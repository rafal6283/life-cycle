package pl.rafal.lifecycle.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.rafal.lifecycle.BuildConfig
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Category
import pl.rafal.lifecycle.data.Kind
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import pl.rafal.lifecycle.domain.Backup
import pl.rafal.lifecycle.domain.CsvExport
import pl.rafal.lifecycle.domain.KreskaExport
import pl.rafal.lifecycle.domain.WeeklySummary
import pl.rafal.lifecycle.tracking.WorkoutImporter
import pl.rafal.lifecycle.util.Fmt
import pl.rafal.lifecycle.tracking.SleepDetector
import pl.rafal.lifecycle.tracking.TrackingControl
import pl.rafal.lifecycle.util.Prefs
import java.time.LocalDate

@Composable
fun SettingsScreen(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var tracking by remember { mutableStateOf(prefs.tracking) }
    var sleepHc by remember { mutableStateOf(prefs.sleepHc) }
    var sleepAuto by remember { mutableStateOf(prefs.sleepAuto) }
    var workouts by remember { mutableStateOf(prefs.workoutsHc) }
    var stepsHc by remember { mutableStateOf(prefs.stepsHc) }
    var commutes by remember { mutableStateOf(prefs.commutes) }
    var stepGoalDialog by remember { mutableStateOf(false) }
    var stepGoalTick by remember { mutableIntStateOf(0) }
    var weekly by remember { mutableStateOf(prefs.weeklySummary) }
    var backupTree by remember { mutableStateOf(prefs.backupTree) }
    var lastBackup by remember { mutableStateOf(prefs.lastBackupAt) }
    var summaryText by remember { mutableStateOf<String?>(null) }
    var confirmRestore by remember { mutableStateOf<Uri?>(null) }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: Exception) {
            }
            prefs.backupTree = uri.toString()
            backupTree = uri.toString()
            scope.launch {
                val ok = Backup.autoBackup(ctx, force = true)
                lastBackup = prefs.lastBackupAt
                toast(if (ok) "Kopia zapisana w wybranym folderze" else "Nie udało się zapisać kopii w tym folderze")
            }
        }
    }
    val backupFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            try {
                Backup.exportTo(ctx, uri)
                toast("Kopia zapisana")
            } catch (e: Exception) {
                toast("Nie udało się zapisać kopii")
            }
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmRestore = uri
    }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val missing = remember(tick) {
        listOf(
            TrackingControl.hasFineLocation(ctx), TrackingControl.hasBackground(ctx),
            TrackingControl.hasActivity(ctx), TrackingControl.batteryUnrestricted(ctx),
        ).count { !it }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val n = try {
                CsvExport.write(ctx, uri)
            } catch (e: Exception) {
                -1
            }
            Toast.makeText(ctx, if (n >= 0) "Wyeksportowano $n wpisów" else "Eksport nie powiódł się", Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenHeader("Ustawienia")

        SectionTitle("Śledzenie")
        Group {
            SwitchRow("Automatyczne śledzenie", "Lokalizacja i ruch w tle", tracking) {
                tracking = it
                prefs.tracking = it
                if (it) TrackingControl.startIfAllowed(ctx) else TrackingControl.stop(ctx)
            }
            HorizontalDivider(Modifier.padding(start = 16.dp))
            NavRow(
                "Uprawnienia i bateria",
                if (missing == 0) "Wszystko ustawione" else "Brakuje: $missing",
                subtitleColor = if (missing == 0) Color(0xFF34C759) else MaterialTheme.colorScheme.error,
            ) { onOpen("permissions") }
        }

        SectionTitle("Sen")
        Group {
            SwitchRow("Sen z Health Connect", "Z zegarka lub aplikacji do snu", sleepHc) {
                sleepHc = it
                prefs.sleepHc = it
            }
            HorizontalDivider(Modifier.padding(start = 16.dp))
            SwitchRow("Wykrywaj sen automatycznie", "Z nocnej przerwy w używaniu telefonu", sleepAuto) {
                sleepAuto = it
                prefs.sleepAuto = it
            }
            HorizontalDivider(Modifier.padding(start = 16.dp))
            SwitchRow("Treningi z Health Connect", "Dokładne godziny i dystans z zegarka", workouts) {
                workouts = it
                prefs.workoutsHc = it
                if (it) scope.launch { withContext(Dispatchers.IO) { try { WorkoutImporter.run(ctx) } catch (_: Exception) {} } }
            }
            HorizontalDivider(Modifier.padding(start = 16.dp))
            NavRow("Przelicz sen teraz", null) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            SleepDetector.run(ctx)
                        } catch (_: Exception) {
                        }
                    }
                    Toast.makeText(ctx, "Sen przeliczony", Toast.LENGTH_SHORT).show()
                }
            }
        }

        SectionTitle("Dojazdy")
        Group {
            SwitchRow(
                "Rozpoznawaj dojazdy",
                "Przejazdy Dom ↔ Praca (z przystankami do 20 min) jako kategoria „Dojazd”",
                commutes,
            ) {
                commutes = it
                prefs.commutes = it
            }
        }

        SectionTitle("Kroki")
        Group {
            NavRow(
                "Dzienny cel kroków",
                remember(stepGoalTick) { Fmt.steps(prefs.stepGoal.toLong()) },
            ) { stepGoalDialog = true }
            HorizontalDivider(Modifier.padding(start = 16.dp))
            SwitchRow("Kroki z Health Connect", "Z zegarka; w każdej godzinie liczy się wyższa wartość z telefonu i zegarka", stepsHc) {
                stepsHc = it
                prefs.stepsHc = it
                if (it) scope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            pl.rafal.lifecycle.tracking.StepsImporter.run(ctx)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            if (!remember { pl.rafal.lifecycle.tracking.StepSensor.available(ctx) }) {
                HorizontalDivider(Modifier.padding(start = 16.dp))
                NavRow("Telefon nie ma licznika kroków", "Kroki będą pobierane tylko z Health Connect") { }
            }
        }

        SectionTitle("Cele i podsumowania")
        Group {
            NavRow("Cele", "Minimum, limity i „ile razy w tygodniu”") { onOpen("goals") }
            HorizontalDivider(Modifier.padding(start = 16.dp))
            SwitchRow("Podsumowanie tygodnia", "Powiadomienie w niedzielę wieczorem", weekly) {
                weekly = it
                prefs.weeklySummary = it
            }
            HorizontalDivider(Modifier.padding(start = 16.dp))
            NavRow("Pokaż podsumowanie tego tygodnia", null) {
                scope.launch { summaryText = WeeklySummary.build(ctx) ?: "Brak danych z tego tygodnia." }
            }
        }

        SectionTitle("Kopia zapasowa")
        Group {
            NavRow(
                "Automatyczna kopia codziennie",
                if (backupTree == null) "Wybierz folder (np. Synology Drive, dysk sieciowy)"
                else "Ostatnio: " + (if (lastBackup > 0) Fmt.dateTime(lastBackup) else "jeszcze nie") + " · zmień folder",
            ) { treeLauncher.launch(null) }
            if (backupTree != null) {
                HorizontalDivider(Modifier.padding(start = 16.dp))
                NavRow("Zrób kopię teraz", null) {
                    scope.launch {
                        val ok = Backup.autoBackup(ctx, force = true)
                        lastBackup = prefs.lastBackupAt
                        toast(if (ok) "Kopia zapisana" else "Nie udało się zapisać kopii")
                    }
                }
                HorizontalDivider(Modifier.padding(start = 16.dp))
                NavRow("Wyłącz automatyczną kopię", null) {
                    prefs.backupTree = null
                    backupTree = null
                }
            }
            HorizontalDivider(Modifier.padding(start = 16.dp))
            NavRow("Zapisz kopię do pliku", null) {
                backupFileLauncher.launch("doba-kopia-${LocalDate.now()}.json")
            }
            HorizontalDivider(Modifier.padding(start = 16.dp))
            NavRow("Przywróć z kopii", "Zastępuje wszystkie obecne dane") {
                restoreLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
            }
        }

        SectionTitle("Inne aplikacje")
        Group {
            NavRow("Wyślij do Kreski", "Cała historia w formacie wymiany zdarzeń") {
                scope.launch {
                    try {
                        val file = KreskaExport.buildFile(ctx)
                        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("application/json")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        ctx.startActivity(Intent.createChooser(send, "Wyślij do Kreski"))
                    } catch (e: Exception) {
                        toast("Nie udało się przygotować eksportu")
                    }
                }
            }
        }

        SectionTitle("Dane")
        Group {
            NavRow("Kategorie", "Nazwy, kolory i ikony") { onOpen("categories") }
            HorizontalDivider(Modifier.padding(start = 16.dp))
            NavRow("Eksportuj do CSV", "Wszystkie wpisy w jednym pliku") {
                exportLauncher.launch("doba-${LocalDate.now()}.csv")
            }
        }

        SectionTitle("O aplikacji")
        Group {
            NavRow("Doba", "Wersja ${BuildConfig.VERSION_NAME}") { }
        }
    }

    if (stepGoalDialog) StepGoalDialog { stepGoalDialog = false; stepGoalTick++ }

    summaryText?.let { text ->
        AlertDialog(
            onDismissRequest = { summaryText = null },
            title = { Text("Twój tydzień") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { summaryText = null }) { Text("OK") } },
        )
    }

    confirmRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Przywrócić kopię?") },
            text = { Text("Wszystkie obecne wpisy, miejsca, kategorie i cele zostaną zastąpione zawartością pliku.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = null
                    scope.launch { toast(Backup.importFrom(ctx, uri)) }
                }) { Text("Przywróć", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Anuluj") } },
        )
    }
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface),
        content = content,
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NavRow(
    title: String,
    subtitle: String?,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------- Kategorie ----------

@Composable
fun CategoriesScreen(onBack: () -> Unit) {
    val repo = LifeApp.instance.repo
    val categories by repo.categories.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<Category?>(null) }
    var adding by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Kategorie", onBack = onBack) {
            IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Dodaj kategorię") }
        }
        val scope = rememberCoroutineScope()
        val ctx = LocalContext.current
        TextButton(
            onClick = {
                scope.launch {
                    applyLifeCyclePalette(categories)
                    Toast.makeText(ctx, "Kolory i ikony w stylu Life Cycle", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.padding(horizontal = 12.dp),
        ) { Text("Zastosuj kolory i ikony w stylu Life Cycle") }
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(categories, key = { it.id }) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { editing = c }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CategoryBadge(c, 36.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.bodyLarge)
                        if (c.kind in Kind.required) {
                            Text(
                                "używana przez automatyczne śledzenie",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { CategoryEditorDialog(it, onDismiss = { editing = null }) }
    if (adding) CategoryEditorDialog(null, onDismiss = { adding = false })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryEditorDialog(category: Category?, onDismiss: () -> Unit) {
    val repo = LifeApp.instance.repo
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(category?.name ?: "") }
    var color by remember { mutableStateOf(category?.color ?: CategoryPalette[1]) }
    var icon by remember { mutableStateOf(category?.icon ?: "home") }
    var confirmDelete by remember { mutableStateOf(false) }
    val deletable = category != null && category.kind !in Kind.required

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(0.94f),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryBadge(Category(name = name, color = color, icon = icon), 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (category == null) "Nowa kategoria" else "Edytuj kategorię",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Nazwa") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text("Kolor", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CategoryPalette.forEach { c ->
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(Color(c))
                                .then(
                                    if (c == color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    else Modifier
                                )
                                .clickable { color = c }
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Ikona", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CatIcons.all.forEach { (key, vec) ->
                        val sel = key == icon
                        Box(
                            Modifier.size(40.dp).clip(CircleShape)
                                .background(if (sel) Color(color) else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { icon = key },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(vec, null, tint = if (sel) Color.White else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (deletable) {
                        TextButton(onClick = { confirmDelete = true }) {
                            Text("Usuń", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Anuluj") }
                    Button(enabled = name.isNotBlank(), onClick = {
                        scope.launch {
                            val c = category?.copy(name = name.trim(), color = color, icon = icon)
                                ?: Category(name = name.trim(), color = color, icon = icon)
                            repo.saveCategory(c)
                            onDismiss()
                        }
                    }) { Text("Zapisz") }
                }
            }
        }
    }

    if (confirmDelete && category != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Usunąć kategorię?") },
            text = { Text("Wpisy i miejsca z tą kategorią zostaną oznaczone jako „Nieznane”.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repo.deleteCategory(category)
                        confirmDelete = false
                        onDismiss()
                    }
                }) { Text("Usuń", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Anuluj") } },
        )
    }
}


/** Paleta zbliżona do oryginalnej aplikacji — dla kategorii domyślnych (po rodzaju lub nazwie). */
private suspend fun applyLifeCyclePalette(categories: List<Category>) {
    val repo = LifeApp.instance.repo
    val byKind = mapOf(
        Kind.SLEEP to (0xFF2F5D7C to "moon"),
        Kind.HOME to (0xFF9B74B8 to "home"),
        Kind.WORK to (0xFFEFC020 to "work"),
        Kind.TRAVEL to (0xFF8F928B to "car"),
        Kind.WALK to (0xFFF64B7D to "walk"),
        Kind.RUN to (0xFFE8573F to "run"),
        Kind.BIKE to (0xFF4FA3A5 to "bike"),
        Kind.WORKOUT to (0xFFF4B3C6 to "heart"),
        Kind.UNKNOWN to (0xFFB8B8BD to "unknown"),
    )
    val byName = mapOf(
        "Jedzenie" to (0xFFE8915A to "food"),
        "Zakupy" to (0xFF7FB069 to "bag"),
        "Znajomi" to (0xFFD1789C to "friends"),
        "Rozrywka" to (0xFF6F6874 to "fun"),
        "Nauka" to (0xFF4A90C2 to "study"),
    )
    for (c in categories) {
        val (color, icon) = byKind[c.kind] ?: byName[c.name] ?: continue
        repo.saveCategory(c.copy(color = color.toInt(), icon = icon))
    }
}
