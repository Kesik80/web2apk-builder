package com.web2apk.builder.apk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Builds a minimal self-signed X.509 certificate (RSA, SHA-256) without extra libraries. */
public final class CertMaker {
    private CertMaker() {
    }

    private static final byte[] OID_SHA256_RSA =
            {0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B};
    private static final byte[] OID_CN = {0x55, 0x04, 0x03};

    public static X509Certificate selfSigned(KeyPair kp, String commonName, int years) throws Exception {
        byte[] sigAlg = seq(der(0x06, OID_SHA256_RSA), der(0x05, new byte[0]));
        byte[] name = seq(der(0x31, seq(der(0x06, OID_CN),
                der(0x0C, commonName.getBytes(StandardCharsets.UTF_8)))));

        byte[] serial = new byte[8];
        new SecureRandom().nextBytes(serial);
        serial[0] &= 0x3F;
        serial[0] |= 0x01; // positive and non-zero

        Calendar now = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        Date notBefore = new Date(now.getTimeInMillis() - 24L * 3600 * 1000);
        now.add(Calendar.YEAR, years);
        Date notAfter = now.getTime();
        byte[] validity = seq(
                der(0x18, generalized(notBefore)),
                der(0x18, generalized(notAfter)));

        byte[] tbs = seq(
                der(0xA0, der(0x02, new byte[]{0x02})),
                der(0x02, serial),
                sigAlg,
                name,
                validity,
                name,
                kp.getPublic().getEncoded());

        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);

        byte[] cert = seq(tbs, sigAlg, der(0x03, bits));
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(cert));
    }

    private static byte[] generalized(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(d).getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] seq(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return der(0x30, o.toByteArray());
    }

    private static byte[] der(int tag, byte[] content) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int n = content.length;
        if (n < 0x80) {
            o.write(n);
        } else if (n < 0x100) {
            o.write(0x81);
            o.write(n);
        } else if (n < 0x10000) {
            o.write(0x82);
            o.write(n >> 8);
            o.write(n);
        } else {
            o.write(0x83);
            o.write(n >> 16);
            o.write(n >> 8);
            o.write(n);
        }
        o.write(content, 0, n);
        return o.toByteArray();
    }
}
