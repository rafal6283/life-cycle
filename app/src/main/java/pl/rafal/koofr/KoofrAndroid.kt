package pl.rafal.koofr

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import androidx.compose.runtime.Composable
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Transport WebDAV przez OkHttp (obsługuje PROPFIND, MKCOL, MOVE). */
class OkDavHttp : DavHttp {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): DavHttp.Response {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        val rb = body?.toRequestBody("application/octet-stream".toMediaType())
            ?: if (method == "PUT") ByteArray(0).toRequestBody() else null
        b.method(method, rb)
        client.newCall(b.build()).execute().use { r -> return DavHttp.Response(r.code, r.body?.bytes() ?: ByteArray(0)) }
    }
}

/** Hasło i kod odzyskiwania w zaszyfrowanych preferencjach (klucz w Android Keystore). */
class PrefsStore(ctx: Context) : KoofrSync.Store {
    private val p: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            ctx, "koofr_sync",
            MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        ctx.getSharedPreferences("koofr_sync_plain", Context.MODE_PRIVATE)
    }

    override fun read(key: String): String? = p.getString(key, null)
    override fun write(key: String, value: String) = p.edit().putString(key, value).apply()
    override fun delete(key: String) = p.edit().remove(key).apply()
}

/** Część zależna od aplikacji: co jest w kopii i jak ją odtworzyć. */
interface KoofrApp {
    val appId: String
    val appName: String
    val contentNote: String
    val restoreWarning: String get() = "Dane z kopii zastąpią obecne dane aplikacji $appName na tym telefonie."

    /** Kopia automatyczna czeka, gdy aplikacja robi coś ważnego. */
    val busy: Boolean get() = false

    suspend fun export(ctx: Context): Pair<ByteArray, List<KoofrSync.Blob>>
    suspend fun import(ctx: Context, r: KoofrSync.Restored, progress: (String) -> Unit)

    /** Podgląd danych między osobami (tylko w niektórych aplikacjach). */
    val sharing: KoofrSharing? get() = null

    /** Wymiana danych z innymi aplikacjami tej samej osoby (null = aplikacja nic nie wymienia). */
    val exchange: KoofrExchange? get() = null
}

/** Automatyczna wymiana plików „wymiana-zdarzen” między moimi aplikacjami przez Koofr. */
interface KoofrExchange {
    /** Co ta aplikacja może udostępniać (opis do ustawień), np. „wiatry wykryte w nocy”; null = nic. */
    val publishes: String? get() = null

    /** Źródła do pobierania: przedrostek nazwy pliku → opis (np. "rytm-snu" → „Rytm Snu: wiatry”). */
    val sources: List<Pair<String, String>> get() = emptyList()

    /** Pliki do opublikowania: nazwa → treść; null treści = usuń plik z Koofr. */
    suspend fun export(ctx: Context): Map<String, ByteArray?> = emptyMap()

    /** Wczytanie pobranego pliku (zastępuje poprzednie dane z tego źródła). */
    suspend fun import(ctx: Context, name: String, data: ByteArray) {}

    /** Dodatkowe ustawienia aplikacji w sekcji wymiany (np. które liczniki udostępniać). */
    @Composable
    fun ExtraSettings() {}
}

interface KoofrSharing {
    /** np. „Twoją dobę”, „Twój sen”. */
    val whatMine: String
    /** np. „dobę tej osoby”. */
    val whatTheirs: String
    val dayOptions: List<Int> get() = listOf(7, 30, 90)
    /** Klucze zakresu i ich opisy (przełączniki). */
    val options: List<Pair<String, String>>
    val defaults: Map<String, Any?>
    /** Co zawsze jest w podglądzie (opis). */
    val alwaysIncluded: String

    suspend fun build(ctx: Context, scope: Map<String, Any?>): ByteArray

    @Composable
    fun Viewer(ownerName: String, data: ByteArray, updatedAt: Long?, onBack: () -> Unit)
}

/** Jedno miejsce dla całej aplikacji: konto, kopie, podgląd i automatyka. */
object Koofr {
    lateinit var app: KoofrApp
        private set
    lateinit var sync: KoofrSync
        private set
    private lateinit var appCtx: Context

