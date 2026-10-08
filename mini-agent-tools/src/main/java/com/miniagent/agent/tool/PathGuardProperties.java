package com.miniagent.agent.tool;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把 {@code agent.tools.allowed-read-roots} 接到 {@link PathGuard} 的静态放行清单上。
 *
 * <p>默认只允许 workspace / 数据根 / 项目根。确实要读别处（例如挂载在
 * {@code /data/shared} 的资料目录）就显式列出来 —— 关键是"模型能碰主机上的哪些目录"
 * 必须是一行可审计的配置。</p>
 */
@Slf4j
@Component
public class PathGuardProperties {

    @Value("${agent.tools.allowed-read-roots:}")
    private String extraRoots;

    @PostConstruct
    void apply() {
        PathGuard.allowRoots(extraRoots);
        List<java.nio.file.Path> roots = PathGuard.allowedRoots();
        StringBuilder sb = new StringBuilder();
        for (java.nio.file.Path p : roots) {
            sb.append("\n  - ").append(p);
        }
        log.info("文件工具允许的根目录:{}", sb);
    }
}
