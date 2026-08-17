package top.nkbe.npatch.patch.util;

import com.android.apksig.ApkVerifier;
import java.io.File;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ApkSignatureHelper {

    public static char[] toChars(byte[] mSignature) {
        byte[] sig = mSignature;
        final int N = sig.length;
        final int N2 = N * 2;
        char[] text = new char[N2];
        for (int j = 0; j < N; j++) {
            byte v = sig[j];
            int d = (v >> 4) & 0xf;
            text[j * 2] = (char) (d >= 10 ? ('a' + d - 10) : ('0' + d));
            d = v & 0xf;
            text[j * 2 + 1] = (char) (d >= 10 ? ('a' + d - 10) : ('0' + d));
        }
        return text;
    }

    /**
     * Extracts raw DER-encoded certificate byte arrays from the APK signing blocks (v1, v2, v3, v4).
     */
    public static List<byte[]> getApkSignatures(String apkFilePath) {
        File apkFile = new File(apkFilePath);
        if (!apkFile.isFile()) {
            return Collections.emptyList();
        }

        try {
            ApkVerifier verifier = new ApkVerifier.Builder(apkFile).build();
            ApkVerifier.Result result = verifier.verify();

            List<X509Certificate> certs = result.getSignerCertificates();
            if (certs == null || certs.isEmpty()) {
                certs = new ArrayList<>();
                for (ApkVerifier.Result.V3SchemeSignerInfo signer : result.getV3SchemeSigners()) {
                    certs.addAll(signer.getCertificates());
                }
                for (ApkVerifier.Result.V2SchemeSignerInfo signer : result.getV2SchemeSigners()) {
                    certs.addAll(signer.getCertificates());
                }
                for (ApkVerifier.Result.V1SchemeSignerInfo signer : result.getV1SchemeSigners()) {
                    X509Certificate cert = signer.getCertificate();
                    if (cert != null) {
                        certs.add(cert);
                    }
                }
            }

            if (!certs.isEmpty()) {
                List<byte[]> list = new ArrayList<>(certs.size());
                for (X509Certificate cert : certs) {
                    list.add(cert.getEncoded());
                }
                return list;
            }
        } catch (Throwable ignored) {
        }

        return Collections.emptyList();
    }

    /**
     * Returns the hex-encoded string of the first signer certificate (matching Android's Signature.toCharsString()).
     */
    public static String getApkSignInfo(String apkFilePath) {
        List<byte[]> signatures = getApkSignatures(apkFilePath);
        if (!signatures.isEmpty()) {
            return new String(toChars(signatures.get(0)));
        }
        return null;
    }
}

