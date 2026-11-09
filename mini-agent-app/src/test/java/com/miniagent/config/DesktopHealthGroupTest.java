package com.miniagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桌面档的 readiness 健康组不能引用不存在的 Redis contributor。
 *
 * <p>统一档 {@code application.yml} 把 {@code redis} 列进了 readiness
 * （云端多副本确实要探活 Redis）。桌面档把 {@code RedisAutoConfiguration}
 * exclude 掉了，也没有 RedisHealthIndicator，于是 Spring Boot 在启动时
 * 校验健康组成员失败：{@code Included health contributor 'redis' in group
 * 'readiness' does not exist}，整个后端起不来 —— 2026-11-09 冷启动实测。
 *
 * <p>修法是在 {@code application-desktop.yml} 覆盖这一组，而不是关掉
 * {@code validate-group-membership}：后者会把同类配置漂移永久藏起来。
 */
class DesktopHealthGroupTest {

    private static PropertySource<?> load(String file) throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load(file, new ClassPathResource(file));
        return sources.get(0);
    }

    private static String readinessInclude(String file) throws Exception {
        Object value = load(file).getProperty("management.endpoint.health.group.readiness.include");
        return value == null ? "" : String.valueOf(value);
    }

    @Test
    void baseProfileReallyIncludesRedisSoTheOverrideIsNecessary() throws Exception {
        assertTrue(readinessInclude("application.yml").contains("redis"),
                "若统一档不再包含 redis，桌面档那段覆盖注释就该更新");
    }

    @Test
    void desktopProfileOverridesReadinessGroupWithoutRedis() throws Exception {
        String include = readinessInclude("application-desktop.yml");
        assertEquals("readinessState,db", include);
        assertFalse(include.contains("redis"));
    }

    @Test
    void desktopProfileKeepsRedisHealthIndicatorDisabled() throws Exception {
        assertEquals(Boolean.FALSE,
                load("application-desktop.yml").getProperty("management.health.redis.enabled"));
    }
}
