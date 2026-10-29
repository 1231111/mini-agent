package com.miniagent.memoryservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 独立记忆服务入口。
 *
 * <p>当前过渡阶段扫描 app 中的记忆实现并复用其实体/Repository；HTTP、认证和进程边界
 * 已经独立，持久化实现下沉后只需移除这些扫描范围和 app 依赖。</p>
 */
@SpringBootApplication(scanBasePackages = {
        "com.miniagent.memoryservice",
        "com.miniagent.agent.memory"
})
@EntityScan("com.miniagent.agent.memory.entity")
@EnableJpaRepositories("com.miniagent.agent.memory.repository")
public class MemoryServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MemoryServiceApplication.class, args);
    }
}
