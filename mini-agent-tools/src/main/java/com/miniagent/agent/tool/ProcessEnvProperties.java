package com.miniagent.agent.tool;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.TreeSet;

/**
 * 把 {@code agent.tools.env-passthrough} 接到 {@link ProcessEnv} 的静态白名单上。
 *
 * <p>子进程默认只继承一份最小环境（避免密钥随 shell / MCP 服务外流）。
 * 某些部署里 CLI 需要自己的变量（例如某个私有 registry 的 token），
 * 那就显式列出来 —— 关键是"放行了什么"必须是一行可审计的配置，
 * 而不是"默认全给、出了问题再回头查"。</p>
 */
@Slf4j
@Component
public class ProcessEnvProperties {

    @Value("${agent.tools.env-passthrough:}")
    private String passthrough;

    @PostConstruct
    void apply() {
        ProcessEnv.allowKeys(passthrough);
        log.info("子进程环境白名单: {} 项{}", ProcessEnv.allowedKeys().size(),
                passthrough == null || passthrough.isBlank()
                        ? ""
                        : "；额外放行=[" + String.join(", ", new TreeSet<>(java.util.Arrays.asList(
                                passthrough.split("[,\\s]+")))) + "]");
    }
}
