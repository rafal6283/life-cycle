package pl.rafal.lifecycle.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import kotlinx.coroutines.runBlocking
import pl.rafal.lifecycle.LifeApp

/**
 * Pobyty z Doby dla zaprzyjaźnionych aplikacji (np. Znicz) — tylko za zgodą użytkownika
 * (uprawnienie „pl.rafal.lifecycle.permission.READ_STAYS” wymaga potwierdzenia w telefonie).
 *
 * content://pl.rafal.lifecycle.stays/stays?from=<ms>&to=<ms>
 * Kolumny: start, end, lat, lng, place, category
 */
class StaysProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val now = System.currentTimeMillis()
        val from = uri.getQueryParameter("from")?.toLongOrNull() ?: (now - 90L * 86_400_000L)
        val to = uri.getQueryParameter("to")?.toLongOrNull() ?: now
        val cur = MatrixCursor(arrayOf("start", "end", "lat", "lng", "place", "category"))
        val app = context?.applicationContext as? LifeApp ?: return cur
        runBlocking {
            val cats = app.db.categories().getAll().associateBy { it.id }
            val places = app.db.places().getAll().associateBy { it.id }
            app.db.entries().getRange(from, to)
                .filter { it.source == Source.AUTO_STAY && it.lat != null && it.lng != null }
                .forEach { e ->
                    cur.addRow(arrayOf<Any?>(
                        e.startAt, e.endAt ?: now, e.lat, e.lng,
                        e.placeId?.let { places[it]?.name }, cats[e.categoryId]?.name,
                    ))
                }
        }
        return cur
    }

    override fun getType(uri: Uri) = "vnd.android.cursor.dir/vnd.doba.stay"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
