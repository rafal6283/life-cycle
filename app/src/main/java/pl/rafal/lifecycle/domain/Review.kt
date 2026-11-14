package pl.rafal.lifecycle.domain

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.rafal.lifecycle.LifeApp
import pl.rafal.lifecycle.data.Category
import pl.rafal.lifecycle.data.Entry
import pl.rafal.lifecycle.data.Kind
import pl.rafal.lifecycle.data.Source
import pl.rafal.lifecycle.util.Fmt
import java.text.Normalizer
import java.time.LocalDate
import kotlin.math.roundToInt

/** Coś do uzupełnienia w przeglądzie dnia: luka bez danych albo nieznany pobyt. */
sealed class ReviewItem {
    abstract val start: Long
    abstract val end: Long

    data class Gap(override val start: Long, override val end: Long) : ReviewItem()
    data class Unknown(val entry: Entry, override val start: Long, override val end: Long) : ReviewItem()
}

object Review {
    private const val MIN_GAP = 30 * 60_000L
    private const val MIN_UNKNOWN = 10 * 60_000L

    /** Przedziały bez żadnego wpisu (luki w śledzeniu) w [from, to). */
    fun gaps(segs: List<Segment>, from: Long, to: Long, minLen: Long = MIN_GAP): List<Pair<Long, Long>> {
        val out = mutableListOf<Pair<Long, Long>>()
        var t = from
        for (s in segs.sortedBy { it.start }) {
            if (s.start - t >= minLen) out += t to s.start
            if (s.end > t) t = s.end
        }
        if (to - t >= minLen) out += t to to
        return out
    }

    fun items(entries: List<Entry>, cats: Map<Long, Category>, day: LocalDate, now: Long, firstData: Long?): List<ReviewItem> {
        val from = maxOf(Fmt.dayStart(day), firstData ?: Long.MAX_VALUE)
        val to = minOf(Fmt.dayStart(day.plusDays(1)), now - 10 * 60_000L)
        if (to <= from) return emptyList()
        val segs = Timeline.resolve(entries, from, to, now)
        val out = mutableListOf<ReviewItem>()
        gaps(segs, from, to).forEach { (a, b) -> out += ReviewItem.Gap(a, b) }
        segs.filter { cats[it.entry.categoryId]?.kind == Kind.UNKNOWN && it.duration >= MIN_UNKNOWN }
            .distinctBy { it.entry.id }
            .forEach { out += ReviewItem.Unknown(it.entry, it.start, it.end) }
        return out.sortedBy { it.start }
    }

    suspend fun forDay(ctx: Context, day: LocalDate): List<ReviewItem> = withContext(Dispatchers.Default) {
        val app = ctx.applicationContext as LifeApp
        val entries = app.repo.getRange(Fmt.dayStart(day), Fmt.dayStart(day.plusDays(1)))
        items(entries, app.db.categories().getAll().associateBy { it.id }, day, System.currentTimeMillis(), app.db.entries().firstStart())
    }

    /** Uzupełnia lukę ręcznym wpisem. */
    suspend fun fillGap(ctx: Context, g: ReviewItem.Gap, categoryId: Long, note: String?) {
        val app = ctx.applicationContext as LifeApp
        app.repo.saveEntry(Entry(startAt = g.start, endAt = g.end, categoryId = categoryId, source = Source.MANUAL, note = note))
    }
}

/** „Kiedy ostatnio…?” — wizyty w miejscu albo kategorii. */
data class VisitStats(
    val title: String,
    val subtitle: String,
    val categoryId: Long?,
    val placeId: Long?,
    val visits: Int,
    val days: Int,
    val totalMs: Long,
    val last: Long,
    val first: Long,
    /** Typowy odstęp między dniami wizyt (mediana), w dniach. */
    val usualGapDays: Int?,
    val recent: List<Long>,
) {
    val sinceDays: Int get() = ((System.currentTimeMillis() - last) / 86_400_000L).toInt()
    val overdue: Boolean get() = usualGapDays != null && visits >= 4 && sinceDays > usualGapDays * 1.3 + 1
}

object Search {
    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace('ł', 'l')

    private fun stats(title: String, sub: String, catId: Long?, placeId: Long?, list: List<Entry>, now: Long): VisitStats? {
        if (list.isEmpty()) return null
        val sorted = list.sortedBy { it.startAt }
        // Wpisy w odstępie < 2 h to jedna wizyta.
        val visitStarts = mutableListOf<Long>()
        var lastEnd = Long.MIN_VALUE
        for (e in sorted) {
            if (e.startAt - lastEnd > 2 * 3_600_000L) visitStarts += e.startAt
            lastEnd = maxOf(lastEnd, e.endAt ?: now)
        }
        val days = visitStarts.map { Fmt.toLocalDate(it).toEpochDay() }.distinct()
        val gaps = days.zipWithNext { a, b -> (b - a).toInt() }.filter { it > 0 }.sorted()
        return VisitStats(
            title, sub, catId, placeId, visitStarts.size, days.size,
            sorted.sumOf { (it.endAt ?: now) - it.startAt }, sorted.last().startAt, sorted.first().startAt,
            gaps.takeIf { it.size >= 2 }?.let { it[it.size / 2] }, visitStarts.takeLast(6).reversed(),
        )
    }

    suspend fun run(ctx: Context, query: String): List<VisitStats> = withContext(Dispatchers.Default) {
        val app = ctx.applicationContext as LifeApp
        val now = System.currentTimeMillis()
        val all = app.repo.allWithCommutes()
        val cats = app.db.categories().getAll()
        val places = app.db.places().getAll()
        val catMap = cats.associateBy { it.id }
        val q = norm(query.trim())
        val out = mutableListOf<VisitStats>()
        val byPlace = all.filter { it.placeId != null }.groupBy { it.placeId!! }
        for (p in places) {
            if (q.isNotEmpty() && !norm(p.name).contains(q) && !norm(catMap[p.categoryId]?.name ?: "").contains(q)) continue
            stats(p.name, catMap[p.categoryId]?.name ?: "", p.categoryId, p.id, byPlace[p.id].orEmpty(), now)?.let { out += it }
        }
        for (c in cats) {
            if (c.kind == Kind.UNKNOWN) continue
            if (q.isEmpty() || !norm(c.name).contains(q)) continue
            stats(c.name, "kategoria", c.id, null, all.filter { it.categoryId == c.id }, now)?.let { out += it }
        }
        // Notatki (np. „fryzjer” wpisany w notatce).
        if (q.length >= 3) {
            val noted = all.filter { e -> e.note?.let { norm(it).contains(q) } == true }
            stats("„$query” w notatkach", "wpisy z notatką", null, null, noted, now)?.let { out += it }
        }
        if (q.isEmpty()) out.filter { it.overdue }.sortedByDescending { it.sinceDays - (it.usualGapDays ?: 0) }
        else out.sortedByDescending { it.last }
    }

    fun gapText(s: VisitStats): String {
        val since = s.sinceDays
        val sinceTxt = when (since) {
            0 -> "dziś"
            1 -> "wczoraj"
            else -> "$since dni temu"
        }
        val usual = s.usualGapDays?.let { g ->
            when {
                g <= 1 -> "codziennie"
                g % 7 == 0 && g <= 56 -> "zwykle co ${g / 7} tyg."
                g >= 28 -> "zwykle co ok. ${(g / 30.0).roundToInt().coerceAtLeast(1)} mies."
                else -> "zwykle co $g dni"
            }
        }
        return "Ostatnio $sinceTxt" + (usual?.let { " · $it" } ?: "")
    }
}
