package com.miniagent.account;

import com.miniagent.account.service.MemoryAccessTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 启动冒烟：把完整的账号服务上下文装起来。
 *
 * <p>存在的理由是一次真实故障：{@code MemoryAccessTokenService} 的构造器注入了
 * {@code java.time.Clock}，但应用里没有定义这个 bean。所有单元测试都用 mock 构造该服务，
 * 因此编译、单测、打包全部通过，直到部署时容器初始化才抛
 * {@code UnsatisfiedDependencyException} 并退出。
 *
 * <p>这里用 H2 内存库并关掉 Flyway：Flyway 的脚本是 MySQL 方言（{@code ENGINE=InnoDB}），
 * 在 H2 上跑不通；建表交给 Hibernate 的 {@code create-drop}。目的不是验证 SQL，而是验证
 * <b>bean 图能闭合</b> —— 少一个 {@code Clock}、少一个仓储实现，这个测试就会红。
 *
 * <p>默认 {@code webEnvironment=MOCK}：会初始化 Web 上下文（过滤器、安全配置都参与装配），
 * 但不占端口，可以和应用的其他测试并行跑。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:account-context;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        // 32 字节全零的 Base64 占位密钥：构造器只保存、不校验，签发时才解码。
        "agent.account.memory-token-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "agent.account.internal-key=context-loads-internal-key"
})
class AccountContextLoadsTest {

    @Autowired
    private MemoryAccessTokenService memoryAccessTokenService;

    @Test
    void contextLoadsWithMemoryAccessTokenService() {
        assertNotNull(memoryAccessTokenService,
                "Clock 等构造器依赖缺失时，这个 bean 装配不出来");
    }
}
