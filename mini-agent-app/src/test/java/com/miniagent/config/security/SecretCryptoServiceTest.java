package com.miniagent.config.security;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型密钥静态加密的回归测试。
 *
 * <p>背景：{@code SecretCryptoService} 写得对（AES-256-GCM、每次新 nonce、AAD、缺 key 且存在密文时显式失败），
 * 但在修之前**写入路径上没有任何调用者** —— 用户通过 API 保存的密钥是明文落库的，
 * 而它唯一的调用者（启动迁移器）会把明文改写成 {@code enc:v1:…}，读路径却不解密，
 * 于是那个用户的模型调用稳定 401。也就是说：控制既无效、又自伤。</p>
 *
 * <p>这些用例锁住加解密本身的可逆性、以及"坏密文必须显式失败而不是静默返回垃圾"。</p>
 */
class SecretCryptoServiceTest {

    private static final String KEY_A = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    private static final String KEY_B = Base64.getEncoder()
            .encodeToString("fedcba9876543210fedcba9876543210".getBytes());

    private static SecretCryptoService service(String key) {
        SecretCryptoService s = new SecretCryptoService();
        ReflectionTestUtils.setField(s, "encodedKey", key);
        ReflectionTestUtils.invokeMethod(s, "init");
        return s;
    }

    @Test
    void roundTripUnderSameKey() {
        SecretCryptoService crypto = service(KEY_A);
        String plain = "sk-live-abcdefghijklmnopqrstuvwxyz012345";

        String stored = crypto.encrypt(plain);

        assertTrue(crypto.isEncrypted(stored), "落库值必须带加密前缀: " + stored);
        assertNotEquals(plain, stored, "密文不能等于明文");
        assertFalse(stored.contains(plain), "密文里不能出现明文片段");
        assertEquals(plain, crypto.decrypt(stored), "同一把密钥必须能解回原文");
    }

    @Test
    void samePlaintextProducesDifferentCiphertext() {
        SecretCryptoService crypto = service(KEY_A);
        assertNotEquals(crypto.encrypt("sk-same-value"), crypto.encrypt("sk-same-value"),
                "每次加密必须用新 nonce（否则相同明文产生相同密文，可被枚举比对）");
    }

    @Test
    void plaintextPassesThroughUnchanged() {
        SecretCryptoService crypto = service(KEY_A);
        // 历史明文行仍要能读：迁移只负责把明文换成密文，读取侧不能因为"不是密文"就报错
        assertEquals("sk-legacy-plain", crypto.decrypt("sk-legacy-plain"));
        assertEquals("", crypto.decrypt(""));
    }

    @Test
    void wrongKeyFailsLoudlyInsteadOfReturningGarbage() {
        String stored = service(KEY_A).encrypt("sk-live-value");
        SecretCryptoService other = service(KEY_B);
        assertThrows(IllegalStateException.class, () -> other.decrypt(stored),
                "换过密钥后解密必须显式失败，不能返回乱码让人误以为拿到了 key");
    }

    @Test
    void ciphertextWithoutKeyFailsLoudly() {
        String stored = service(KEY_A).encrypt("sk-live-value");
        SecretCryptoService noKey = service("");
        assertFalse(noKey.isEnabled());
        assertThrows(IllegalStateException.class, () -> noKey.decrypt(stored),
                "存在密文但没配密钥：必须报错，而不是静默降级");
    }

    @Test
    void encryptIsNoOpWithoutKey() {
        SecretCryptoService noKey = service("");
        // 未配置密钥时（本地开发）保持可用：原样返回，不抛异常
        assertEquals("sk-dev-value", noKey.encrypt("sk-dev-value"));
    }
}
