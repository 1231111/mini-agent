package com.miniagent.web;

import com.miniagent.agent.planner.PlannerMetrics;
import com.miniagent.agent.planner.PlannerProperties;
import com.miniagent.agent.planner.PlannerStateStore;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Planner 健康检查端点，提供子系统状态和指标。
 *
 * <p>提供两种访问方式：
 * <ul>
 *   <li>Spring Boot Actuator: /actuate/health/planner（自动集成到 /actuate/health）</li>
 *   <li>REST API: /api/planner/health（返回详细 JSON）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/planner")
public class HealthController implements HealthIndicator {

    @Autowired
    private PlannerMetrics metrics;
    @Autowired
    private PlannerProperties properties;
    @Autowired
    private ObjectProvider<PlannerStateStore> stateStoreProvider;

    private PlannerStateStore stateStore;

    @PostConstruct
    void init() {
        this.stateStore = stateStoreProvider.getIfAvailable();
    }

    /**
     * Actuator 健康检查（集成到 /actuate/health）。
     */
    @Override
    public Health health() {
        Map<String, Object> details = buildDetails();
        boolean healthy = isHealthy(details);
        return healthy ? Health.up().withDetails(details).build()
                : Health.down().withDetails(details).build();
    }

    /**
     * REST 健康检查（返回详细 JSON）。
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> details = buildDetails();
        body.put("status", isHealthy(details) ? "UP" : "DOWN");
        body.put("components", details);
        body.put("metrics", metrics.snapshot());
        return ResponseEntity.ok(body);
    }

    /**
     * Planner 指标快照。
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Long>> metrics() {
        return ResponseEntity.ok(metrics.snapshot());
    }

    private Map<String, Object> buildDetails() {
        Map<String, Object> details = new LinkedHashMap<>();

        // Planner 配置状态
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", properties.isEnabled());
        config.put("maxOuterRounds", properties.getMaxOuterRounds());
        config.put("proposalBatchSize", properties.getProposalBatchSize());
        config.put("plannerModel", properties.getPlannerModelName() != null ? "configured" : "fallback");
        details.put("config", config);

        // Planner 状态存储
        Map<String, Object> store = new LinkedHashMap<>();
        if (stateStore != null) {
            store.put("available", true);
            try {
                store.put("sessionCount", stateStore.activeSessionCount());
            } catch (Exception e) {
                store.put("available", false);
                store.put("error", e.getMessage());
            }
        } else {
            store.put("available", false);
        }
        details.put("stateStore", store);

        // 运行时指标
        Map<String, Object> runtime = new LinkedHashMap<>();
        Map<String, Long> snap = metrics.snapshot();
        runtime.put("proposalsTotal", snap.getOrDefault("proposals", 0L));
        runtime.put("nodesSuccessTotal", snap.getOrDefault("nodesSuccess", 0L));
        runtime.put("nodesFailedTotal", snap.getOrDefault("nodesFailed", 0L));
        runtime.put("recoveriesTotal", snap.getOrDefault("recoveries", 0L));
        runtime.put("decompositionsTotal", snap.getOrDefault("decompositions", 0L));
        details.put("runtime", runtime);

        return details;
    }

    private boolean isHealthy(Map<String, Object> details) {
        @SuppressWarnings("unchecked")
        Map<String, Object> store = (Map<String, Object>) details.get("stateStore");
        return store != null && Boolean.TRUE.equals(store.get("available"));
    }
}
