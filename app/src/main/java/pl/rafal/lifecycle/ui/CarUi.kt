package pl.rafal.lifecycle.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.domain.FieldWork
import pl.rafal.lifecycle.tracking.CarBluetooth
import pl.rafal.lifecycle.util.Fmt
import pl.rafal.lifecycle.util.Prefs
import java.util.Locale

/** Ustawienia: które urządzenia Bluetooth to samochód. */
@Composable
fun CarSettings() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var tick by remember { mutableIntStateOf(0) }
    var chosen by remember { mutableStateOf(prefs.carDevices) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val devices = remember(tick) { CarBluetooth.bonded(ctx) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text("Samochód (Bluetooth)", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Zaznacz zestaw głośnomówiący lub radio auta. Połączenie = jazda za kierownicą (pewna kilometrówka), " +
                "rozłączenie = zapamiętane miejsce parkowania.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!CarBluetooth.permitted(ctx) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            OutlinedButton(onClick = { perm.launch(android.Manifest.permission.BLUETOOTH_CONNECT) }) {
                Text("Pozwól odczytać urządzenia Bluetooth")
            }
        } else if (devices.isEmpty()) {
            Text("Brak sparowanych urządzeń Bluetooth.", style = MaterialTheme.typography.bodySmall)
        } else {
            devices.take(12).forEach { (addr, name) ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        chosen = if (addr in chosen) chosen - addr else chosen + addr
                        prefs.carDevices = chosen
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(addr in chosen, { on ->
                        chosen = if (on) chosen + addr else chosen - addr
                        prefs.carDevices = chosen
                    })
                    Text(name)
                }
            }
        }
    }
}

/** „Gdzie zaparkowałem?” — karta na ekranie dziś, gdy auto stoi zaparkowane. */
@Composable
fun ParkingCard() {
    val ctx = LocalContext.current
    if (remember { Prefs(ctx).carDevices.isEmpty() }) return
    val last by remember { LifeApp.instance.db.cars().observeLastParked() }.collectAsState(initial = null)
    val s = last ?: return
    val end = s.endAt ?: return
    val lat = s.parkLat ?: return
    val lng = s.parkLng ?: return
    if (System.currentTimeMillis() - end > 3 * 86_400_000L) return
    val addr by produceState<String?>(null, lat, lng) { value = FieldWork.address(ctx, lat, lng) }
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🅿️", fontSize = 26.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Auto zaparkowane", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                (addr ?: String.format(Locale.US, "%.5f, %.5f", lat, lng)) + " · od ${Fmt.dateTime(end)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = {
            val uri = Uri.parse(String.format(Locale.US, "google.navigation:q=%.6f,%.6f&mode=w", lat, lng))
            val nav = Intent(Intent.ACTION_VIEW, uri)
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(Auto)", lat, lng, lat, lng)))
            try { ctx.startActivity(nav) } catch (_: Exception) {
                try { ctx.startActivity(fallback) } catch (_: Exception) {}
            }
        }) { Text("Prowadź") }
    }
}
