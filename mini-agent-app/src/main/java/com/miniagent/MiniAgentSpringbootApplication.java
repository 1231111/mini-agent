package com.miniagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Spring Boot 入口：扫描 com.miniagent 下的组件并启动内嵌 Web 容器。 */
@SpringBootApplication
@EnableScheduling
public class MiniAgentSpringbootApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiniAgentSpringbootApplication.class, args);
        System.out.println("==================================================");
        System.out.println("🚀 Mini Agent Spring Boot 项目启动成功！");
        System.out.println("这是一个面向 Java 开发者的 Agent 学习项目");
        System.out.println("目标：从0到1理解并实现智能体（Agent）框架");
        System.out.println("==================================================");
    }
}
