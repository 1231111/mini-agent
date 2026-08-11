package com.miniagent.web;

import com.miniagent.application.AgentChatApplicationService;
import com.miniagent.agent.core.TokenUsageTracker;
import com.miniagent.config.security.SessionCookieService;
import com.miniagent.config.service.AuthService;
import com.miniagent.config.service.DatabaseConversationStore;
import com.miniagent.config.service.FileStorageService;
import com.miniagent.config.service.UserModelConfigService;
import com.miniagent.config.storage.MediaStorage;
import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.permission.SessionPermissionStore;
import com.miniagent.web.dto.ChatRequest;
import com.miniagent.web.dto.FileAttachment;
import com.miniagent.web.dto.FileRef;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import com.miniagent.agent.web.UploadedDocumentService;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import com.miniagent.config.entity.ChatMessage;
import com.miniagent.config.repository.ChatMessageRepository;
import com.miniagent.config.repository.ChatTaskRepository;
import com.miniagent.config.entity.ChatTask;
import com.miniagent.config.repository.AgentTraceStepRepository;
import com.miniagent.config.entity.AgentTraceStep;

@Controller
public class MiniAgentChatPageController {

    @Autowired
    private  AgentChatApplicationService agentService;
    @Autowired
    private  AuthService authService;
    @Autowired
    private  FileStorageService fileStorageService;
    @Autowired
    private  UploadedDocumentService uploadedDocumentService;
    @Autowired
    private  ChatMessageRepository chatMessageRepository;
    @Autowired
    private  ChatTaskRepository chatTaskRepository;
    @Autowired
    private  AgentTraceStepRepository agentTraceStepRepository;
    @Autowired
    private  com.miniagent.agent.core.SessionStreamHub streamHub;
    @Autowired
    private  SessionCookieService sessionCookieService;
    @Autowired
    private  DatabaseConversationStore conversationStore;
    @Autowired
    private  UserModelConfigService userModelConfigService;
    @Autowired
    private MediaStorage mediaStorage;

    @Value("${file.upload.max-size:52428800}")
    private long maxUploadSizeBytes;
    @Autowired
    private SessionPermissionStore permissionStore;
    @Autowired(required = false)
    private com.miniagent.agent.mcp.McpToolBridge mcpToolBridge;
    @Autowired(required = false)
    private com.miniagent.agent.mcp.McpProperties mcpProperties;

    @GetMapping("/")
    public String showChatPage(HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (userId == null) {
            return "login";
        }
        return "chat";
    }

    // ========== Login / Register / Auth endpoints ==========

