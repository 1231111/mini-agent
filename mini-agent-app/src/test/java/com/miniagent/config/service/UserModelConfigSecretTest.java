package com.miniagent.config.service;

import com.miniagent.config.entity.UserModelConfig;
import com.miniagent.config.model.AgentModelsProperties;
import com.miniagent.config.model.EffectiveModelSettings;
import com.miniagent.config.repository.UserModelConfigRepository;
import com.miniagent.config.security.SecretCryptoService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 模型密钥"写入加密 / 读取解密"的闭环测试。
 *
 * <p>背景（审计 P1-10 / F-6）：{@code SecretCryptoService} 在修之前**写入路径上没有调用者** ——
 * {@code PUT /api/model} 保存的密钥是明文落库；而它唯一的调用者（启动迁移器）会把明文改写成
 * {@code enc:v1:…}，读路径却把这段密文当 API key 交给 LangChain4j，那个用户的模型调用稳定 401。
 * 也就是说控制既无效又自伤。这些用例锁住两件事：</p>
 * <ol>
 *   <li>{@code save()} 落库的必须是密文（明文不得进 DB）；</li>
 *   <li>{@code resolve()} 必须解回明文交给模型客户端（否则就是 401 那种自伤）。</li>
 * </ol>
 */
class UserModelConfigSecretTest {

    private static final String KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    private static final String OTHER_KEY = Base64.getEncoder()
            .encodeToString("fedcba9876543210fedcba9876543210".getBytes());

    /** 用 Mockito 做内存仓储：save 写进 holder，findByUserId 从 holder 读。 */
    private static UserModelConfigRepository repository(AtomicReference<UserModelConfig> holder) {
        UserModelConfigRepository repo = mock(UserModelConfigRepository.class);
        when(repo.findByUserId(anyLong())).thenAnswer(inv -> Optional.ofNullable(holder.get()));
        when(repo.save(any(UserModelConfig.class))).thenAnswer(inv -> {
            UserModelConfig saved = inv.getArgument(0);
            holder.set(saved);
            return saved;
        });
        when(repo.findAll()).thenAnswer(inv -> holder.get() == null
                ? List.of() : List.of(holder.get()));
        return repo;
    }

    private static SecretCryptoService crypto(String key) {
        SecretCryptoService s = new SecretCryptoService();
        ReflectionTestUtils.setField(s, "encodedKey", key);
        ReflectionTestUtils.invokeMethod(s, "init");
        return s;
    }

    private static UserModelConfigService service(UserModelConfigRepository repo, SecretCryptoService crypto) {
        UserModelConfigService svc = new UserModelConfigService();
        ReflectionTestUtils.setField(svc, "repository", repo);
        ReflectionTestUtils.setField(svc, "crypto", crypto);
        AgentModelsProperties props = new AgentModelsProperties();
        AgentModelsProperties.Preset preset = new AgentModelsProperties.Preset();
        preset.setId("default");
        preset.setLabel("默认");
        preset.setBaseUrl("https://api.example.com/v1");
        preset.setModelName("gpt-4o-mini");
        props.setDefaultPreset("default");
        props.setPresets(List.of(preset));
        ReflectionTestUtils.setField(svc, "modelsProperties", props);
        ReflectionTestUtils.setField(svc, "globalApiKey", "sk-global-platform-key");
        ReflectionTestUtils.setField(svc, "globalBaseUrl", "https://api.example.com/v1");
        ReflectionTestUtils.setField(svc, "globalModelName", "gpt-4o-mini");
        ReflectionTestUtils.setField(svc, "customBaseUrlEnabled", true);
        return svc;
    }

    @Test
    void savedKeyIsStoredEncryptedAndResolvedBackToPlaintext() {
        AtomicReference<UserModelConfig> holder = new AtomicReference<>();
        SecretCryptoService crypto = crypto(KEY);
        UserModelConfigService svc = service(repository(holder), crypto);

        svc.save(1L, "default", null, null, "sk-user-secret-value");

        String stored = holder.get().getCustomApiKey();
        assertNotNull(stored);
        assertTrue(crypto.isEncrypted(stored), "落库必须是密文，明文不得进 DB: " + stored);
        assertFalse(stored.contains("sk-user-secret-value"), "库里不能出现明文片段");

        EffectiveModelSettings eff = svc.getEffective(1L);
        assertEquals("sk-user-secret-value", eff.apiKey(),
                "读取必须解回明文交给模型客户端 —— 否则就是那次'迁移器把 key 换成密文导致稳定 401'的自伤");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> current(Map<String, Object> view) {
        return (Map<String, Object>) view.get("current");
    }

    @Test
    void viewMasksOnlyUserOwnedKey() {
        AtomicReference<UserModelConfig> holder = new AtomicReference<>();
        UserModelConfigService svc = service(repository(holder), crypto(KEY));

        // 没有自定义 key：回显里不得出现平台密钥的任何片段
        Map<String, Object> before = current(svc.getView(1L));
        assertEquals("", before.get("apiKeyMasked"), "平台密钥的后四位不该回显给用户");
        assertEquals("global", before.get("apiKeySource"));
        assertTrue((Boolean) before.get("hasApiKey"), "仍要告诉前端『有可用密钥』");

        svc.save(1L, "default", null, null, "sk-user-secret-value");
        Map<String, Object> after = current(svc.getView(1L));
        assertEquals("user", after.get("apiKeySource"));
        assertTrue(after.get("apiKeyMasked").toString().endsWith("alue"),
                "用户自己的 key 仍按末四位脱敏: " + after.get("apiKeyMasked"));
        assertFalse(after.get("apiKeyMasked").toString().contains("secret"),
                "脱敏不能泄露中段");
    }

    @Test
    void undecryptableStoredKeyFailsWithActionableMessage() {
        AtomicReference<UserModelConfig> holder = new AtomicReference<>();
        // 用 A 加密、用 B 读取：模拟"轮换过 MODEL_CONFIG_ENCRYPTION_KEY"
        UserModelConfigService writer = service(repository(holder), crypto(KEY));
        writer.save(1L, "default", null, null, "sk-user-secret-value");

        UserModelConfigService reader = service(repository(holder), crypto(OTHER_KEY));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> reader.getEffective(1L));
        assertTrue(e.getMessage().contains("重新保存"),
                "报错要给出可执行的处置方式: " + e.getMessage());
    }

    @Test
    void legacyPlaintextRowIsStillReadable() {
        AtomicReference<UserModelConfig> holder = new AtomicReference<>();
        UserModelConfigService svc = service(repository(holder), crypto(KEY));
        UserModelConfig row = new UserModelConfig();
        row.setUserId(1L);
        row.setCustomApiKey("sk-legacy-plain-text");
        holder.set(row);

        assertEquals("sk-legacy-plain-text", svc.getEffective(1L).apiKey(),
                "历史明文行必须仍可读（迁移器负责把它换成密文）");
    }

    @Test
    void clearingKeyRemovesStoredCiphertext() {
        AtomicReference<UserModelConfig> holder = new AtomicReference<>();
        UserModelConfigService svc = service(repository(holder), crypto(KEY));
        svc.save(1L, "default", null, null, "sk-user-secret-value");

        svc.save(1L, "default", null, null, "__CLEAR__");

        assertEquals(null, holder.get().getCustomApiKey(), "清空必须真的清空密文");
        assertEquals("sk-global-platform-key", svc.getEffective(1L).apiKey(),
                "清空后应回退到全局密钥");
    }
}
