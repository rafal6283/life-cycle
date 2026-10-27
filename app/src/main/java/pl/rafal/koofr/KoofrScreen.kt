@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package pl.rafal.koofr

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun fmtDate(t: Long) = SimpleDateFormat("d.MM.yyyy, HH:mm", Locale("pl")).format(Date(t))

/** Ekran „Kopia i rodzina (Koofr)”: konto, osoba, kopia, przywracanie i (opcjonalnie) podgląd za zgodą. */
@Composable
fun KoofrScreen(onBack: () -> Unit) {
    val sync = Koofr.sync
    val app = Koofr.app
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ver by Koofr.version.collectAsState()
    val busyGlobal by Koofr.busy.collectAsState()
    val progress by Koofr.progress.collectAsState()
    val lastError by Koofr.lastError.collectAsState()

    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var members by remember { mutableStateOf<List<KoofrSync.Member>>(emptyList()) }
    var links by remember { mutableStateOf<List<KoofrSync.ShareLink>>(emptyList()) }
    var consents by remember { mutableStateOf<Map<String, Map<String, Any>>>(emptyMap()) }
    var refreshKey by remember { mutableStateOf(0) }
    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    var viewing by remember { mutableStateOf<Pair<KoofrSync.Member, Pair<ByteArray, Long?>>?>(null) }

    fun toast(t: String) = Toast.makeText(ctx, t, Toast.LENGTH_LONG).show()

    fun act(ok: String? = null, refresh: Boolean = true, f: suspend () -> Unit) {
        scope.launch {
            working = true
            try {
                f()
                if (ok != null) toast(ok)
                if (refresh) refreshKey++
            } catch (e: Exception) {
                toast(e.message ?: e.toString())
            } finally {
                working = false
            }
        }
    }

    LaunchedEffect(refreshKey, ver, sync.connected(), sync.ready()) {
        error = null
        try {
            withContext(Dispatchers.IO) {
                if (sync.connected()) members = sync.members()
                if (sync.ready() && app.sharing != null) {
                    links = sync.links()
                    consents = sync.myConsents()
                }
            }
        } catch (e: Exception) {
            error = e.message
        }
    }

    viewing?.let { (m, d) ->
        BackHandler { viewing = null }
        app.sharing!!.Viewer(m.name, d.first, d.second) { viewing = null }
        return
    }

    dialog?.invoke()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(4.dp, 12.dp, 8.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Wstecz") }
            Text("Kopia i rodzina", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (sync.connected()) IconButton(onClick = { refreshKey++ }, enabled = !working) { Icon(Icons.Filled.Refresh, "Odśwież") }
        }
        if (working || busyGlobal) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { ErrorCard(it) }
        run {
            when {
                !sync.connected() -> ConnectForm(app.appName, working) { c -> act("Połączono z Koofr") { withContext(Dispatchers.IO) { sync.connect(c) } } }
                !sync.ready() -> JoinView(members, working,
                    onExisting = { m ->
                        dialog = {
                            CodeEntryDialog(m.name, onDismiss = { dialog = null }) { code ->
                                dialog = null
                                act("Witaj, ${m.name}!") { withContext(Dispatchers.IO) { sync.join(m.id, null, code) } }
                            }
                        }
                    },
                    onNew = {
                        dialog = {
                            NameDialog(onDismiss = { dialog = null }) { name ->
                                if (members.any { it.name.equals(name, true) }) {
                                    dialog = null
                                    toast("„$name” już jest na liście — wybierz tę osobę i wpisz jej kod.")
                                } else {
                                    val code = RecoveryCode.generate()
                                    dialog = {
                                        CodeDialog(
                                            "Kod odzyskiwania: $name", code.format(),
                                            "Zapisz go (np. w menedżerze haseł albo na kartce). Bez niego nie odtworzysz kopii po zgubieniu " +
                                                "telefonu i nie podłączysz innych aplikacji jako ta sama osoba. Nikt — także Koofr — nie odzyska go za Ciebie.",
                                            requireConfirm = true, onDismiss = { dialog = null },
                                        ) {
                                            dialog = null
                                            act("Witaj, $name!") { withContext(Dispatchers.IO) { sync.join(null, name, code) } }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    onDisconnect = { act("Odłączono") { withContext(Dispatchers.IO) { sync.disconnect() } } },
                )
                else -> {
                    val me = members.firstOrNull { it.id == sync.userId }
                    SectionHead("Konto")
                    Card {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(me?.name ?: "?")
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(me?.name ?: sync.userId ?: "", style = MaterialTheme.typography.titleMedium)
                                Muted("${sync.config.email} · folder „${sync.config.root}”")
                            }
                        }
                        Div()
                        RowItem("Kod konfiguracji dla innego telefonu", "Wklejasz go w innej aplikacji zamiast e-maila i hasła") {
                            dialog = {
                                CodeDialog("Kod konfiguracji", sync.config.toCode(),
                                    "Zawiera hasło aplikacji Koofr — przekaż go tylko domownikom.", onDismiss = { dialog = null }) { dialog = null }
                            }
                        }
                        Div()
                        RowItem("Mój kod odzyskiwania", null) {
                            dialog = {
                                CodeDialog("Kod odzyskiwania", sync.recoveryCode ?: "",
                                    "Ten sam kod wpisujesz w innych rodzinnych aplikacjach i na nowym telefonie.", onDismiss = { dialog = null }) { dialog = null }
                            }
                        }
                        Div()
                        RowItem("Odłącz ten telefon", "Kopie na Koofr zostają" + if (app.sharing != null) "; zgody z tego telefonu zostaną cofnięte" else "",
                            danger = true) {
                            dialog = {
                                Confirm("Odłączyć ten telefon?", "Żeby podłączyć się ponownie, będzie potrzebny kod odzyskiwania.", "Odłącz",
                                    onDismiss = { dialog = null }) {
                                    dialog = null
                                    act("Telefon odłączony") { withContext(Dispatchers.IO) { sync.disconnect() } }
                                }
                            }
                        }
                    }

                    SectionHead("Kopia zapasowa")
                    lastError?.let { ErrorCard("Ostatnia automatyczna praca: $it") }
                    Card {
                        SwitchItem("Automatyczna kopia", "Raz na dobę, w tle", sync.backupEnabled()) { v ->
                            act { withContext(Dispatchers.IO) { sync.setBackupEnabled(v) }; if (v) Koofr.autoRunAsync() }
                        }
                        Div()
                        SwitchItem("Tylko przez Wi-Fi", "Dotyczy pracy automatycznej", Koofr.wifiOnly()) { v -> Koofr.setWifiOnly(v) }
                        Div()
                        val last = sync.lastBackup()
                        RowItem("Zrób kopię teraz", progress ?: if (last == null) "Jeszcze nie było kopii z tego telefonu" else "Ostatnia: ${fmtDate(last)}") {
                            if (!working) act("Kopia zapisana na Koofr") { Koofr.backupNow() }
                        }
                        Div()
                        RowItem("Przywróć z kopii", "Wybierz jedną z zapisanych kopii") {
                            if (!working) act(refresh = false) {
                                val list = withContext(Dispatchers.IO) { sync.backups() }
                                if (list.isEmpty()) toast("Na Koofr nie ma jeszcze Twoich kopii.")
                                else dialog = {
                                    BackupPicker(list, sync.deviceId, onDismiss = { dialog = null }) { b ->
                                        dialog = {
                                            Confirm("Przywrócić tę kopię?", "Kopia z ${fmtDate(b.at)}.\n\n${app.restoreWarning}", "Przywróć",
                                                onDismiss = { dialog = null }) {
                                                dialog = null
                                                act("Przywrócono kopię") { Koofr.restore(b) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Muted("${app.contentNote} Wszystko jest szyfrowane na telefonie. Zostaje 7 ostatnich dni i po jednej kopii z 4 wcześniejszych tygodni.",
                            Modifier.padding(16.dp, 4.dp, 16.dp, 14.dp))
                    }

                    app.sharing?.let { sh ->
                        SharingSection(sh, members.filter { it.id != sync.userId }, links, consents, working,
                            act = { ok, f -> act(ok) { f() } },
                            showDialog = { dialog = it },
                            closeDialog = { dialog = null },
                            open = { m -> act(refresh = false) {
                                val r = withContext(Dispatchers.IO) { sync.fetchShared(m.id) }
                                if (r == null) toast("${m.name} nie wysłał(a) jeszcze danych — pojawią się po otwarciu aplikacji na tamtym telefonie.")
                                else viewing = m to ((r[0] as ByteArray) to (r[1] as Long?))
                            } })
                    }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

// ------------------------------------------------------------ podgląd

@Composable
private fun SharingSection(
    sh: KoofrSharing,
    others: List<KoofrSync.Member>,
    links: List<KoofrSync.ShareLink>,
    consents: Map<String, Map<String, Any>>,
    working: Boolean,
    act: (String?, suspend () -> Unit) -> Unit,
    showDialog: (@Composable () -> Unit) -> Unit,
    closeDialog: () -> Unit,
    open: (KoofrSync.Member) -> Unit,
) {
    val sync = Koofr.sync
    val me = sync.userId ?: return
    val appId = sync.appId
    fun link(o: String, v: String) = links.firstOrNull { it.owner == o && it.viewer == v }

    SectionHead("Podgląd")
    Card {
        if (others.isEmpty()) {
            RowItem("Nikt inny jeszcze się nie podłączył", "Gdy domownicy podłączą swoje aplikacje do tego konta, pojawią się tutaj.") {}
        }
        others.forEachIndexed { i, m ->
            if (i > 0) Div()
            val mine = link(me, m.id)
            val theirs = link(m.id, me)
            val parts = mutableListOf<String>()
            if (!m.keys.containsKey(appId)) parts += "nie używa jeszcze tej aplikacji"
            mine?.let { parts += if (it.active()) "widzi ${sh.whatMine.lowercase()}" else if (it.ownerAgreed) "czeka na przyjęcie" else "prosi o podgląd" }
            theirs?.let { parts += if (it.active()) "widzisz ${sh.whatTheirs}" else if (it.viewerAgreed) "czekasz na zgodę" else "chce Ci pokazać swoje dane" }
            Row(Modifier.fillMaxWidth().clickable {
                showDialog {
                    MemberDialog(sh, m, mine, theirs, consents.containsKey(m.id), onDismiss = { closeDialog() }, act = { ok, f ->
                        closeDialog()
                        act(ok, f)
                    }, pickScope = { initial, onPicked ->
                        showDialog { ScopeDialog(sh, m.name, initial, onDismiss = { closeDialog() }) { s -> closeDialog(); onPicked(s) } }
                    }, open = { closeDialog(); open(m) })
                }
            }.padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(m.name)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(m.name, style = MaterialTheme.typography.bodyLarge)
                    Muted(if (parts.isEmpty()) "Brak podglądu" else parts.joinToString(" · "))
                }
            }
        }
        if (consents.isNotEmpty()) {
            Div()
            RowItem("Wyślij podgląd teraz", "Automatycznie: w tle co kilka godzin") {
                if (!working) act("Wysłano") { Koofr.publish() }
            }
        }
    }
    Muted("Podgląd działa tylko za zgodą obu osób i każda może go w dowolnej chwili wyłączyć. Dane są szyfrowane na telefonie " +
        "kluczem osoby oglądającej — nikt inny, także właściciel konta Koofr, ich nie odczyta.", Modifier.padding(20.dp, 8.dp))
}

@Composable
private fun MemberDialog(
    sh: KoofrSharing,
    m: KoofrSync.Member,
    mine: KoofrSync.ShareLink?,
    theirs: KoofrSync.ShareLink?,
    mineHere: Boolean,
    onDismiss: () -> Unit,
    act: (String?, suspend () -> Unit) -> Unit,
    pickScope: (Map<String, Any?>, (Map<String, Any?>) -> Unit) -> Unit,
    open: () -> Unit,
) {
    val sync = Koofr.sync
    var safety by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(m.id) { safety = try { withContext(Dispatchers.IO) { sync.safetyNumberWith(m.id) } } catch (e: Exception) { null } }
    fun allow(scope: Map<String, Any?>, ok: String) = act(ok) {
        withContext(Dispatchers.IO) { sync.allowView(m.id, LinkedHashMap(scope)) }
        Koofr.publish()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(m.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!m.keys.containsKey(sync.appId)) {
                    Text("Ta osoba nie podłączyła jeszcze tej aplikacji do Koofr. Gdy to zrobi, będzie można ustawić podgląd.")
                } else {
                    Label("${sh.whatMine.uppercase()} DLA TEJ OSOBY")
                    when {
                        mine == null -> {
                            Text("Nie udostępniasz.")
                            FilledTonalButton(onClick = { pickScope(sh.defaults) { allow(it, "Wysłano propozycję — ${m.name} musi ją przyjąć") } }) { Text("Udostępnij") }
                        }
                        !mine.ownerAgreed -> {
                            Text("${m.name} prosi o podgląd.")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = { pickScope(sh.defaults) { allow(it, "${m.name} widzi teraz Twoje dane") } }) { Text("Zgódź się") }
                                OutlinedButton(onClick = { act("Odrzucono") { withContext(Dispatchers.IO) { sync.dismissLink(sync.userId, m.id) } } }) { Text("Odrzuć") }
                            }
                        }
                        else -> {
                            Text(if (mine.active()) "${m.name} widzi ${sh.whatMine.lowercase()}." else "Czekasz, aż ${m.name} przyjmie podgląd.")
                            if (!mineHere) Muted("Zgoda udzielona z innego telefonu — stamtąd są wysyłane dane.")
                            Muted(scopeText(sh, mine.scope))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (mineHere) OutlinedButton(onClick = { pickScope(sh.defaults + mine.scope) { allow(it, "Zmieniono zakres") } }) { Text("Zmień zakres") }
                                OutlinedButton(onClick = { act("Wyłączono podgląd") { withContext(Dispatchers.IO) { sync.revokeAsOwner(m.id) } } }) {
                                    Text("Wyłącz", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Label("${sh.whatTheirs.uppercase()} DLA MNIE")
                    when {
                        theirs == null -> {
                            Text("Nie masz podglądu.")
                            FilledTonalButton(onClick = { act("Wysłano prośbę do: ${m.name}") { withContext(Dispatchers.IO) { sync.requestView(m.id) } } }) {
                                Text("Poproś o podgląd")
                            }
                        }
                        !theirs.viewerAgreed -> {
                            Text("${m.name} chce Ci pokazać swoje dane.")
                            Muted(scopeText(sh, theirs.scope))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = { act("Przyjęto — dane pojawią się po wysyłce z telefonu ${m.name}") { withContext(Dispatchers.IO) { sync.requestView(m.id) } } }) { Text("Przyjmij") }
                                OutlinedButton(onClick = { act("Odrzucono") { withContext(Dispatchers.IO) { sync.dismissLink(m.id, sync.userId) } } }) { Text("Odrzuć") }
                            }
                        }
                        else -> {
                            Text(if (theirs.active()) "Widzisz ${sh.whatTheirs}." else "Czekasz na zgodę: ${m.name}.")
                            if (theirs.active()) Muted(scopeText(sh, theirs.scope))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (theirs.active()) FilledTonalButton(onClick = open) { Text("Otwórz") }
                                OutlinedButton(onClick = {
                                    act(if (theirs.active()) "Zrezygnowano z podglądu" else "Wycofano prośbę") { withContext(Dispatchers.IO) { sync.revokeAsViewer(m.id) } }
                                }) { Text(if (theirs.active()) "Zrezygnuj" else "Wycofaj prośbę", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                    safety?.let {
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        Label("KOD BEZPIECZEŃSTWA")
                        Text(it, fontFamily = FontFamily.Monospace, fontSize = 18.sp)
                        Muted("Na telefonie drugiej osoby powinien być identyczny. Jeśli się różni, ktoś podmienił klucz — nie włączajcie podglądu.")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zamknij") } },
    )
}

private fun scopeText(sh: KoofrSharing, s: Map<String, Any?>): String {
    val days = (s["days"] as? Number)?.toInt()
    val items = sh.options.filter { s[it.first] == true }.map { it.second.lowercase() }
    return "Zakres: " + (if (days != null) "ostatnie $days dni, " else "") + sh.alwaysIncluded.lowercase() +
        (if (items.isEmpty()) "" else ", " + items.joinToString(", ")) + "."
}

@Composable
private fun ScopeDialog(sh: KoofrSharing, name: String, initial: Map<String, Any?>, onDismiss: () -> Unit, onOk: (Map<String, Any?>) -> Unit) {
    var s by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Co zobaczy $name?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Muted("Zawsze: ${sh.alwaysIncluded.lowercase()}.")
                Spacer(Modifier.height(8.dp))
                Text("Okres", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sh.dayOptions.forEach { d ->
                        FilterChip(selected = (s["days"] as? Number)?.toInt() == d, onClick = { s = s + ("days" to d.toLong()) },
                            label = { Text(if (d >= 365) "rok" else "$d dni") })
                    }
                }
                sh.options.forEach { (k, label) ->
                    Row(Modifier.fillMaxWidth().clickable { s = s + (k to (s[k] != true)) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = s[k] == true, onCheckedChange = { s = s + (k to it) })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onOk(s) }) { Text("Zgadzam się") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

// ------------------------------------------------------------ połączenie i osoba

@Composable
private fun ConnectForm(appName: String, working: Boolean, onConnect: (KoofrSync.Config) -> Unit) {
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var root by remember { mutableStateOf("Rodzina") }
    var hide by remember { mutableStateOf(true) }
    var codeDialog by remember { mutableStateOf(false) }
    val clip = LocalClipboardManager.current
    Muted("Szyfrowana kopia zapasowa aplikacji $appName na wspólnym, rodzinnym koncie Koofr. Dane są szyfrowane na telefonie, " +
        "zanim trafią do Koofr. Domyślnie nic nie jest wysyłane.", Modifier.padding(20.dp, 8.dp))
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = { codeDialog = true }, enabled = !working, modifier = Modifier.fillMaxWidth()) {
                Text("Wklej kod konfiguracji z innej aplikacji")
            }
            Muted("albo wpisz ręcznie:")
            OutlinedTextField(email, { email = it }, label = { Text("E-mail konta Koofr") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it }, label = { Text("Hasło aplikacji") }, singleLine = true,
                visualTransformation = if (hide) PasswordVisualTransformation() else VisualTransformation.None,
                trailingIcon = { TextButton(onClick = { hide = !hide }) { Text(if (hide) "Pokaż" else "Ukryj") } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            Muted("Utwórz je na app.koofr.net w ustawieniach konta, w sekcji haseł aplikacji (nie podawaj hasła do konta).")
            OutlinedTextField(root, { root = it }, label = { Text("Folder na Koofr") }, singleLine = true,
                supportingText = { Text("Taki sam we wszystkich rodzinnych aplikacjach") }, modifier = Modifier.fillMaxWidth())
            FilledTonalButton(onClick = { if (email.isNotBlank() && pass.isNotBlank()) onConnect(KoofrSync.Config(email, pass, root)) },
                enabled = !working, modifier = Modifier.fillMaxWidth()) { Text("Połącz") }
        }
    }
    if (codeDialog) {
        var text by remember { mutableStateOf(clip.getText()?.text?.takeIf { it.startsWith("KSYNC1:") } ?: "") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { codeDialog = false },
            title = { Text("Kod konfiguracji") },
            text = {
                OutlinedTextField(text, { text = it; err = null }, placeholder = { Text("KSYNC1:…") }, isError = err != null,
                    supportingText = { err?.let { Text(it) } }, minLines = 3, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    val c = KoofrSync.Config.fromCode(text)
                    if (c == null) err = "To nie jest poprawny kod" else {
                        codeDialog = false
                        onConnect(c)
                    }
                }) { Text("Połącz") }
            },
            dismissButton = { TextButton(onClick = { codeDialog = false }) { Text("Anuluj") } },
        )
    }
}

@Composable
private fun JoinView(
    members: List<KoofrSync.Member>,
    working: Boolean,
    onExisting: (KoofrSync.Member) -> Unit,
    onNew: () -> Unit,
    onDisconnect: () -> Unit,
) {
    SectionHead("Kim jesteś?")
    Muted("Jedno konto Koofr służy całej rodzinie. Wybierz siebie z listy albo dodaj nową osobę. Każda osoba ma własny kod " +
        "odzyskiwania — ten sam we wszystkich rodzinnych aplikacjach.", Modifier.padding(20.dp, 0.dp, 20.dp, 12.dp))
    Card {
        members.forEachIndexed { i, m ->
            if (i > 0) Div()
            Row(Modifier.fillMaxWidth().clickable(enabled = !working) { onExisting(m) }.padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(m.name)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(m.name, style = MaterialTheme.typography.bodyLarge)
                    Muted(if (m.keys.containsKey(Koofr.sync.appId)) "Używa już tej aplikacji" else "Podłączona w innej aplikacji — wpisz ten sam kod")
                }
            }
        }
        if (members.isNotEmpty()) Div()
        RowItem("Nowa osoba", "Pierwszy raz w którejkolwiek z rodzinnych aplikacji") { if (!working) onNew() }
    }
    TextButton(onClick = onDisconnect, enabled = !working, modifier = Modifier.padding(12.dp)) { Text("Odłącz konto Koofr") }
}

@Composable
private fun CodeEntryDialog(name: String, onDismiss: () -> Unit, onOk: (RecoveryCode) -> Unit) {
    var text by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kod odzyskiwania: $name") },
        text = {
            Column {
                Text("Wpisz kod zapisany przy pierwszym podłączeniu tej osoby (32 znaki, myślniki dowolnie).")
                OutlinedTextField(text, { text = it; err = null }, placeholder = { Text("XXXX-XXXX-…") }, isError = err != null,
                    supportingText = { err?.let { Text(it) } },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val c = RecoveryCode.parse(text)
                if (c == null) err = "Niepełny kod albo literówka" else onOk(c)
            }) { Text("Dalej") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

@Composable
private fun NameDialog(onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nowa osoba") },
        text = {
            OutlinedTextField(text, { text = it }, placeholder = { Text("Imię, np. Rafał") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onOk(text.trim()) }) { Text("Dalej") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

@Composable
private fun CodeDialog(title: String, text: String, info: String, requireConfirm: Boolean = false, onDismiss: () -> Unit, onOk: () -> Unit) {
    var confirmed by remember { mutableStateOf(false) }
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = { if (!requireConfirm) onDismiss() },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SelectionContainer {
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 22.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp))
                }
                TextButton(onClick = {
                    clip.setText(AnnotatedString(text))
                    Toast.makeText(ctx, "Skopiowano", Toast.LENGTH_SHORT).show()
                }) { Text("Kopiuj") }
                Muted(info)
                if (requireConfirm) Row(Modifier.clickable { confirmed = !confirmed }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(confirmed, { confirmed = it })
                    Text("Zapisałem kod")
                }
            }
        },
        confirmButton = { TextButton(onClick = onOk, enabled = !requireConfirm || confirmed) { Text(if (requireConfirm) "Dalej" else "Zamknij") } },
        dismissButton = { if (requireConfirm) TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

@Composable
private fun BackupPicker(list: List<KoofrSync.BackupInfo>, deviceId: String?, onDismiss: () -> Unit, onPick: (KoofrSync.BackupInfo) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wybierz kopię") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                list.forEach { b ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(b) }.padding(vertical = 10.dp)) {
                        Text(fmtDate(b.at), style = MaterialTheme.typography.bodyLarge)
                        Muted(String.format(Locale.ROOT, "%.1f MB bez załączników · %s", b.size / 1048576.0,
                            if (b.device == deviceId) "ten telefon" else "inny telefon"))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

@Composable
private fun Confirm(title: String, text: String, ok: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onOk) { Text(ok) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

// ------------------------------------------------------------ drobne elementy

@Composable
private fun SectionHead(t: String) = Text(
    t.uppercase(Locale("pl")), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
)

@Composable
private fun Label(t: String) = Text(t, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)

@Composable
private fun Muted(t: String, modifier: Modifier = Modifier) =
    Text(t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface),
    content = content,
)

@Composable
private fun Div() = HorizontalDivider(Modifier.padding(start = 16.dp))

@Composable
private fun ErrorCard(t: String) = Text(
    t, color = MaterialTheme.colorScheme.onErrorContainer,
    modifier = Modifier.padding(16.dp, 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
)

@Composable
private fun Avatar(name: String) = androidx.compose.foundation.layout.Box(
    Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center,
) { Text(name.trim().take(1).uppercase().ifEmpty { "?" }, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold) }

@Composable
private fun RowItem(title: String, subtitle: String?, danger: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        if (subtitle != null) Muted(subtitle)
    }
}

@Composable
private fun SwitchItem(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Muted(subtitle)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
