package com.miniagent.memory.service;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Profile;

/** 记忆服务独立运行入口；默认仅扫描服务端适配层。 */
@SpringBootApplication(scanBasePackages = "com.miniagent.memory.service")
@Profile("memory-service")
public class MemoryServiceApplication {
    public static void main(String[] args) {
        new SpringApplicationBuilder(MemoryServiceApplication.class)
                .profiles("memory-service")
                .run(args);
    }
}
