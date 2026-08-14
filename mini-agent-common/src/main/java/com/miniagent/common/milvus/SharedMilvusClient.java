package com.miniagent.common.milvus;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 共享 Milvus 连接（记忆向量 / 会话历史向量共用）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.memory.vector.backend", havingValue = "milvus")
public class SharedMilvusClient {

    @Value("${agent.memory.vector.milvus.uri:http://127.0.0.1:19530}")
    private String uri;
    @Value("${agent.memory.vector.milvus.token:}")
    private String token;

    private volatile MilvusClientV2 client;

    public synchronized MilvusClientV2 get() {
        if (client == null) {
            ConnectConfig.ConnectConfigBuilder b = ConnectConfig.builder().uri(uri);
            if (StringUtils.isNotBlank(token)) {
                b.token(token);
            }
            client = new MilvusClientV2(b.build());
            log.info("SharedMilvusClient 已连接 uri={}", uri);
        }
        return client;
    }

    @PreDestroy
    void close() {
        if (client != null) {
            try { client.close(5); } catch (Exception ignored) {}
        }
    }
}
