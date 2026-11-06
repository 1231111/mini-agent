package com.miniagent.memory.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 内容哈希、分桶、失败指纹共用的 SHA-256。 */
public final class Hashes {
    private Hashes() {
    }

    public static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** 64 位小写十六进制。 */
    public static String sha256Hex(String text) {
        return HexFormat.of().formatHex(sha256(text));
    }
}
