package com.miniagent.web;

import com.miniagent.agent.mcp.McpToolBridge;
import com.miniagent.agent.memory.entity.AgentEpisodeEntity;
import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.agent.memory.entity.AgentProcedureEntity;
import com.miniagent.agent.memory.entity.AgentSemanticFactEntity;
import com.miniagent.agent.memory.repository.AgentEpisodeRepository;
import com.miniagent.agent.memory.repository.AgentMemoryEntryRepository;
import com.miniagent.agent.memory.repository.AgentProcedureRepository;
import com.miniagent.agent.memory.repository.AgentSemanticFactRepository;
import com.miniagent.agent.permission.SessionPermissionStore;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.common.ApiResponse;
import com.miniagent.common.ErrorCode;
import com.miniagent.config.OpenAiModelProperties;
import com.miniagent.config.security.AuthenticatedUser;
import com.miniagent.config.security.JwtSessionService;
import com.miniagent.config.security.SessionAuthorizationService;
import com.miniagent.config.service.UserModelConfigService;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Shell 外壳的<strong>数据接口</strong>。
 *
 * <p>与 {@link ShellPageController} 的页面骨架严格分开：页面导航带不了 Authorization 头，
 * 所以页面路由无条件放行；这里的每个 {@code /api/shell/**} 都先 {@link #resolveUserId}
 * 再按 sessionId 做归属校验（{@link SessionAuthorizationService#requireOwner}），
 * 未登录返回 {@link ErrorCode#AUTH_NOT_AUTHENTICATED}，越权由全局异常处理器映射为 403，
 * sessionId 为空返回 {@link ErrorCode#CONFIG_INVALID}。
 *
 * <p>所有返回值都用统一包装 {@link ApiResponse}。数据一律取自现有 bean / repository 的
 * 真实状态，空数据时给出结构完整的空值而不是抛异常。
 */
@RestController
public class ShellApiController {

    @Autowired
    private JwtSessionService jwtSessionService;
    @Autowired
    private SessionAuthorizationService sessionAuthorization;
    @Autowired
    private SessionPermissionStore permissionStore;

    @Autowired(required = false)
    private UserModelConfigService userModelConfigService;
    @Autowired(required = false)
    private OpenAiModelProperties openAiModelProperties;
    @Autowired(required = false)
    private ToolRegistry toolRegistry;
    @Autowired(required = false)
    private McpToolBridge mcpToolBridge;
    @Autowired(required = false)
    private AgentMemoryEntryRepository memoryEntryRepository;
    @Autowired(required = false)
    private AgentSemanticFactRepository semanticFactRepository;
    @Autowired(required = false)
    private AgentProcedureRepository procedureRepository;
    @Autowired(required = false)
    private AgentEpisodeRepository episodeRepository;

    // ==================== GET /api/shell/status ====================

    @GetMapping(value = "/api/shell/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> status(HttpServletRequest request) {
        Long userId = resolveUserId(request);
        if (Objects.isNull(userId)) {
            return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("modelName", resolveModelName(userId));
        data.put("toolsAvailable", countTools());
        data.put("memoryState", memoryState());
        data.put("status", "运行正常");
        return ApiResponse.ok(data);
    }

    // ==================== 「始终允许」====================

    @PostMapping(value = "/api/shell/permission/always",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<String>> alwaysAllow(
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        Map<String, Object> safeBody = body == null ? Map.of() : body;
        String sessionId = str(safeBody.get("sessionId"));
        ApiResponse<List<String>> denied = authorize(request, sessionId);
        if (denied != null) {
            return denied;
        }
        String tool = str(safeBody.get("tool"));
        if (StringUtils.isBlank(tool)) {
            return ApiResponse.fail(ErrorCode.CONFIG_INVALID, "tool required");
        }
        permissionStore.alwaysAllow(sessionId, tool);
        return ApiResponse.ok(sortedAlways(sessionId));
    }

    @DeleteMapping(value = "/api/shell/permission/always",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<String>> revokeAlwaysAllow(
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        Map<String, Object> safeBody = body == null ? Map.of() : body;
        String sessionId = str(safeBody.get("sessionId"));
        ApiResponse<List<String>> denied = authorize(request, sessionId);
        if (denied != null) {
            return denied;
        }
        String tool = str(safeBody.get("tool"));
        if (StringUtils.isBlank(tool)) {
            return ApiResponse.fail(ErrorCode.CONFIG_INVALID, "tool required");
        }
        permissionStore.revokeAlwaysAllow(sessionId, tool);
        return ApiResponse.ok(sortedAlways(sessionId));
    }

    @GetMapping(value = "/api/shell/permission/always",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<String>> getAlways(
            @RequestParam(required = false) String sessionId,
            HttpServletRequest request) {
        ApiResponse<List<String>> denied = authorize(request, sessionId);
        if (denied != null) {
            return denied;
        }
        return ApiResponse.ok(sortedAlways(sessionId));
    }

    // ==================== GET /api/shell/memory-insights ====================

    @GetMapping(value = "/api/shell/memory-insights",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> memoryInsights(
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false, defaultValue = "30") int limit,
            HttpServletRequest request) {
        Long userId = resolveUserId(request);
        if (Objects.isNull(userId)) {
            return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        }
        // sessionId 可选：给了就是「这个会话命中了什么记忆」，不给就是「这个租户一共有什么」。
        // 两种都是真实数据，页面按用途各取一种，不用为了凑 sessionId 去猜一个别人的会话。
        boolean scoped = StringUtils.isNotBlank(sessionId);
        if (scoped) {
            sessionAuthorization.requireOwner(userId, sessionId);
        }

        String tenant = resolveTenant(request, userId);
        List<AgentEpisodeEntity> episodes = scoped
                ? nonNullList(episodeRepository == null ? null
                        : episodeRepository.findBySessionId(sessionId))
                : List.of();

        long memoryCount = memoryEntryRepository == null ? 0L
                : memoryEntryRepository.countByTenantIdAndStatus(
                        tenant, AgentMemoryEntryEntity.Status.ACTIVE);
        long factCount = semanticFactRepository == null ? 0L
                : semanticFactRepository.countByTenantId(tenant);
        long procedureCount = procedureRepository == null ? 0L
                : procedureRepository.countByTenantId(tenant);

        List<AgentSemanticFactEntity> facts = nonNullList(
                semanticFactRepository == null ? null
                        : semanticFactRepository.findByTenantIdAndScopeTypeAndScopeId(
                                tenant, AgentMemoryEntryEntity.ScopeType.USER,
                                String.valueOf(userId)));
        List<AgentProcedureEntity> procedures = nonNullList(
                procedureRepository == null ? null
                        : procedureRepository.findByTenantIdAndScopeTypeAndScopeId(
                                tenant, AgentMemoryEntryEntity.ScopeType.USER,
                                String.valueOf(userId)));

        // 上限由调用方给：检查器只要看几条，知识库整页要拉满一屏。
        // 夹在 1..500，避免有人传 0 或一亿把响应撑爆。
        int cap = Math.max(1, Math.min(500, limit));
        List<Map<String, Object>> related = new ArrayList<>();
        for (AgentEpisodeEntity ep : episodes) {
            if (related.size() >= cap) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "episode");
            item.put("id", ep.getId());
            item.put("summary", safe(ep.getTaskSummary()));
            item.put("outcome",
                    ep.getOutcome() == null ? "" : ep.getOutcome().name());
            item.put("sessionId", safe(ep.getSessionId()));
            related.add(item);
        }
        for (AgentSemanticFactEntity f : facts) {
            if (related.size() >= cap) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "fact");
            item.put("subject", safe(f.getSubject()));
            item.put("predicate", safe(f.getPredicate()));
            item.put("object", safe(f.getObjectValue()));
            related.add(item);
        }
        for (AgentProcedureEntity p : procedures) {
            if (related.size() >= cap) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "procedure");
            item.put("name", safe(p.getName()));
            item.put("version", p.getProcedureVersion());
            related.add(item);
        }

        Set<String> tags = new TreeSet<>();
        for (AgentEpisodeEntity ep : episodes) {
            if (ep.getOutcome() != null) {
                tags.add(ep.getOutcome().name());
            }
        }
        for (AgentSemanticFactEntity f : facts) {
            addTag(tags, f.getSubject());
        }
        for (AgentProcedureEntity p : procedures) {
            addTag(tags, p.getName());
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("memoryCount", memoryCount);
        stats.put("factCount", factCount);
        stats.put("procedureCount", procedureCount);
        stats.put("episodeCount", (long) episodes.size());
        stats.put("relatedCount", (long) related.size());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", scoped ? sessionId : "");
        data.put("saved", !episodes.isEmpty());
        data.put("related", related);
        data.put("tags", new ArrayList<>(tags));
        data.put("stats", stats);
        return ApiResponse.ok(data);
    }

    // ==================== GET /api/shell/tools ====================

    /**
     * 内置工具与 MCP 工具的完整清单，供「应用 / 工具」页展示。
     *
     * <p>名字与描述都取自 {@link ToolRegistry#getSpecifications()} 与
     * {@link McpToolBridge#registeredToolNames()}，不在前端维护第二份工具名单 ——
     * 那种名单一旦和注册表脱钩，用户看到的「可用工具」就是假的。
     */
    @GetMapping(value = "/api/shell/tools", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> tools(HttpServletRequest request) {
        Long userId = resolveUserId(request);
        if (Objects.isNull(userId)) {
            return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        }
        List<Map<String, Object>> builtin = new ArrayList<>();
        if (toolRegistry != null) {
            for (var spec : toolRegistry.getSpecifications()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", spec.name());
                item.put("description", safe(spec.description()));
                builtin.add(item);
            }
        }
        List<Map<String, Object>> mcp = new ArrayList<>();
        if (mcpToolBridge != null) {
            for (String name : mcpToolBridge.registeredToolNames()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", name);
                item.put("description", "");
                mcp.add(item);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("builtin", builtin);
        data.put("mcp", mcp);
        data.put("builtinCount", builtin.size());
        data.put("mcpCount", mcp.size());
        data.put("total", countTools());
        return ApiResponse.ok(data);
    }

    // ==================== 内部辅助 ====================

    /**
     * 与现有端点同款取当前用户：优先读过滤器放在 request 上的 userId，
     * 否则从 Bearer 头解析。未登录返回 null。
     */
    private Long resolveUserId(HttpServletRequest request) {
        Long fromAttr = JwtSessionService.userIdFromRequest(request);
        if (Objects.nonNull(fromAttr)) {
            return fromAttr;
        }
        return jwtSessionService == null
                ? null : jwtSessionService.resolveUserIdAndRefresh(request);
    }

    /**
     * 鉴权 + sessionId 非空 + 会话归属校验。
     * 返回 null 表示全部通过；否则是应直接返回给前端的失败响应。
     * 归属校验沿用 {@link SessionAuthorizationService#requireOwner}，越权时抛
     * AccessDeniedException（由全局异常处理器转成 403），与现有权限端点一致。
     */
    private ApiResponse<List<String>> authorize(
            HttpServletRequest request, String sessionId) {
        Long userId = resolveUserId(request);
        if (Objects.isNull(userId)) {
            return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        }
        if (StringUtils.isBlank(sessionId)) {
            return ApiResponse.fail(ErrorCode.CONFIG_INVALID, "sessionId required");
        }
        sessionAuthorization.requireOwner(userId, sessionId);
        return null;
    }

    private List<String> sortedAlways(String sessionId) {
        return permissionStore.alwaysAllowedTools(sessionId)
                .stream().sorted().toList();
    }

    /**
     * 模型名与 {@code GET /api/model} 同源：先取用户生效配置的 modelName，
     * 再用 {@link OpenAiModelProperties}（读 langchain4j 的 model-name 真实值）兜底，
     * 全程不硬编码具体模型名。
     */
    private String resolveModelName(Long userId) {
        if (userModelConfigService != null) {
            try {
                Map<String, Object> view = userModelConfigService.getView(userId);
                Object current = view == null ? null : view.get("current");
                if (current instanceof Map<?, ?> cur) {
                    Object name = cur.get("modelName");
                    if (name != null && !String.valueOf(name).isBlank()) {
                        return String.valueOf(name);
                    }
                }
            } catch (RuntimeException ignored) {
                // 落到全局配置兜底
            }
        }
        if (openAiModelProperties != null) {
            String chat = openAiModelProperties.getChatModelName();
            if (chat != null && !chat.isBlank()) {
                return chat;
            }
            String streaming = openAiModelProperties.getStreamingModelName();
            if (streaming != null && !streaming.isBlank()) {
                return streaming;
            }
        }
        return "未配置";
    }

    /** 内置工具 + MCP 工具，按名字去重后的总数。 */
    private int countTools() {
        Set<String> names = new HashSet<>();
        if (toolRegistry != null && toolRegistry.getToolNames() != null) {
            names.addAll(toolRegistry.getToolNames());
        }
        if (mcpToolBridge != null && mcpToolBridge.registeredToolNames() != null) {
            names.addAll(mcpToolBridge.registeredToolNames());
        }
        return names.size();
    }

    /** JVM 堆使用比例映射成「正常 / 偏高 / 紧张」。 */
    private String memoryState() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long max = heap.getMax();
        long used = heap.getUsed();
        if (max <= 0L || used < 0L) {
            return "正常";
        }
        double ratio = (double) used / (double) max;
        if (ratio >= 0.9) {
            return "紧张";
        }
        if (ratio >= 0.7) {
            return "偏高";
        }
        return "正常";
    }

    /** 租户取过滤器写入的 principal，缺失时退回 userId（不因此报错）。 */
    private String resolveTenant(HttpServletRequest request, Long userId) {
        Object principal = request == null ? null
                : request.getAttribute(JwtSessionService.ATTR_PRINCIPAL);
        if (principal instanceof AuthenticatedUser user) {
            return user.tenantKey();
        }
        return String.valueOf(userId);
    }

    private static <T> List<T> nonNullList(List<T> value) {
        return value == null ? List.of() : value;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static void addTag(Set<String> tags, String s) {
        if (s != null && !s.isBlank()) {
            tags.add(s.trim());
        }
    }
}
