package pl.rafal.koofr;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Klucze i pliki .ksy — zgodne bajt w bajt z pakietem koofr_sync (Dart). */
public final class KsCrypto {
    private static final byte[] MAGIC = {0x4B, 0x53, 0x59, 0x31};
    private static final byte[] SALT = "koofr_sync v1".getBytes(StandardCharsets.UTF_8);
    static final int KIND_BACKUP = 1, KIND_SHARE = 2;
    private static final SecureRandom RND = new SecureRandom();

    public final String appId;
    final byte[] backupKey;
    private final byte[] priv;
    public final byte[] publicKey;
    public final String codeCheck;

    private KsCrypto(String appId, byte[] backupKey, byte[] priv, byte[] pub, String check) {
        this.appId = appId;
        this.backupKey = backupKey;
        this.priv = priv;
        this.publicKey = pub;
        this.codeCheck = check;
    }

    public static KsCrypto fromCode(RecoveryCode code, String appId) {
        byte[] master = hkdf(code.entropy, "master".getBytes(StandardCharsets.UTF_8));
        byte[] backup = hkdf(master, ("backup/" + appId).getBytes(StandardCharsets.UTF_8));
        byte[] seed = hkdf(master, ("x25519/" + appId).getBytes(StandardCharsets.UTF_8));
        return new KsCrypto(appId, backup, seed, X25519.publicKey(seed), checkOf(code));
    }

    public static String checkOf(RecoveryCode code) {
        byte[] master = hkdf(code.entropy, "master".getBytes(StandardCharsets.UTF_8));
        return hex(Arrays.copyOf(hkdf(master, "check".getBytes(StandardCharsets.UTF_8)), 8));
    }

    public String publicKeyB64() {
        return Base64.getUrlEncoder().encodeToString(publicKey);
    }

    public static byte[] parsePublicKey(String b64) throws KsException {
        byte[] b;
        try {
            b = Base64.getUrlDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new KsException("Nieprawidłowy klucz publiczny");
        }
        if (b.length != 32) throw new KsException("Nieprawidłowy klucz publiczny");
        return b;
    }

    // ------------------------------------------------------------ kopia

    public byte[] sealBackup(byte[] data) throws KsException {
        byte[] header = header(KIND_BACKUP);
        byte[] nonce = new byte[12];
        RND.nextBytes(nonce);
        return concat(header, nonce, gcm(Cipher.ENCRYPT_MODE, backupKey, nonce, aad(header), gzip(data)));
    }

    public byte[] openBackup(byte[] file) throws KsException {
        checkHeader(file, KIND_BACKUP);
        byte[] header = Arrays.copyOf(file, 5);
        if (file.length < 5 + 12 + 16) throw new KsException("Kopia jest uszkodzona.");
        try {
            byte[] clear = gcm(Cipher.DECRYPT_MODE, backupKey, Arrays.copyOfRange(file, 5, 17), aad(header),
                    Arrays.copyOfRange(file, 17, file.length));
            return gunzip(clear);
        } catch (KsException e) {
            throw new KsException("Nie da się odszyfrować kopii — to kopia innej osoby albo inny kod odzyskiwania.");
        }
    }

    /** Nazwa załącznika: HMAC-SHA256(klucz kopii, treść), pierwsze 16 bajtów hex. */
    public String blobId(byte[] data) {
        return hex(Arrays.copyOf(hmac(backupKey, data), 16));
    }

    // ------------------------------------------------------------ podgląd

    public byte[] sealFor(byte[] recipient, byte[] data) throws KsException {
        byte[] header = header(KIND_SHARE);
        byte[] eph = new byte[32];
        RND.nextBytes(eph);
        byte[] ephPub = X25519.publicKey(eph);
        byte[] key = shareKey(X25519.scalarMult(eph, recipient), X25519.scalarMult(priv, recipient), ephPub, publicKey, recipient);
        byte[] nonce = new byte[12];
        RND.nextBytes(nonce);
        return concat(header, ephPub, nonce, gcm(Cipher.ENCRYPT_MODE, key, nonce, aad(header), gzip(data)));
    }

    public byte[] openFrom(byte[] sender, byte[] file) throws KsException {
        checkHeader(file, KIND_SHARE);
        if (file.length < 5 + 32 + 28) throw new KsException("Plik podglądu jest uszkodzony.");
        byte[] header = Arrays.copyOf(file, 5);
        byte[] ephPub = Arrays.copyOfRange(file, 5, 37);
        byte[] key = shareKey(X25519.scalarMult(priv, ephPub), X25519.scalarMult(priv, sender), ephPub, sender, publicKey);
        try {
            return gunzip(gcm(Cipher.DECRYPT_MODE, key, Arrays.copyOfRange(file, 37, 49), aad(header),
                    Arrays.copyOfRange(file, 49, file.length)));
        } catch (KsException e) {
            throw new KsException("Nie da się odszyfrować — dane nie pochodzą od tej osoby albo zmieniła kod.");
        }
    }

    private byte[] shareKey(byte[] ss1, byte[] ss2, byte[] ephPub, byte[] senderPub, byte[] recipientPub) {
        byte[] info = concat(("share/" + appId).getBytes(StandardCharsets.UTF_8), ephPub, senderPub, recipientPub);
        return hkdf(concat(ss1, ss2), info);
    }

    public static String safetyNumber(String appId, byte[] a, byte[] b) {
        String ka = Base64.getUrlEncoder().encodeToString(a), kb = Base64.getUrlEncoder().encodeToString(b);
        String joined = ka.compareTo(kb) <= 0 ? ka + "/" + kb : kb + "/" + ka;
        byte[] h = sha256(("safety/" + appId + "/" + joined).getBytes(StandardCharsets.UTF_8));
        BigInteger n = new BigInteger(1, Arrays.copyOf(h, 8)).mod(BigInteger.TEN.pow(12));
        String s = String.format("%012d", n.longValue());
        return s.substring(0, 4) + " " + s.substring(4, 8) + " " + s.substring(8);
    }

    // ------------------------------------------------------------ pomocnicze

    private byte[] aad(byte[] header) {
        return concat(header, appId.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] header(int kind) {
        return new byte[] {MAGIC[0], MAGIC[1], MAGIC[2], MAGIC[3], (byte) kind};
    }

    private static void checkHeader(byte[] f, int kind) throws KsException {
        if (f.length < 5) throw new KsException("Plik jest pusty lub uszkodzony.");
        for (int i = 0; i < 4; i++) if (f[i] != MAGIC[i]) throw new KsException("To nie jest plik kopii/podglądu.");
        if (f[4] != kind) throw new KsException("Nieoczekiwany rodzaj pliku.");
    }

    private static byte[] gcm(int mode, byte[] key, byte[] nonce, byte[] aad, byte[] data) throws KsException {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            c.updateAAD(aad);
            return c.doFinal(data);
        } catch (Exception e) {
            throw new KsException("Błąd szyfrowania: " + e.getClass().getSimpleName());
        }
    }

    static byte[] hkdf(byte[] ikm, byte[] info) {
        byte[] prk = hmac(SALT, ikm);
        return Arrays.copyOf(hmac(prk, concat(info, new byte[] {1})), 32);
    }

    static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] sha256(byte[] d) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] gzip(byte[] d) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (GZIPOutputStream g = new GZIPOutputStream(bo)) {
                g.write(d);
            }
            return bo.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] gunzip(byte[] d) throws KsException {
        try (GZIPInputStream g = new GZIPInputStream(new ByteArrayInputStream(d))) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = g.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } catch (IOException e) {
            throw new KsException("Uszkodzone dane (gzip).");
        }
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }
}
