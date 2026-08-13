package com.miniagent.agent.memory.lifecycle;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.memory.entity.AgentWorkingMemoryEntity;
import com.miniagent.agent.memory.repository.AgentWorkingMemoryRepository;
import com.miniagent.memory.model.WorkingMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 工作记忆管理器：当前任务状态的 CRUD。
 * Redis 缓存 + MySQL 持久化（write-through）。
 */
@Service
public class WorkingMemoryManager {

    private static final Logger log = LoggerFactory.getLogger(WorkingMemoryManager.class);
    private static final String REDIS_PREFIX = "wm:";
    private static final long TTL_HOURS = 24;

    @Autowired
    private AgentWorkingMemoryRepository repository;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 获取工作记忆（Redis 优先，MySQL 兜底）。
     */
    public WorkingMemory get(String sessionId) {
        // 尝试 Redis
        if (redisTemplate != null) {
            try {
                String json = redisTemplate.opsForValue().get(REDIS_PREFIX + sessionId);
                if (json != null) {
                    return objectMapper.readValue(json, WorkingMemory.class);
                }
            } catch (Exception e) {
                log.debug("Redis 读取工作记忆失败: {}", e.getMessage());
            }
        }

        // 回退 MySQL
        return repository.findById(sessionId).map(this::toModel).orElse(null);
    }

    /**
     * 保存工作记忆（write-through: Redis + MySQL）。
     */
    @Transactional
    public void save(WorkingMemory wm) {
        wm.setUpdatedAt(System.currentTimeMillis());

        // MySQL
        AgentWorkingMemoryEntity entity = toEntity(wm);
        repository.save(entity);

        // Redis
        if (redisTemplate != null) {
            try {
                String json = objectMapper.writeValueAsString(wm);
                redisTemplate.opsForValue().set(REDIS_PREFIX + wm.getSessionId(), json, TTL_HOURS, TimeUnit.HOURS);
            } catch (Exception e) {
                log.debug("Redis 写入工作记忆失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 更新工作记忆（合并更新）。
     */
    @Transactional
    public void update(String sessionId, WorkingMemory update) {
        WorkingMemory existing = get(sessionId);
        if (existing == null) {
            update.setSessionId(sessionId);
            save(update);
            return;
        }

        // 合并字段
        if (update.getGoal() != null) existing.setGoal(update.getGoal());
        if (update.getPlanId() != null) existing.setPlanId(update.getPlanId());
        if (update.getCurrentTaskId() != null) existing.setCurrentTaskId(update.getCurrentTaskId());
        if (update.getCompletedTasks() != null && !update.getCompletedTasks().isEmpty()) {
            for (String t : update.getCompletedTasks()) {
                if (!existing.getCompletedTasks().contains(t)) {
                    existing.addCompletedTask(t);
                }
            }
        }
        if (update.getFailedTasks() != null && !update.getFailedTasks().isEmpty()) {
            for (String t : update.getFailedTasks()) {
                if (!existing.getFailedTasks().contains(t)) {
                    existing.addFailedTask(t);
                }
            }
        }
        if (update.getVariables() != null) {
            existing.getVariables().putAll(update.getVariables());
        }
        if (update.getConstraints() != null) {
            existing.getConstraints().putAll(update.getConstraints());
        }
        if (update.getArtifacts() != null) {
            existing.getArtifacts().putAll(update.getArtifacts());
        }
        if (update.getStatus() != null) existing.setStatus(update.getStatus());

        save(existing);
    }

    /**
     * 删除工作记忆。
     */
    @Transactional
    public void delete(String sessionId) {
        repository.deleteById(sessionId);
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(REDIS_PREFIX + sessionId);
            } catch (Exception e) {
                log.debug("Redis 删除工作记忆失败: {}", e.getMessage());
            }
        }
    }

    private WorkingMemory toModel(AgentWorkingMemoryEntity entity) {
        WorkingMemory wm = new WorkingMemory();
        wm.setSessionId(entity.getSessionId());
        wm.setTenantId(entity.getTenantId());
        wm.setUserId(entity.getUserId());
        wm.setProjectId(entity.getProjectId());
        wm.setGoal(entity.getGoal());
        wm.setPlanId(entity.getPlanId());
        wm.setCurrentTaskId(entity.getCurrentTaskId());
        wm.setStatus(entity.getStatus());
        wm.setCompletedTasks(parseJsonList(entity.getCompletedTasksJson()));
        wm.setFailedTasks(parseJsonList(entity.getFailedTasksJson()));
        wm.setVariables(parseJsonMap(entity.getVariablesJson()));
        wm.setConstraints(parseJsonMap(entity.getConstraintsJson()));
        wm.setArtifacts(parseJsonMap(entity.getArtifactsJson()));
        wm.setUpdatedAt(entity.getUpdatedAt() != null ?
            entity.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : 0);
        return wm;
    }

    private AgentWorkingMemoryEntity toEntity(WorkingMemory wm) {
        AgentWorkingMemoryEntity entity = new AgentWorkingMemoryEntity();
        entity.setSessionId(wm.getSessionId());
        entity.setTenantId(wm.getTenantId());
        entity.setUserId(wm.getUserId());
        entity.setProjectId(wm.getProjectId());
        entity.setGoal(wm.getGoal());
        entity.setPlanId(wm.getPlanId());
        entity.setCurrentTaskId(wm.getCurrentTaskId());
        entity.setStatus(wm.getStatus());
        entity.setCompletedTasksJson(writeJson(wm.getCompletedTasks()));
        entity.setFailedTasksJson(writeJson(wm.getFailedTasks()));
        entity.setVariablesJson(writeJson(wm.getVariables()));
        entity.setConstraintsJson(writeJson(wm.getConstraints()));
        entity.setArtifactsJson(writeJson(wm.getArtifacts()));
        return entity;
    }

    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String writeJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
