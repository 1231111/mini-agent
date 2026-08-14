package com.miniagent.agent.delegate;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.util.*;

/**
 * 角色配置加载器
 * 从 roles.yml 加载角色定义
 */
@Slf4j
@Component
public class RoleLoader {

    private Map<String, RoleConfig> roles = new LinkedHashMap<>();

    @PostConstruct
    public void load() {
        try {
            Yaml yaml = new Yaml(new SafeConstructor(new org.yaml.snakeyaml.LoaderOptions()));
            ClassPathResource resource = new ClassPathResource("roles.yml");

            if (!resource.exists()) {
                log.warn("roles.yml 不存在，角色系统将使用默认配置");
                return;
            }

            try (InputStream is = resource.getInputStream()) {
                Map<String, Object> root = yaml.load(is);
                Map<String, Map<String, Object>> rolesMap =
                        (Map<String, Map<String, Object>>) root.get("roles");

                if (Objects.isNull(rolesMap)) {
                    log.warn("roles.yml 中未找到 roles 配置");
                    return;
                }

                for (Map.Entry<String, Map<String, Object>> entry : rolesMap.entrySet()) {
                    String roleId = entry.getKey();
                    Map<String, Object> roleData = entry.getValue();

                    RoleConfig config = RoleConfig.builder()
                            .id(roleId)
                            .name((String) roleData.get("name"))
                            .description((String) roleData.get("description"))
                            .systemPrompt((String) roleData.get("system_prompt"))
                            .allowedTools(toStringList(roleData.get("allowed_tools")))
                            .build();

                    roles.put(roleId, config);
                    log.info("加载角色: {} - {}", roleId, config.getName());
                }

                log.info("共加载 {} 个角色配置", roles.size());
            }
        } catch (Exception e) {
            log.error("加载 roles.yml 失败", e);
        }
    }

    /**
     * 获取角色配置
     * @param roleId 角色ID
     * @return 角色配置，如果不存在返回null
     */
    public RoleConfig getRole(String roleId) {
        return roles.get(roleId);
    }

    /**
     * 获取所有可用角色
     * @return 角色列表
     */
    public List<RoleConfig> getAllRoles() {
        return new ArrayList<>(roles.values());
    }

    /**
     * 获取所有角色ID
     * @return 角色ID列表
     */
    public List<String> getRoleIds() {
        return new ArrayList<>(roles.keySet());
    }

    /**
     * 检查角色是否存在
     * @param roleId 角色ID
     * @return 是否存在
     */
    public boolean hasRole(String roleId) {
        return roles.containsKey(roleId);
    }

    private List<String> toStringList(Object obj) {
        if (Objects.isNull(obj)) return List.of();
        if (obj instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .toList();
        }
        return List.of();
    }
}
