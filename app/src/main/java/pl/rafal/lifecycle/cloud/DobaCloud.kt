package pl.rafal.lifecycle.cloud

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import pl.rafal.koofr.KoofrApp
import pl.rafal.koofr.KoofrExchange
import pl.rafal.koofr.KoofrSharing
import pl.rafal.koofr.KoofrSync
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.domain.Backup
import pl.rafal.lifecycle.util.Fmt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Kopia Doby na rodzinnym Koofr (ta sama treść co „Zapisz kopię do pliku”) i podgląd doby za zgodą. */
object DobaCloud : KoofrApp {
    override val appId = "doba"
    override val appName = "Doba"
    override val contentNote = "W kopii są wpisy, miejsca, kategorie, cele, kroki, dane z innych aplikacji i czas ekranu."

    override suspend fun export(ctx: Context): Pair<ByteArray, List<KoofrSync.Blob>> =
        Backup.toJson().toByteArray(Charsets.UTF_8) to emptyList()

    override suspend fun import(ctx: Context, r: KoofrSync.Restored, progress: (String) -> Unit) {
        progress("Zapisywanie…")
        try {
            Backup.restore(String(r.data, Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            throw pl.rafal.koofr.KsException(e.message ?: "To nie jest kopia Doby")
        }
    }

    override val sharing: KoofrSharing = DobaSharing

    override val exchange: KoofrExchange = object : KoofrExchange {
        override val sources = listOf(
            "rytm-snu" to "Rytm Snu (wiatry w nocy)",
            "kreska" to "Kreska (udostępnione liczniki)",
            "polar-ekg" to "Polar EKG (epizody AF)",
        )

        override suspend fun import(ctx: Context, name: String, data: ByteArray) {
            pl.rafal.lifecycle.domain.ExchangeImport.importText(String(data, Charsets.UTF_8))
        }
    }
}

object DobaSharing : KoofrSharing {
    override val whatMine = "Twoją dobę"
    override val whatTheirs = "dobę tej osoby"
    override val alwaysIncluded = "Oś czasu z kategoriami (bez współrzędnych)"
    override val options = listOf(
        "places" to "Nazwy miejsc",
        "notes" to "Notatki przy wpisach",
        "steps" to "Kroki dziennie",
    )
    override val defaults: Map<String, Any?> = mapOf("days" to 7L, "places" to false, "notes" to false, "steps" to false)

    override suspend fun build(ctx: Context, scope: Map<String, Any?>): ByteArray = withContext(Dispatchers.IO) {
        val db = LifeApp.instance.db
        val days = (scope["days"] as? Number)?.toLong() ?: 7L
        val from = LocalDate.now().minusDays(days - 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()
        val cats = db.categories().getAll()
        val places = if (scope["places"] == true) db.places().getAll().associateBy { it.id } else emptyMap()
        val entries = db.entries().getRange(from, now)
        val root = JSONObject()
            .put("format", "doba-podglad").put("v", 1).put("generatedAt", now).put("from", from)
        root.put("categories", JSONArray().apply {
            cats.forEach { c -> put(JSONObject().put("id", c.id).put("name", c.name).put("color", c.color)) }
        })
        root.put("entries", JSONArray().apply {
            entries.forEach { e ->
                val o = JSONObject().put("s", maxOf(e.startAt, from)).put("e", e.endAt ?: now).put("c", e.categoryId)
                if (e.endAt == null) o.put("open", true)
                e.placeId?.let { places[it] }?.let { o.put("p", it.name) }
                if (scope["notes"] == true && !e.note.isNullOrBlank()) o.put("n", e.note)
                put(o)
            }
        })
        if (scope["steps"] == true) {
            val perDay = sortedMapOf<String, Long>()
            db.steps().getRange(from, now).groupBy { it.hourStart }.forEach { (h, list) ->
                // z kilku źródeł dla tej samej godziny bierzemy największe
                val d = Instant.ofEpochMilli(h).atZone(ZoneId.systemDefault()).toLocalDate().toString()
                perDay[d] = (perDay[d] ?: 0L) + list.maxOf { it.steps }
            }
            root.put("steps", JSONObject(perDay as Map<*, *>))
        }
        root.toString().toByteArray(Charsets.UTF_8)
    }

    private class Cat(val name: String, val color: Color)
    private class Seg(val s: Long, val e: Long, val cat: Long, val place: String?, val note: String?, val open: Boolean)
    private class Day(val date: LocalDate, val segs: List<Seg>, val steps: Long?)

    private fun parse(data: ByteArray): Triple<Map<Long, Cat>, List<Day>, Long> {
        val j = JSONObject(String(data, Charsets.UTF_8))
        if (j.optString("format") != "doba-podglad") throw IllegalArgumentException("Nieznany format danych")
        val cats = HashMap<Long, Cat>()
        j.optJSONArray("categories")?.let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let { c -> cats[c.getLong("id")] = Cat(c.getString("name"), Color(c.getInt("color"))) }
        }
        val zone = ZoneId.systemDefault()
        val byDay = sortedMapOf<LocalDate, MutableList<Seg>>(compareByDescending { it })
        j.optJSONArray("entries")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                var s = o.getLong("s")
                val e = o.getLong("e")
                // dzielimy wpisy przechodzące przez północ
                while (s < e) {
                    val d = Instant.ofEpochMilli(s).atZone(zone).toLocalDate()
                    val dayEnd = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    val end = minOf(e, dayEnd)
                    byDay.getOrPut(d) { mutableListOf() } += Seg(s, end, o.getLong("c"), o.optString("p").ifEmpty { null },
                        o.optString("n").ifEmpty { null }, o.optBoolean("open"))
                    s = end
                }
            }
        }
        val steps = j.optJSONObject("steps")
        val from = j.optLong("from")
        val days = byDay.filterKeys { from == 0L || !it.isBefore(Instant.ofEpochMilli(from).atZone(zone).toLocalDate()) }
            .map { (d, l) -> Day(d, l.sortedBy { it.s }, steps?.optLong(d.toString(), -1)?.takeIf { it >= 0 }) }
        return Triple(cats, days, j.optLong("generatedAt"))
    }

