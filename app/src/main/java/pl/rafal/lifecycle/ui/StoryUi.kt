package pl.rafal.lifecycle.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.rafal.lifecycle.domain.DayStory
import pl.rafal.lifecycle.util.Fmt
import java.time.LocalDate

private fun shareText(ctx: android.content.Context, title: String, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(i, title))
}

/** „Twój dzień” — opis dnia na ekranie dnia. */
@Composable
fun DayStoryCard(day: LocalDate, refreshKey: Any?, onDiary: () -> Unit) {
    val ctx = LocalContext.current
    val text by produceState<String?>(null, day, refreshKey) { value = DayStory.build(ctx, day) }
    val t = text ?: return
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface).padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 4.dp)
    ) {
        Text("Twój dzień", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(t, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp, end = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { shareText(ctx, Fmt.dayTitle(day), "${Fmt.dayTitle(day)}\n$t") }) { Text("Udostępnij") }
            TextButton(onClick = onDiary) { Text("Pamiętnik") }
        }
    }
}

/** Pamiętnik: opisy kolejnych dni. */
@Composable
fun DiaryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val days by produceState<List<Pair<LocalDate, String>>?>(null) {
        val out = mutableListOf<Pair<LocalDate, String>>()
        var d = LocalDate.now()
        repeat(30) {
            DayStory.build(ctx, d)?.let { out += d to it }
            value = out.toList()
            d = d.minusDays(1)
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            ScreenHeader("Pamiętnik", onBack = onBack) {
                val l = days
                if (!l.isNullOrEmpty()) {
                    TextButton(onClick = {
                        shareText(ctx, "Pamiętnik z Doby", l.joinToString("\n\n") { (d, t) -> "${Fmt.dayTitle(d)}\n$t" })
                    }) { Text("Eksportuj") }
                }
            }
        }
        val l = days
        if (l == null) item { Text("Piszę…", Modifier.padding(20.dp)) }
        else {
            if (l.isEmpty()) item { Text("Brak danych z ostatnich dni.", Modifier.padding(20.dp)) }
            items(l, key = { it.first.toEpochDay() }) { (d, t) ->
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(Fmt.dayTitle(d), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(t, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
