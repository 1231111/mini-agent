package com.miniagent.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.MiniAgentSpringbootApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 向拉起当前进程的桌面壳上报经随机 nonce 绑定的动态监听端口。 */
@Component
@Profile("desktop")
public class DesktopReadyReporter {

    static final String READY_PREFIX = "MINIAGENT_READY ";
    private static final String DEVELOPMENT_BUILD_ID = "development";

    private final ObjectMapper objectMapper;
    private final WebServerApplicationContext webContext;
    private final String nonce;

    public DesktopReadyReporter(
            ObjectMapper objectMapper,
            WebServerApplicationContext webContext,
            @Value("${MINI_AGENT_DESKTOP_NONCE:}") String nonce) {
        this.objectMapper = objectMapper;
        this.webContext = webContext;
        this.nonce = nonce;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reportReady() throws JsonProcessingException {
        if (nonce.isBlank()) {
            return;
        }
        int port = webContext.getWebServer().getPort();
        System.out.println(readyLine(port));
        System.out.flush();
    }

    String readyLine(int port) throws JsonProcessingException {
        ReadyMessage ready = new ReadyMessage(port, nonce, resolveBuildId());
        return READY_PREFIX + objectMapper.writeValueAsString(ready);
    }

    private String resolveBuildId() {
        String version = MiniAgentSpringbootApplication.class
                .getPackage()
                .getImplementationVersion();
        return version == null ? DEVELOPMENT_BUILD_ID : version;
    }

    private record ReadyMessage(int port, String nonce, String buildId) {
    }
}