    @Composable
    override fun Viewer(ownerName: String, data: ByteArray, updatedAt: Long?, onBack: () -> Unit) {
        val parsed = remember(data) { runCatching { parse(data) } }
        var open by remember { mutableStateOf<LocalDate?>(null) }
        BackHandler(enabled = open != null) { open = null }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(4.dp, 12.dp, 8.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (open != null) open = null else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Wstecz") }
                Text("Doba: $ownerName", style = MaterialTheme.typography.headlineSmall)
            }
            val r = parsed.getOrNull()
            if (r == null) {
                Text(parsed.exceptionOrNull()?.message ?: "Błąd danych", Modifier.padding(20.dp))
                return@Column
            }
            val (cats, days, gen) = r
            if (gen > 0) Text("Aktualizacja: ${Fmt.dateTime(gen)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
            val sel = days.firstOrNull { it.date == open }
            if (sel != null) {
                DayDetail(sel, cats)
            } else if (days.isEmpty()) {
                Text("Brak wpisów w udostępnionym okresie.", Modifier.padding(20.dp))
            } else {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(days, key = { it.date.toString() }) { d -> DayCard(d, cats) { open = d.date } }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    private val dayF = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale("pl"))

    @Composable
    private fun DayCard(d: Day, cats: Map<Long, Cat>, onClick: () -> Unit) {
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.date.format(dayF).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                d.steps?.let { Text("${Fmt.steps(it)} kroków", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.height(8.dp))
            DayBar(d, cats)
            Spacer(Modifier.height(8.dp))
            val totals = d.segs.groupBy { it.cat }.mapValues { (_, l) -> l.sumOf { it.e - it.s } }.entries.sortedByDescending { it.value }.take(4)
            totals.forEach { (c, ms) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(cats[c]?.color ?: Color.Gray))
                    Text(cats[c]?.name ?: "?", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 6.dp).weight(1f))
                    Text(Fmt.duration(ms), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    @Composable
    private fun DayBar(d: Day, cats: Map<Long, Cat>) {
        val zone = ZoneId.systemDefault()
        val start = d.date.atStartOfDay(zone).toInstant().toEpochMilli()
        val len = d.date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - start
        val track = MaterialTheme.colorScheme.surfaceVariant
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            drawRoundRect(track, cornerRadius = CornerRadius(7.dp.toPx()))
            d.segs.forEach { s ->
                val x0 = (s.s - start).toFloat() / len * size.width
                val x1 = (s.e - start).toFloat() / len * size.width
                drawRect(cats[s.cat]?.color ?: Color.Gray, topLeft = Offset(x0, 0f), size = Size(maxOf(1f, x1 - x0), size.height))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("0", "6", "12", "18", "24").forEachIndexed { i, t ->
                Text(t, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (i < 4) Spacer(Modifier.weight(1f))
            }
        }
    }

    @Composable
    private fun DayDetail(d: Day, cats: Map<Long, Cat>) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text(d.date.format(dayF).replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(20.dp, 8.dp))
                Box(Modifier.padding(horizontal = 16.dp)) { Column { DayBar(d, cats) } }
                Spacer(Modifier.height(8.dp))
            }
            items(d.segs) { s ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(cats[s.cat]?.color ?: Color.Gray))
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(cats[s.cat]?.name ?: "?", fontWeight = FontWeight.Medium)
                        val sub = listOfNotNull(s.place, s.note).joinToString(" · ")
                        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${Fmt.time(s.s)}–${if (s.open) "teraz" else Fmt.time(s.e)}", style = MaterialTheme.typography.bodySmall)
                        Text(Fmt.duration(s.e - s.s), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Spacer(Modifier.width(1.dp).height(24.dp)) }
        }
    }
}
