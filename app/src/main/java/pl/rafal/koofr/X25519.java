package pl.rafal.koofr;

import java.math.BigInteger;

/** X25519 (RFC 7748) na BigInteger — dla Androida przed API 33, bez dodatkowych bibliotek. */
final class X25519 {
    private X25519() {}

    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger A24 = BigInteger.valueOf(121665);
    private static final byte[] BASE = new byte[32];

    static {
        BASE[0] = 9;
    }

    static byte[] publicKey(byte[] priv) {
        return scalarMult(priv, BASE);
    }

    static byte[] scalarMult(byte[] scalar, byte[] point) {
        byte[] k = scalar.clone();
        k[0] &= (byte) 248;
        k[31] &= 127;
        k[31] |= 64;
        BigInteger kn = decode(k);
        byte[] u = point.clone();
        u[31] &= 127;
        BigInteger x1 = decode(u).mod(P);
        BigInteger x2 = BigInteger.ONE, z2 = BigInteger.ZERO, x3 = x1, z3 = BigInteger.ONE;
        boolean swap = false;
        for (int t = 254; t >= 0; t--) {
            boolean kt = kn.testBit(t);
            if (swap ^ kt) {
                BigInteger tmp = x2; x2 = x3; x3 = tmp;
                tmp = z2; z2 = z3; z3 = tmp;
            }
            swap = kt;
            BigInteger a = x2.add(z2).mod(P);
            BigInteger aa = a.multiply(a).mod(P);
            BigInteger b = x2.subtract(z2).mod(P);
            BigInteger bb = b.multiply(b).mod(P);
            BigInteger e = aa.subtract(bb).mod(P);
            BigInteger c = x3.add(z3).mod(P);
            BigInteger d = x3.subtract(z3).mod(P);
            BigInteger da = d.multiply(a).mod(P);
            BigInteger cb = c.multiply(b).mod(P);
            BigInteger s1 = da.add(cb).mod(P);
            x3 = s1.multiply(s1).mod(P);
            BigInteger s2 = da.subtract(cb).mod(P);
            z3 = x1.multiply(s2.multiply(s2)).mod(P);
            x2 = aa.multiply(bb).mod(P);
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P);
        }
        if (swap) {
            x2 = x3;
            z2 = z3;
        }
        return encode(x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P));
    }

    private static BigInteger decode(byte[] le) {
        byte[] be = new byte[33];
        for (int i = 0; i < 32; i++) be[32 - i] = le[i];
        return new BigInteger(be);
    }

    private static byte[] encode(BigInteger n) {
        byte[] be = n.toByteArray();
        byte[] le = new byte[32];
        for (int i = 0; i < 32 && i < be.length; i++) le[i] = be[be.length - 1 - i];
        return le;
    }
}
