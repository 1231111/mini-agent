package com.miniagent.account.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码哈希。
 *
 * <p>只引了 {@code spring-security-crypto}，没引 {@code spring-boot-starter-security}，
 * 所以 {@code PasswordEncoder} 没有自动配置，必须在这里显式声明。
 * 这是个刻意的取舍：账号服务不签 token、没有浏览器会话，整条 security 过滤链
 * 在这个服务里没有用武之地，引进来只会多一层"为什么这些请求被拦了"的猜测空间。
 *
 * <p>强度由 {@code agent.account.bcrypt-strength} 控制。它与云端 agent 的
 * {@code agent.auth.bcrypt-strength} 是<b>两个独立的值</b> —— 这一点要清楚：
 * 同一个账号的密码只由本服务校验（云端 agent 从不验密码，它只验 token），
 * 所以两处强度不同不会造成"在 A 能登录、在 B 登不上"。
 * 但 prod 档两处都必须是 12，见各自的 application-prod.yml。
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder(
            @Value("${agent.account.bcrypt-strength:10}") int strength) {
        return new BCryptPasswordEncoder(strength);
    }
}
