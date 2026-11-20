package pl.rafal.lifecycle.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.rafal.lifecycle.domain.MyHealthDay
import pl.rafal.lifecycle.domain.MyHealthTrend
import pl.rafal.lifecycle.util.Fmt
import java.util.Locale
import kotlin.math.abs

private val PL = Locale.forLanguageTag("pl-PL")
private fun f1(v: Double) = String.format(PL, "%.1f", v)
private fun f0(v: Double) = String.format(PL, "%.0f", v)

/** Jeden dzień jednym zdaniem: tylko to, co jest. */
fun MyHealthDay.summary(): String = listOfNotNull(
    sleepMin?.let { "sen ${it.toInt() / 60} h ${(it.toInt() % 60).toString().padStart(2, '0')}" + (sleepScore?.let { s -> " (${f0(s)})" } ?: "") },
    (cpapAhi?.let { "AHI ${f1(it)} (CPAP)" } ?: apnea?.let { "oddech ${f1(it)}/h" }),
    if (afEpisodes > 0) "AF $afEpisodes× (${f0(afMin)} min)" else null,
    hrv?.let { "HRV ${f0(it)} ms" },
    medsPlanned?.let { "leki ${f0(medsTaken ?: 0.0)}/${f0(it)}" },
    kcal?.let { "${f0(it)} kcal" },
    alcohol?.takeIf { it > 0 }?.let { "alkohol" },
    coffee?.takeIf { it > 0 }?.let { "kawa ${f0(it)}" },
    trainMin?.let { "trening ${f0(it)} min" },
).joinToString(" · ")

/** Karta „Moje zdrowie” na górze ekranu Zdrowie: tydzień do tygodnia i ostatnie dni. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MyHealthCard(days: List<MyHealthDay>, trends: List<MyHealthTrend>) {
    var expanded by remember { mutableStateOf(false) }
    val withData = days.filter { it.hasData }
    if (withData.isEmpty()) return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface).padding(16.dp)
    ) {
        Text("Moje zdrowie", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("7 dni wobec poprzednich 7 · z Rytmu Snu, Polar EKG, Teczki, Dziennika talerza i Formy",
            style = MaterialTheme.typography.labelSmall, color = muted)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in trends) TrendChip(t)
        }
        Spacer(Modifier.height(12.dp))
        val shown = if (expanded) withData else withData.take(3)
        for (d in shown) {
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                Text(Fmt.dayTitle(d.day), Modifier.width(92.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Text(d.summary(), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (withData.size > 3) {
            Text(if (expanded) "Zwiń" else "Pokaż ${withData.size - 3} kolejnych dni",
                Modifier.clickable { expanded = !expanded }.padding(top = 6.dp),
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TrendChip(t: MyHealthTrend) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val v = if (abs(t.now) >= 20) f0(t.now) else f1(t.now)
    val delta = t.before?.let { t.now - it }
    val good = delta?.let { if (abs(it) < 1e-9) null else (it > 0) == t.higherBetter }
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(t.label, style = MaterialTheme.typography.labelSmall, color = muted)
        Text("$v${t.unit}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        if (delta != null) {
            val d = if (abs(delta) >= 20) f0(abs(delta)) else f1(abs(delta))
            Text((if (delta >= 0) "+" else "−") + d + t.unit,
                style = MaterialTheme.typography.labelSmall,
                color = when (good) { true -> MaterialTheme.colorScheme.primary; false -> MaterialTheme.colorScheme.error; null -> muted })
        }
    }
}
