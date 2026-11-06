package com.miniagent.account.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.account.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 记忆令牌密钥的启动校验。
 *
 * <h3>为什么需要这个测试</h3>
 * 密钥没配时，登录接口会在签发令牌那一步抛 {@code IllegalStateException}，被全局异常处理器
 * 包成 {@code SYSTEM.01.01 系统内部错误}。运维在客户端只能看到"系统内部错误"，查不到真因。
 * 这里把校验提前到启动阶段，并用测试锁住"报错信息必须点出环境变量名"这件事。
 */
class MemoryAccessTokenServiceConfigurationTest {

    private static final String VALID_KEY = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    private MemoryAccessTokenService service(String keyMaterial, String internalKey) {
        return new MemoryAccessTokenService(mock(UserRepository.class), new ObjectMapper(),
                Clock.systemUTC(), keyMaterial, internalKey);
    }

    @Test
    @DisplayName("密钥为空时启动失败，并且报错点出 ACCOUNT_MEMORY_TOKEN_KEY")
    void blankKeyFailsStartupWithActionableMessage() {
        MemoryAccessTokenService service = service("", "internal-key");

        assertThatThrownBy(service::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACCOUNT_MEMORY_TOKEN_KEY")
                .hasMessageContaining("32 字节");
    }

    @Test
    @DisplayName("密钥不是 32 字节的 Base64 时启动失败")
    void malformedKeyFailsStartup() {
        String tooShort = Base64.getEncoder().encodeToString("short".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MemoryAccessTokenService service = service(tooShort, "internal-key");

        assertThatThrownBy(service::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 字节");
    }

    @Test
    @DisplayName("密钥合法时正常启动；内部密钥缺失只告警不阻断")
    void validKeyStartsEvenWithoutInternalKey() {
        MemoryAccessTokenService service = service(VALID_KEY, "");

        assertThatCode(service::validateConfiguration).doesNotThrowAnyException();
        assertThat(VALID_KEY).isNotBlank();
    }
}