    @PostMapping(value = "/api/login", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> login(@RequestBody LoginRequest req, HttpServletResponse response) {
        return authService.login(req.getUsername(), req.getPassword())
                .map(user -> {
                    sessionCookieService.issueSession(response, user.getId(), user.getUsername());
                    return Map.<String, Object>of("success", true, "userId", user.getId(), "username", user.getUsername(), "displayName", user.getDisplayName());
                })
                .orElse(Map.of("success", false, "message", "Invalid credentials"));
    }

    @PostMapping(value = "/api/register", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> register(@RequestBody RegisterRequest req, HttpServletResponse response) {
        return authService.register(req.getUsername(), req.getPassword(), req.getDisplayName())
                .map(user -> {
                    sessionCookieService.issueSession(response, user.getId(), user.getUsername());
                    return Map.<String, Object>of("success", true, "userId", user.getId(), "username", user.getUsername());
                })
                .orElse(Map.of("success", false, "message", "Username already exists or password too short"));
    }

    @GetMapping("/api/logout")
    public String logout(HttpServletResponse response) {
        sessionCookieService.clearSession(response);
        return "redirect:/";
    }


    @PostMapping(value = "/api/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> uploadFile(@RequestParam("file") MultipartFile file,
                                           @RequestParam(value = "sessionId", defaultValue = "default") String sessionId,
                                           HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (userId == null) return Map.of("success", false, "message", "Not authenticated");
        if (file == null || file.isEmpty()) {
            return Map.of("success", false, "message", "文件为空");
        }
        if (file.getSize() > maxUploadSizeBytes) {
            return Map.of("success", false, "message",
                    "文件过大: " + (file.getSize() / 1024 / 1024) + "MB，上限 "
                            + (maxUploadSizeBytes / 1024 / 1024) + "MB");
        }
        try {
            String base64 = java.util.Base64.getEncoder().encodeToString(file.getBytes());
            var saved = fileStorageService.saveFile(userId, sessionId, file.getOriginalFilename(), file.getContentType(), base64);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("filePath", saved.getFilePath());
            resp.put("filename", saved.getOriginalFilename());
            resp.put("mimeType", saved.getMimeType() != null ? saved.getMimeType() : "application/octet-stream");
            resp.put("fileSize", saved.getFileSize());
            if (saved.getExtractedTextPath() != null) {
                resp.put("extractedTextPath", saved.getExtractedTextPath());
            }
            return resp;
        } catch (Exception e) {
            return Map.of("success", false, "message", e.getMessage());
        }
    }

    @GetMapping(value = "/api/auth-status", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> authStatus(HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (userId == null) {
            return Map.of("authenticated", false);
        }
        return authService.getUserById(userId)
                .map(user -> Map.<String, Object>of("authenticated", true, "userId", user.getId(), "username", user.getUsername(), "displayName", user.getDisplayName()))
                .orElse(Map.of("authenticated", false));
    }

    private Long getUserIdFromCookie(HttpServletRequest request) {
        Long fromAttr = SessionCookieService.userIdFromRequest(request);
        if (fromAttr != null) return fromAttr;
        return sessionCookieService.resolveUserId(request);
    }

    /** Ensure session belongs to user (via conversation or prior tasks). */
    private boolean ownsSession(Long userId, String sessionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()) return false;
        if (conversationStore.ownedBy(userId, sessionId)) return true;
        return chatTaskRepository.existsByUserIdAndSessionId(userId, sessionId);
    }

    // ========== 执行中追加消息 ==========

    @PostMapping(value = "/api/chat/inject", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public java.util.Map<String, Object> injectMessage(@RequestBody java.util.Map<String, String> body, HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (userId == null) {
            return java.util.Map.of("success", false, "error", "Not authenticated");
        }
        String sessionId = body.get("sessionId");
        String message = body.get("message");
        if (sessionId == null || sessionId.isBlank() || message == null || message.isBlank()) {
            return java.util.Map.of("success", false, "error", "sessionId and message required");
        }
        if (!ownsSession(userId, sessionId) && !agentService.isTaskRunning(sessionId)) {
            return java.util.Map.of("success", false, "error", "Forbidden");
        }
        boolean ok = streamHub.injectMessage(sessionId, message);
        if (!ok) {
            return java.util.Map.of("success", false, "error", "No active task for this session");
        }
        return java.util.Map.of("success", true);
    }

    // ========== SSE streaming ==========

    @PostMapping(value = "/chat/stream", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SseEmitter chatStreamMultimodal(@RequestBody ChatRequest req, HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (userId == null) {
            SseEmitter emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event().name("error").data("Not authenticated"));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }
        String message = req.getMessage();
        String sessionId = req.getSessionId();
        String role = req.getRole();  // 获取角色选择
        List<String> images = req.getImages() == null ? List.of() : req.getImages();
        List<FileAttachment> files = req.getFiles() == null ? List.of() : req.getFiles();
        StringBuilder queryTask = new StringBuilder(message != null ? message : "");
        // 预上传附件：统一走安全提取 + 侧车 + 上下文预算（docx/pptx/pdf/md 等）
        if (req.getFileRefs() != null && !req.getFileRefs().isEmpty()) {
            String fileCtx = uploadedDocumentService.buildMessageContext(userId, req.getFileRefs());
            if (fileCtx != null && !fileCtx.isBlank()) {
                queryTask.append(fileCtx);
            }
        }
        return agentService.chatStreamMultimodal(userId, sessionId, queryTask.toString(), images, files, role);
    }

    @Data
    public static class LoginRequest {
        private String username;
        private String password;
    }

    @Data
    public static class RegisterRequest {
        private String username;
        private String password;
        private String displayName;
    }

    @GetMapping("/static/images/{filename}")
    @ResponseBody
    public ResponseEntity<?> serveImage(@PathVariable("filename") String filename) {
        try {
            Path baseDir = mediaStorage.generatedDir().toAbsolutePath().normalize();
            Path imagePath = baseDir.resolve(filename).normalize();

            if (!imagePath.startsWith(baseDir)) {
                return ResponseEntity.badRequest().body("Illegal path");
            }
            if (!Files.exists(imagePath)) {
                return ResponseEntity.notFound().build();
            }

            String contentType = Files.probeContentType(imagePath);
            if (contentType == null) contentType = "application/octet-stream";

            String uri = imagePath.toUri().toString();
            UrlResource resource = new UrlResource(uri == null ? "file:///" : uri);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to read image: " + e.getMessage());
        }
    }

    // ========== Session sync API ==========

    @GetMapping(value = "/api/task-status", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> taskStatus(@RequestParam("sessionId") String sessionId,
                                          HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (userId == null) return Map.of("error", "Not authenticated");
        if (!ownsSession(userId, sessionId) && !agentService.isTaskRunning(sessionId)) {
            return Map.of("sessionId", sessionId, "running", false);
        }
        boolean running = agentService.isTaskRunning(sessionId);
        return Map.of("sessionId", sessionId, "running", running);
    }

    /**
     * 重连端点：刷新页面 / 新开浏览器后，挂载到正在运行（或刚结束仍在缓冲）的会话事件流，
     * 先重放已产出内容，再继续接收实时事件。无活动通道时发 "gone" 让前端回退到数据库加载。
     */
    @GetMapping(value = "/chat/stream/attach", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter attachStream(@RequestParam("sessionId") String sessionId,
                                   HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (userId == null) {
            SseEmitter emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event().name("error").data("Not authenticated"));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }
        if (!ownsSession(userId, sessionId) && !agentService.isTaskRunning(sessionId)) {
            SseEmitter emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event().name("error").data("Forbidden"));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }
        // 服务层已处理：建 emitter、挂载（重放+实时）、无通道时发 gone
        return agentService.attachStream(sessionId);
    }

    @GetMapping(value = "/api/conversations", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object listConversations(HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (userId == null) return java.util.Map.of("error", "Not authenticated");
        var tasks = chatTaskRepository.findLatestTaskPerSession(userId);
        return tasks.stream().map(t -> java.util.Map.of(
            "sessionId", t.getSessionId(),
            "title", t.getQuestion().length() > 40 ? t.getQuestion().substring(0, 40) : t.getQuestion(),
            "updatedAt", t.getCreatedAt() != null ? t.getCreatedAt().toString() : ""
        )).toList();
    }

    @GetMapping(value = "/api/conversation", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object getConversation(@RequestParam("sessionId") String sessionId,
                                  HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (userId == null) return Map.of("error", "Not authenticated");
        var conv = agentService.getConversationForUser(userId, sessionId);
        if (conv == null) {
            return Map.of("exists", false, "sessionId", sessionId);
        }
        return conv;
    }

    @GetMapping(value = "/api/conversation/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object getConversationMessages(
            HttpServletRequest request,
            @RequestParam("sessionId") String sessionId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        Long userId = getUserIdFromCookie(request);
        if (userId == null) return java.util.Map.of("error", "Not authenticated");
        var tasks = chatTaskRepository.findByUserIdAndSessionIdOrderByCreatedAtDesc(
                userId, sessionId, org.springframework.data.domain.PageRequest.of(page, size));
        var list = new java.util.ArrayList<>(tasks.getContent());
        java.util.Collections.reverse(list);
        return java.util.Map.of(
            "tasks", list.stream().map(t -> java.util.Map.of(
                "id", t.getId(),
                "question", t.getQuestion(),
                "answer", t.getAnswer() != null ? t.getAnswer() : "",
                "createdAt", t.getCreatedAt() != null ? t.getCreatedAt().toString() : ""
            )).toList(),
            "hasMore", tasks.hasNext()
        );
    }

    @GetMapping(value = "/api/conversation/delete", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public java.util.Map<String, Object> deleteConversationApi(
            HttpServletRequest request,
            @RequestParam("sessionId") String sessionId) {
        Long userId = getUserIdFromCookie(request);
        if (userId == null) return java.util.Map.of("error", "Not authenticated");
        if (!ownsSession(userId, sessionId)) {
            return java.util.Map.of("success", false, "error", "Forbidden");
        }
        chatTaskRepository.deleteByUserIdAndSessionId(userId, sessionId);
        agentService.deleteConversationForUser(userId, sessionId);
        return java.util.Map.of("success", true);
    }

    @GetMapping(value = "/api/token-usage", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object tokenUsage(@RequestParam("sessionId") String sessionId, HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (userId == null) return Map.of("error", "Not authenticated");
        if (!ownsSession(userId, sessionId)) return Map.of("error", "Forbidden");
        return TokenUsageTracker.get(sessionId);
    }

    @GetMapping(value = "/api/token-usage/all", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object allTokenUsage(HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (userId == null) return Map.of("error", "Not authenticated");
        // Per-user aggregate not tracked; return empty to avoid cross-tenant leak
        return Map.of();
    }

    // ========== 轨迹监控 ==========

    @GetMapping("/trace")
    public String showTracePage(HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (userId == null) {
            return "login";
        }
        return "trace";
    }

    @GetMapping(value = "/api/traces", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object getTraces(
            HttpServletRequest request,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "executionId", required = false) String executionId) {
        Long userId = getUserIdFromCookie(request);
        if (userId == null) return java.util.List.of();
        if (sessionId != null && !sessionId.isBlank() && !ownsSession(userId, sessionId)) {
            return java.util.List.of();
        }
        if (executionId != null && !executionId.isEmpty()) {
            return agentTraceStepRepository.findByExecutionIdOrderByTurnIndexAscIdAsc(executionId);
        }
        return agentTraceStepRepository.findBySessionIdOrderByTurnIndexAscIdAsc(sessionId);
    }

    /** 获取某 session 下所有执行任务的列表（按 executionId 分组） */
    @GetMapping(value = "/api/traces/executions", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public java.util.List<java.util.Map<String, Object>> getExecutions(
            @RequestParam("sessionId") String sessionId,
            HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (userId == null || !ownsSession(userId, sessionId)) return java.util.List.of();
        java.util.List<AgentTraceStep> all = agentTraceStepRepository.findBySessionIdOrderByTurnIndexAscIdAsc(sessionId);
        // 按 executionId 分组，返回每个执行的摘要
        java.util.Map<String, java.util.List<AgentTraceStep>> grouped = new java.util.LinkedHashMap<>();
        for (AgentTraceStep s : all) {
            grouped.computeIfAbsent(s.getExecutionId(), k -> new java.util.ArrayList<>()).add(s);
        }
        java.util.List<java.util.Map<String, Object>> result = new java.util.ArrayList<>();
        for (var entry : grouped.entrySet()) {
            java.util.List<AgentTraceStep> steps = entry.getValue();
            AgentTraceStep first = steps.get(0);
            AgentTraceStep last = steps.get(steps.size() - 1);
            String question = first.getUserQuestion();
            // 尝试从 ANSWER 步骤获取答案摘要
            String answerSummary = steps.stream()
                    .filter(s -> "ANSWER".equals(s.getStepType()))
                    .map(s -> s.getContent())
                    .findFirst().orElse("");
            if (answerSummary.length() > 100) answerSummary = answerSummary.substring(0, 100) + "...";
            java.util.Map<String, Object> exec = new java.util.HashMap<>();
            exec.put("executionId", entry.getKey());
            exec.put("sessionId", sessionId);
            exec.put("userQuestion", question != null ? question : "");
            exec.put("answerSummary", answerSummary);
            exec.put("stepCount", steps.size());
            exec.put("startTime", first.getCreatedAt());
            exec.put("endTime", last.getCreatedAt());
            exec.put("status", last.getStatus());
            result.add(exec);
        }
        return result;
    }

    @GetMapping(value = "/api/traces/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public java.util.Map<String, Object> getTraceSummary(
            HttpServletRequest request,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "executionId", required = false) String executionId) {
        Long userId = getUserIdFromCookie(request);
        if (userId == null) return java.util.Map.of("error", "Not authenticated");
        if (sessionId != null && !sessionId.isBlank() && !ownsSession(userId, sessionId)) {
            return java.util.Map.of("error", "Forbidden");
        }
        long totalSteps, totalTurns;
        Long totalDuration;
        java.util.List<Object[]> toolStats;
        java.util.List<Object[]> slowestSteps = java.util.List.of();

        if (executionId != null && !executionId.isEmpty()) {
            totalSteps = agentTraceStepRepository.countByExecutionId(executionId);
            totalTurns = agentTraceStepRepository.countDistinctTurnsByExecutionId(executionId);
            totalDuration = agentTraceStepRepository.sumDurationByExecutionId(executionId);
            toolStats = agentTraceStepRepository.toolStatsByExecutionId(executionId);
            slowestSteps = agentTraceStepRepository.slowestStepsByExecutionId(executionId);
        } else {
            totalSteps = agentTraceStepRepository.countBySessionId(sessionId);
            totalTurns = agentTraceStepRepository.countDistinctTurnsBySessionId(sessionId);
            totalDuration = agentTraceStepRepository.sumDurationBySessionId(sessionId);
            toolStats = agentTraceStepRepository.toolStatsBySessionId(sessionId);
        }

        java.util.List<java.util.Map<String, Object>> tools = new java.util.ArrayList<>();
        for (Object[] row : toolStats) {
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            m.put("name", row[0]);
            m.put("count", row[1]);
            m.put("avgDurationMs", Math.round(((Number) row[2]).doubleValue()));
            tools.add(m);
        }
        java.util.List<java.util.Map<String, Object>> slowest = new java.util.ArrayList<>();
        for (Object[] row : slowestSteps) {
            if (slowest.size() >= 5) break;
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            m.put("stepType", row[0]);
            m.put("toolName", row[1]);
            m.put("durationMs", row[2]);
            slowest.add(m);
        }
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("totalSteps", totalSteps);
        result.put("totalTurns", totalTurns);
        result.put("totalDurationMs", totalDuration != null ? totalDuration : 0);
        result.put("tools", tools);
        result.put("slowestSteps", slowest);
        return result;
    }

    // ========== 权限模式（按会话） ==========

    @GetMapping(value = "/api/permission-mode", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> getPermissionMode(@RequestParam("sessionId") String sessionId,
                                                 HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (userId == null) return Map.of("success", false, "message", "Not authenticated");
        if (sessionId == null || sessionId.isBlank()) {
            return Map.of("success", false, "message", "sessionId required");
        }
        return permissionStore.toView(sessionId);
    }

    @PutMapping(value = "/api/permission-mode", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> putPermissionMode(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (userId == null) return Map.of("success", false, "message", "Not authenticated");
        String sessionId = body == null ? null : String.valueOf(body.getOrDefault("sessionId", ""));
        if (sessionId == null || sessionId.isBlank() || "null".equals(sessionId)) {
            return Map.of("success", false, "message", "sessionId required");
        }
        String action = body.get("action") == null ? "set" : String.valueOf(body.get("action"));
        if ("approve_plan".equalsIgnoreCase(action)) {
            permissionStore.approvePlan(sessionId);
            // 可选：注入一句提示，让运行中的 agent 感知（若无运行中任务则仅更新状态）
            streamHub.injectMessage(sessionId, "【系统】用户已批准 Plan，请按 todo 开始执行写操作与交付。");
            return permissionStore.toView(sessionId);
        }
        if ("grant_ask".equalsIgnoreCase(action)) {
            String tool = body.get("tool") == null ? "" : String.valueOf(body.get("tool"));
            permissionStore.grantAskTool(sessionId, tool);
            streamHub.injectMessage(sessionId, "【系统】用户已批准工具 " + tool + "，请继续。");
            return permissionStore.toView(sessionId);
        }
        String mode = body.get("mode") == null ? "default" : String.valueOf(body.get("mode"));
        permissionStore.setMode(sessionId, PermissionMode.from(mode));
        return permissionStore.toView(sessionId);
    }

    // ========== MCP 状态 ==========

    @GetMapping(value = "/api/mcp/status", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> mcpStatus(HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (userId == null) return Map.of("success", false, "message", "Not authenticated");
        boolean enabled = mcpProperties != null && mcpProperties.isEnabled();
        List<String> tools = mcpToolBridge == null ? List.of() : mcpToolBridge.registeredToolNames();
        int serverCount = mcpProperties == null || mcpProperties.getServers() == null
                ? 0 : mcpProperties.getServers().size();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("enabled", enabled);
        m.put("serverCount", serverCount);
        m.put("registeredTools", tools);
        m.put("toolCount", tools.size());
        return m;
    }

    @PostMapping(value = "/api/mcp/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> mcpRefresh(@RequestBody(required = false) Map<String, Object> body,
                                          HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (userId == null) return Map.of("success", false, "message", "Not authenticated");
        if (mcpToolBridge == null) return Map.of("success", false, "message", "MCP bridge unavailable");
        try {
            String serverId = body == null || body.get("serverId") == null
                    ? null : String.valueOf(body.get("serverId"));
            if (serverId != null && !serverId.isBlank() && !"null".equals(serverId)) {
                return mcpToolBridge.refreshServer(serverId);
            }
            return mcpToolBridge.refreshAll();
        } catch (Exception e) {
            return Map.of("success", false, "message", e.getMessage() == null ? "refresh failed" : e.getMessage());
        }
    }

    // ========== 模型配置（按用户） ==========

    @GetMapping(value = "/api/model-config", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> getModelConfig(HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (userId == null) return Map.of("success", false, "message", "Not authenticated");
        return userModelConfigService.getView(userId);
    }

    @PutMapping(value = "/api/model-config", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> putModelConfig(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (userId == null) return Map.of("success", false, "message", "Not authenticated");
        if (body != null && Boolean.TRUE.equals(body.get("reset"))) {
            return userModelConfigService.resetToDefault(userId);
        }
        String presetId = body == null ? null : strOrNull(body.get("presetId"));
        String baseUrl = body == null ? null : strOrNull(body.get("baseUrl"));
        String modelName = body == null ? null : strOrNull(body.get("modelName"));
        String apiKey = body == null ? null : strOrNull(body.get("apiKey"));
        return userModelConfigService.save(userId, presetId, baseUrl, modelName, apiKey);
    }

    private static String strOrNull(Object v) {
        if (v == null) return null;
        return String.valueOf(v);
    }
}
