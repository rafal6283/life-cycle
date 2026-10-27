package pl.rafal.koofr;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/** Kod odzyskiwania — format jak w pakiecie koofr_sync (Dart): 18 B losowe + 2 B SHA-256, base32 Crockford. */
public final class RecoveryCode {
    private static final String ALPH = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    final byte[] entropy;

    private RecoveryCode(byte[] e) {
        entropy = e;
    }

    public static RecoveryCode generate() {
        byte[] e = new byte[18];
        new SecureRandom().nextBytes(e);
        return new RecoveryCode(e);
    }

    /** null przy niepełnym kodzie albo literówce. */
    public static RecoveryCode parse(String text) {
        String s = normalize(text);
        if (s.length() != 32) return null;
        byte[] b = decode(s);
        if (b == null || b.length != 20) return null;
        byte[] ent = Arrays.copyOf(b, 18);
        byte[] sum = checksum(ent);
        if (b[18] != sum[0] || b[19] != sum[1]) return null;
        return new RecoveryCode(ent);
    }

    static String normalize(String t) {
        return t.toUpperCase().replaceAll("[^0-9A-Z]", "").replace('O', '0').replace('I', '1').replace('L', '1').replace('U', 'V');
    }

    public String format() {
        byte[] sum = checksum(entropy);
        byte[] all = Arrays.copyOf(entropy, 20);
        all[18] = sum[0];
        all[19] = sum[1];
        String s = encode(all);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i += 4) {
            if (i > 0) sb.append('-');
            sb.append(s, i, i + 4);
        }
        return sb.toString();
    }

    private static byte[] checksum(byte[] e) {
        try {
            return Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(e), 2);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String encode(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int buf = 0, bits = 0;
        for (byte x : bytes) {
            buf = (buf << 8) | (x & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                out.append(ALPH.charAt((buf >> bits) & 31));
            }
            buf &= (1 << bits) - 1;
        }
        if (bits > 0) out.append(ALPH.charAt((buf << (5 - bits)) & 31));
        return out.toString();
    }

    private static byte[] decode(String s) {
        byte[] out = new byte[s.length() * 5 / 8];
        int n = 0, buf = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            int v = ALPH.indexOf(s.charAt(i));
            if (v < 0) return null;
            buf = (buf << 5) | v;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                out[n++] = (byte) ((buf >> bits) & 255);
            }
            buf &= (1 << bits) - 1;
        }
        return Arrays.copyOf(out, n);
    }
}
