package pl.rafal.lifecycle.domain

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Pliki „czynniki-dnia” v1 z innych aplikacji (przez Koofr): sen (Rytm Snu), posiłki (Dziennik talerza),
 * leki (Teczka), HRV i AF (Polar EKG), wartość Portfela. Źródło → dzień → wartości.
 */
object AppFactors {
    private fun dir(ctx: Context) = File(ctx.filesDir, "czynniki").apply { mkdirs() }

    fun save(ctx: Context, name: String, data: ByteArray) {
        if (name.contains('/') || name.contains("..")) return
        File(dir(ctx), "$name.json").writeBytes(data)
    }

    fun load(ctx: Context): Map<String, Map<LocalDate, Map<String, Double>>> {
        val out = HashMap<String, Map<LocalDate, Map<String, Double>>>()
        dir(ctx).listFiles()?.forEach { f ->
            try {
                val j = JSONObject(f.readText())
                if (j.optString("format") != "czynniki-dnia") return@forEach
                val days = HashMap<LocalDate, Map<String, Double>>()
                val arr = j.optJSONArray("days") ?: return@forEach
                for (i in 0 until arr.length()) {
                    val d = arr.getJSONObject(i)
                    val date = LocalDate.parse(d.getString("date"))
                    val vals = HashMap<String, Double>()
                    d.keys().forEach { k -> if (k != "date") (d.opt(k) as? Number)?.let { vals[k] = it.toDouble() } }
                    days[date] = vals
                }
                out[j.optString("source")] = days
            } catch (_: Exception) {
            }
        }
        return out
    }
}
