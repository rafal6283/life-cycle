package pl.rafal.lifecycle.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.CarSession
import pl.rafal.lifecycle.util.Prefs

/**
 * Samochód po Bluetooth: połączenie z zestawem w aucie = jazda za kierownicą,
 * rozłączenie = parkowanie (zapamiętujemy miejsce).
 */
object CarBluetooth {
    fun permitted(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Sparowane urządzenia (adres, nazwa); najpierw te, które wyglądają na samochód. */
    @SuppressLint("MissingPermission")
    fun bonded(ctx: Context): List<Pair<String, String>> {
        if (!permitted(ctx)) return emptyList()
        val bm = ctx.getSystemService(BluetoothManager::class.java) ?: return emptyList()
        val list = try {
            bm.adapter?.bondedDevices?.map { it.address to (it.name ?: it.address) } ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
        val carWords = listOf(
            "car", "auto", "audi", "bmw", "vw", "volkswagen", "skoda", "škoda", "toyota", "ford", "opel", "kia", "hyundai",
            "renault", "peugeot", "citroen", "fiat", "mazda", "honda", "nissan", "seat", "cupra", "volvo", "mercedes",
            "dacia", "suzuki", "tesla", "sync", "uconnect", "mylink", "carplay", "media", "handsfree", "hands-free",
        )
        return list.sortedByDescending { (_, n) -> carWords.any { n.contains(it, ignoreCase = true) } }
    }

    suspend fun onConnected(ctx: Context, address: String) {
        if (address !in Prefs(ctx).carDevices) return
        val db = (ctx.applicationContext as LifeApp).db
        if (db.cars().open() != null) return
        db.cars().insert(CarSession(device = address, startAt = System.currentTimeMillis()))
    }

    @SuppressLint("MissingPermission")
    suspend fun onDisconnected(ctx: Context, address: String) {
        if (address !in Prefs(ctx).carDevices) return
        val app = ctx.applicationContext as LifeApp
        val open = app.db.cars().open() ?: return
        val now = System.currentTimeMillis()
        // Miejsce parkowania: świeży punkt GPS albo ostatni zapisany.
        val fix = try {
            withTimeoutOrNull(15_000) {
                LocationServices.getFusedLocationProviderClient(ctx)
                    .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await()
            }
        } catch (_: Exception) {
            null
        }
        val last = app.db.locations().last()
        val lat = fix?.latitude ?: last?.lat
        val lng = fix?.longitude ?: last?.lng
        app.db.cars().update(open.copy(endAt = now, parkLat = lat, parkLng = lng))
    }
}

/** Odbiornik z manifestu (połączenia ACL są wyjątkiem od ograniczeń niejawnych broadcastów). */
class CarBtReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pr = goAsync()
        val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
            i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val address = dev?.address
        if (address == null) { pr.finish(); return }
        val app = c.applicationContext as LifeApp
        app.appScope.launch {
            try {
                when (i.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> CarBluetooth.onConnected(c, address)
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> CarBluetooth.onDisconnected(c, address)
                }
            } catch (e: Exception) {
                Log.w(TrackingService.TAG, "Samochód BT: ${e.message}")
            } finally {
                pr.finish()
            }
        }
    }
}
