package com.termux.installer;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Sha256 {

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private Sha256() {}

    public static String hexOfFile(Context context, File file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            try (InputStream in = new FileInputStream(file)) {
                int n;
                while ((n = in.read(buffer)) != -1) {
                    md.update(buffer, 0, n);
                }
            }
            return bytesToHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(context.getString(com.termux.R.string.error_sha256_not_available), e);
        }
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int v = b & 0xff;
            sb.append(HEX_CHARS[v >>> 4]).append(HEX_CHARS[v & 0xf]);
        }
        return sb.toString();
    }
}
