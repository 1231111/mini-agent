package com.miniagent.memoryservice.eval;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.lang.management.ManagementFactory;

/**
 * 进程内离线回放（前缀 {@code eval-worker}）。
 * 规划模型应与客户端规划模型一致，否则回放结论不代表客户端。
 */
@ConfigurationProperties(prefix = "eval-worker")
public record EvalWorkerProperties(String workerId, Model planner, Model judge,
                                   String judgeVersion) {
    private static final String DEFAULT_JUDGE_VERSION = "pairwise-swap-v1";

    public EvalWorkerProperties {
        workerId = blank(workerId) ? ManagementFactory.getRuntimeMXBean().getName() : workerId;
        planner = planner == null ? new Model(null, null, null, 0) : planner;
        judge = judge == null ? new Model(null, null, null, 0) : judge;
        judgeVersion = blank(judgeVersion) ? DEFAULT_JUDGE_VERSION : judgeVersion;
    }

    public record Model(String baseUrl, String apiKey, String model, int timeoutSeconds) {
        private static final int DEFAULT_TIMEOUT_SECONDS = 120;

        public Model {
            timeoutSeconds = timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_SECONDS : timeoutSeconds;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
