package pl.rafal.koofr

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Zgodność modułu Koofr (Java) z wersją w Darcie i niezależną implementacją w Pythonie: te same wektory
 * (packages/koofr_sync/test/vectors.json w repozytorium Rytmu Snu, pobiera je CI). Dzięki temu kopię
 * i pliki wymiany z Androida odczyta każda aplikacja, a kopia przywraca się na nowym telefonie z samego kodu.
 */
class KoofrVectorsTest {
    private val file = File("src/test/resources/koofr/vectors.json")

    private fun v(): JSONObject {
        assumeTrue("brak wektorów (pobiera je CI)", file.isFile)
        return JSONObject(file.readText())
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun kluczeZKodu() {
        val v = v()
        val app = v.getString("app")
        for (who in listOf("owner", "viewer")) {
            val o = v.getJSONObject(who)
            val code = RecoveryCode.parse(o.getString("code"))!!
            assertEquals(o.getString("code"), code.format())
            assertEquals(o.getString("check"), KsCrypto.checkOf(code))
            assertEquals(o.getString("pub"), Base64.getEncoder().encodeToString(KsCrypto.fromCode(code, app).publicKey))
        }
    }

    @Test
    fun odczytKopiiPodgladuIWymiany() {
        val v = v()
        val app = v.getString("app")
        val owner = KsCrypto.fromCode(RecoveryCode.parse(v.getJSONObject("owner").getString("code"))!!, app)
        val viewer = KsCrypto.fromCode(RecoveryCode.parse(v.getJSONObject("viewer").getString("code"))!!, app)
        assertEquals(v.getString("backupPlain"), String(owner.openBackup(hex(v.getString("backup"))), Charsets.UTF_8))
        assertEquals(v.getString("sharePlain"), String(viewer.openFrom(owner.publicKey, hex(v.getString("share"))), Charsets.UTF_8))
        assertEquals(v.getString("safety"), KsCrypto.safetyNumber(app, owner.publicKey, viewer.publicKey))
        assertEquals(v.getString("blobId"), owner.blobId("załącznik".toByteArray(Charsets.UTF_8)))
        // wymiana: ten sam klucz w każdej aplikacji tej osoby, inna osoba nie odczyta
        val other = KsCrypto.fromCode(RecoveryCode.parse(v.getJSONObject("owner").getString("code"))!!, "kreska")
        assertEquals(v.getString("exchangePlain"), String(other.openExchange(hex(v.getString("exchange"))), Charsets.UTF_8))
        assertTrue(runCatching { viewer.openExchange(hex(v.getString("exchange"))) }.isFailure)
    }

    @Test
    fun kopiaNaNowymTelefonieZSamegoKodu() {
        val code = RecoveryCode.generate()
        val data = ByteArray(200_000) { (it * 31 % 251).toByte() }
        val sealed = KsCrypto.fromCode(code, "test").sealBackup(data)
        // nowy telefon: tylko przepisany kod odzyskiwania
        val fresh = KsCrypto.fromCode(RecoveryCode.parse(code.format())!!, "test")
        assertArrayEquals(data, fresh.openBackup(sealed))
        // inny kod nie otworzy kopii
        assertTrue(runCatching { KsCrypto.fromCode(RecoveryCode.generate(), "test").openBackup(sealed) }.isFailure)
    }
}