    val busy = MutableStateFlow(false)
    val progress = MutableStateFlow<String?>(null)
    val lastError = MutableStateFlow<String?>(null)
    val version = MutableStateFlow(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var lastPublish = 0L

    val initialized get() = ::sync.isInitialized

    /** Wywołać w Application.onCreate. */
    fun init(ctx: Context, app: KoofrApp) {
        if (initialized) return
        this.app = app
        appCtx = ctx.applicationContext
        sync = KoofrSync(app.appId, PrefsStore(appCtx), OkDavHttp(), null)
        sync.addListener { version.value++ }
        scope.launch {
            sync.load()
            schedule(appCtx)
        }
    }

    // ------------------------------------------------------------ wymiana

    fun exchangeOn(): Boolean = sync.store.read("ks.ex.on") == "1"
    fun setExchangeOn(v: Boolean) {
        sync.store.write("ks.ex.on", if (v) "1" else "0")
        version.value++
        if (v) autoRunAsync(true)
    }

    fun publishOn(): Boolean = sync.store.read("ks.ex.publish") != "0"
    fun setPublishOn(v: Boolean) {
        sync.store.write("ks.ex.publish", if (v) "1" else "0")
        version.value++
        autoRunAsync(true)
    }

    fun sourceOn(prefix: String): Boolean = sync.store.read("ks.ex.src.$prefix") != "0"
    fun setSourceOn(prefix: String, v: Boolean) {
        sync.store.write("ks.ex.src.$prefix", if (v) "1" else "0")
        version.value++
        if (v) autoRunAsync(true)
    }

    val lastExchange = MutableStateFlow<String?>(null)
    private var lastExchangeRun = 0L

    /** Publikuje i pobiera dane wymiany. Zwraca opis dla użytkownika. */
    suspend fun exchangeNow(force: Boolean = false): String = withContext(Dispatchers.IO) {
        val ex = app.exchange ?: return@withContext ""
        if (!sync.ready() || !exchangeOn()) return@withContext ""
        val sent = ArrayList<String>()
        if (ex.publishes != null) {
            for ((name, data) in ex.export(appCtx)) {
                if (data == null || !publishOn()) sync.removeExchange(name)
                else if (sync.publishExchange(name, data, force)) sent += name
            }
        }
        val got = if (ex.sources.isEmpty()) emptyList() else sync.pullExchange({ n ->
            ex.sources.any { (p, _) -> (n == p || n.startsWith("$p-")) && sourceOn(p) }
        }, { n, d -> kotlinx.coroutines.runBlocking { ex.import(appCtx, n, d) } }, force)
        lastExchangeRun = System.currentTimeMillis()
        val msg = listOfNotNull(
            if (sent.isNotEmpty()) "wysłano: ${sent.joinToString()}" else null,
            if (got.isNotEmpty()) "pobrano: ${got.joinToString()}" else null,
        ).joinToString(" · ").ifEmpty { "bez zmian" }
        lastExchange.value = msg
        msg
    }

    fun wifiOnly(): Boolean = sync.store.read("ks.wifiOnly") != "0"
    fun setWifiOnly(v: Boolean) {
        sync.store.write("ks.wifiOnly", if (v) "1" else "0")
        schedule(appCtx)
        version.value++
    }

    private fun unmetered(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.activeNetwork != null && !cm.isActiveNetworkMetered
    } catch (e: Exception) {
        true
    }

    /** Codzienna praca w tle (WorkManager) — kopia i wysyłka podglądu, gdy jest sieć. */
    fun schedule(ctx: Context) {
        val c = Constraints.Builder()
            .setRequiredNetworkType(if (sync.store.read("ks.wifiOnly") != "0") NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val req = PeriodicWorkRequestBuilder<KoofrWorker>(6, TimeUnit.HOURS).setConstraints(c).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("koofr-sync", ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Przy starcie/powrocie do aplikacji i z pracy w tle. */
    fun autoRunAsync(force: Boolean = false) {
        if (!initialized) return
        scope.launch { autoRun(force) }
    }

    suspend fun autoRun(forcePublish: Boolean = false) {
        if (!initialized || busy.value || app.busy) return
        try {
            if (!sync.ready()) return
            // wymiana: małe pliki, także bez Wi-Fi; co najmniej co 15 min
            if (app.exchange != null && exchangeOn() && (forcePublish || System.currentTimeMillis() - lastExchangeRun > 15 * 60_000L)) {
                exchangeNow()
            }
            val wantBackup = sync.backupDue(20 * 3600_000L)
            val wantPublish = app.sharing != null && sync.myConsents().isNotEmpty() &&
                (forcePublish || System.currentTimeMillis() - lastPublish > 15 * 60_000L)
            if (!wantBackup && !wantPublish) return
            if (wifiOnly() && !unmetered(appCtx)) return
            if (wantBackup) backupNow()
            if (wantPublish) publish()
            lastError.value = null
        } catch (e: Exception) {
            lastError.value = e.message ?: e.toString()
        }
    }

    suspend fun backupNow(): KoofrSync.BackupInfo = lock.withLock {
        withContext(Dispatchers.IO) {
            busy.value = true
            try {
                progress.value = "Przygotowanie danych…"
                val (data, blobs) = app.export(appCtx)
                progress.value = if (blobs.isEmpty()) "Wysyłanie…" else "Wysyłanie załączników…"
                sync.backupNow(data, blobs) { d, t -> progress.value = "Załączniki: $d z $t" }
            } finally {
                busy.value = false
                progress.value = null
            }
        }
    }

    suspend fun restore(b: KoofrSync.BackupInfo) = lock.withLock {
        withContext(Dispatchers.IO) {
            busy.value = true
            try {
                progress.value = "Pobieranie kopii…"
                val r = sync.restore(b)
                app.import(appCtx, r) { progress.value = it }
            } finally {
                busy.value = false
                progress.value = null
            }
        }
    }

    suspend fun publish(): Int = withContext(Dispatchers.IO) {
        val sh = app.sharing ?: return@withContext 0
        val n = sync.publishShares { _, scope -> kotlinx.coroutines.runBlocking { sh.build(appCtx, scope) } }
        lastPublish = System.currentTimeMillis()
        n
    }
}

class KoofrWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!Koofr.initialized) return Result.success()
        if (!Koofr.sync.ready()) Koofr.sync.load()
        Koofr.autoRun()
        return Result.success()
    }
}
