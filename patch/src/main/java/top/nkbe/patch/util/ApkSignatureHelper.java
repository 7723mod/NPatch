package top.nkbe.npatch.patch.util;

import top.nkbe.nza.sign.ApkSignatureReader;
import java.io.File;
import java.util.List;

public class ApkSignatureHelper {

    public static char[] toChars(byte[] mSignature) {
        return ApkSignatureReader.toChars(mSignature);
    }

    /**
     * Extracts raw DER-encoded certificate byte arrays from the APK signing blocks (v3, v2, v1).
     */
    public static List<byte[]> getApkSignatures(String apkFilePath) {
        return ApkSignatureReader.getApkSignatures(new File(apkFilePath));
    }

    /**
     * Returns the hex-encoded string of the first signer certificate (matching Android's Signature.toCharsString()).
     */
    public static String getApkSignInfo(String apkFilePath) {
        return ApkSignatureReader.getApkSignInfo(new File(apkFilePath));
    }
}

