package com.miniagent.web;

import com.miniagent.application.AgentChatApplicationService;
import com.miniagent.agent.core.TokenUsageTracker;
import com.miniagent.common.ApiResponse;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.MessageConstants;
import com.miniagent.config.security.SessionCookieService;
import com.miniagent.config.service.AuthService;
import com.miniagent.config.service.DatabaseConversationStore;
import com.miniagent.config.service.FileStorageService;
import com.miniagent.config.service.UserModelConfigService;
import com.miniagent.config.storage.MediaStorage;
import com.miniagent.agent.permission.ConfirmPolicy;
import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.permission.SessionPermissionStore;
import com.miniagent.agent.planner.PlannerStateStore;
import com.miniagent.agent.todo.TaskTodoStore;
import com.miniagent.web.dto.ChatRequest;
import com.miniagent.web.dto.FileAttachment;
import com.miniagent.web.dto.FileRef;
import com.miniagent.web.dto.LoginRequest;
import com.miniagent.web.dto.MediaRef;
import com.miniagent.web.dto.RegisterRequest;
import com.miniagent.agent.web.MultimodalMedia;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import com.miniagent.config.repository.ChatMessageRepository;
import com.miniagent.config.repository.ChatTaskRepository;
import com.miniagent.config.entity.ChatTask;
import com.miniagent.config.repository.AgentTraceStepRepository;
import com.miniagent.config.entity.AgentTraceStep;
import org.apache.commons.lang3.StringUtils;

@Controller
public class MiniAgentChatPageController {

    @Autowired
    private  AgentChatApplicationService agentService;
    @Autowired
    private  com.miniagent.application.ConversationService conversationService;
    @Autowired
    private  com.miniagent.application.ChatStreamingService streamingService;
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
    private  com.miniagent.agent.trace.TraceSseHub traceSseHub;
    @Autowired
    private  com.miniagent.agent.core.SessionEventCenter eventCenter;
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
    @Value("${agent.multimodal.audio-max-bytes:36700160}")
    private long audioMaxBytes;
    @Value("${agent.multimodal.video-max-bytes:36700160}")
    private long videoMaxBytes;
    @Autowired
    private SessionPermissionStore permissionStore;
    @Autowired
    private TaskTodoStore todoStore;
    @Autowired
    private PlannerStateStore plannerStateStore;
    @Autowired(required = false)
    private com.miniagent.agent.mcp.McpToolBridge mcpToolBridge;
    @Autowired(required = false)
    private com.miniagent.agent.mcp.McpProperties mcpProperties;

    @GetMapping("/")
    public String showChatPage(HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (Objects.isNull(userId)) {
            return "login";
        }
        return "chat";
    }

    // ========== Login / Register / Auth endpoints ==========

    @PostMapping(value = "/api/login", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Map<String, Object>> login(@RequestBody LoginRequest req, HttpServletResponse response) {
        return authService.login(req.getUsername(), req.getPassword())
                .map(user -> {
                    sessionCookieService.issueSession(response, user.getId(), user.getUsername());
                    return ApiResponse.ok(Map.<String, Object>of("userId", user.getId(), "username", user.getUsername(), "displayName", user.getDisplayName()));
                })
                .orElse(ApiResponse.fail(ErrorCode.AUTH_LOGIN_FAILED));
    }

    @PostMapping(value = "/api/register", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Map<String, Object>> register(@RequestBody RegisterRequest req, HttpServletResponse response) {
        return authService.register(req.getUsername(), req.getPassword(), req.getDisplayName())
                .map(user -> {
                    sessionCookieService.issueSession(response, user.getId(), user.getUsername());
                    return ApiResponse.ok(Map.<String, Object>of("userId", user.getId(), "username", user.getUsername()));
                })
                .orElse(ApiResponse.fail(ErrorCode.AUTH_USER_EXISTS));
    }

    @GetMapping("/api/logout")
    public String logout(HttpServletResponse response) {
        sessionCookieService.clearSession(response);
        return "redirect:/";
    }


    @PostMapping(value = "/api/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Map<String, Object>> uploadFile(@RequestParam("file") MultipartFile file,
                                                       @RequestParam(value = "sessionId", defaultValue = "default") String sessionId,
                                                       HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (Objects.isNull(userId)) return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        if (Objects.isNull(file)) {
            return ApiResponse.fail(ErrorCode.FILE_EMPTY);
        }
        String originalName = file.getOriginalFilename();
        String contentType = file.getContentType();
        if (MultimodalMedia.looksLikeMediaButUnsupported(originalName, contentType)) {
            return ApiResponse.fail(ErrorCode.FILE_MEDIA_UNSUPPORTED,
                    "仅支持音频 mp3/wav/flac/m4a/ogg 与视频 mp4/mov/avi/wmv");
        }
        String mediaKind = MultimodalMedia.kindOf(originalName, contentType);
        long sizeLimit = maxUploadSizeBytes;
        if (MultimodalMedia.KIND_AUDIO.equals(mediaKind)) sizeLimit = Math.min(sizeLimit, audioMaxBytes);
        else if (MultimodalMedia.KIND_VIDEO.equals(mediaKind)) sizeLimit = Math.min(sizeLimit, videoMaxBytes);
        if (file.getSize() > sizeLimit) {
            return ApiResponse.fail(ErrorCode.FILE_TOO_LARGE,
                    "文件过大: " + (file.getSize() / 1024 / 1024) + "MB，上限 "
                            + (sizeLimit / 1024 / 1024) + "MB");
        }
        try {
            String base64 = Base64.getEncoder().encodeToString(file.getBytes());
            var saved = fileStorageService.saveFile(userId, sessionId, originalName, contentType, base64);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("filePath", saved.getFilePath());
            data.put("filename", saved.getOriginalFilename());
            data.put("mimeType", Optional.ofNullable(saved.getMimeType()).orElse("application/octet-stream"));
            data.put("fileSize", saved.getFileSize());
            if (Objects.nonNull(mediaKind)) data.put("kind", mediaKind);
            if (Objects.nonNull(saved.getExtractedTextPath())) {
                data.put("extractedTextPath", saved.getExtractedTextPath());
            }
            return ApiResponse.ok(data);
        } catch (Exception e) {
            return ApiResponse.fail(ErrorCode.FILE_UPLOAD_ERROR, e.getMessage());
        }
    }

    @GetMapping(value = "/api/auth-status", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Map<String, Object>> authStatus(HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (Objects.isNull(userId)) {
            return ApiResponse.ok(Map.of("authenticated", false));
        }
        return authService.getUserById(userId)
                .map(user -> ApiResponse.ok(Map.<String, Object>of("authenticated", true, "userId", user.getId(), "username", user.getUsername(), "displayName", user.getDisplayName())))
                .orElse(ApiResponse.ok(Map.of("authenticated", false)));
    }

    private Long getUserIdFromCookie(HttpServletRequest request) {
        Long fromAttr = SessionCookieService.userIdFromRequest(request);
        if (Objects.nonNull(fromAttr)) return fromAttr;
        return sessionCookieService.resolveUserId(request);
    }

    /** Ensure session belongs to user (via conversation or prior tasks). */
    private boolean ownsSession(Long userId, String sessionId) {
        if (Objects.isNull(userId) || StringUtils.isBlank(sessionId)) return false;
        if (conversationStore.ownedBy(userId, sessionId)) return true;
        return chatTaskRepository.existsByUserIdAndSessionId(userId, sessionId);
    }

    // ========== 执行中追加消息 ==========

    @PostMapping(value = "/api/chat/append-message", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Void> appendUserMessage(@RequestBody Map<String, String> body, HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (Objects.isNull(userId)) {
            return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        }
        String sessionId = body.get("sessionId");
        String message = body.get("message");
        if (StringUtils.isBlank(sessionId) || StringUtils.isBlank(message)) {
            return ApiResponse.fail(ErrorCode.CONFIG_INVALID, "sessionId and message required");
        }
        if (!ownsSession(userId, sessionId) && !streamingService.isTaskRunning(sessionId)) {
            return ApiResponse.fail(ErrorCode.AUTH_FORBIDDEN);
        }
        boolean ok = eventCenter.appendUserMessage(sessionId, message);
        if (!ok) {
            return ApiResponse.fail(ErrorCode.CHAT_SESSION_NOT_FOUND, "No active task for this session");
        }
        return ApiResponse.ok();
    }

    @PostMapping(value = "/api/chat/cancel", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Void> cancelChat(@RequestBody Map<String, String> body, HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (Objects.isNull(userId)) return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        String sessionId = body.get("sessionId");
        if (StringUtils.isBlank(sessionId)) return ApiResponse.fail(ErrorCode.CONFIG_INVALID, "sessionId required");
        if (!ownsSession(userId, sessionId) && !streamingService.isTaskRunning(sessionId)) {
            return ApiResponse.fail(ErrorCode.AUTH_FORBIDDEN);
        }
        agentService.cancel(userId, sessionId);
        return ApiResponse.ok();
    }

    // ========== SSE streaming ==========

    @PostMapping(value = "/chat/stream", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SseEmitter chatStreamMultimodal(@RequestBody ChatRequest req, HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (Objects.isNull(userId)) {
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
        if (StringUtils.isNotBlank(sessionId)) {
            if (StringUtils.isNotBlank(req.getPermissionMode())) {
                permissionStore.setMode(sessionId, PermissionMode.from(req.getPermissionMode()));
            }
            if (StringUtils.isNotBlank(req.getConfirmPolicy())) {
                permissionStore.setConfirmPolicy(
                        sessionId, ConfirmPolicy.from(req.getConfirmPolicy()));
            }
        }
        List<String> images = Objects.isNull(req.getImages()) ? List.of() : req.getImages();
        List<FileAttachment> files = Objects.isNull(req.getFiles()) ? List.of() : req.getFiles();
        List<MediaRef> mediaRefs = Objects.isNull(req.getMediaRefs()) ? List.of() : req.getMediaRefs();
        StringBuilder queryTask = new StringBuilder(Optional.ofNullable(message).orElse(""));
        // 预上传附件：统一走安全提取 + 侧车 + 上下文预算（docx/pptx/pdf/md 等）
        if (Objects.nonNull(req.getFileRefs()) && !req.getFileRefs().isEmpty()) {
            String fileCtx = uploadedDocumentService.buildMessageContext(userId, req.getFileRefs());
            if (StringUtils.isNotBlank(fileCtx)) {
                queryTask.append(fileCtx);
            }
        }
        return agentService.chatStreamMultimodal(
                userId, sessionId, queryTask.toString(), images, files, mediaRefs, role);
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
            if (Objects.isNull(contentType)) contentType = "application/octet-stream";

            String uri = imagePath.toUri().toString();
            UrlResource resource = new UrlResource(Objects.isNull(uri) ? "file:///" : uri);
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
    public ApiResponse<Map<String, Object>> taskStatus(@RequestParam("sessionId") String sessionId,
                                                       HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (Objects.isNull(userId)) return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        if (!ownsSession(userId, sessionId) && !streamingService.isTaskRunning(sessionId)) {
            return ApiResponse.ok(Map.of("sessionId", sessionId, "running", false));
        }
        boolean running = streamingService.isTaskRunning(sessionId);
        return ApiResponse.ok(Map.of("sessionId", sessionId, "running", running));
    }

    /**
     * 重连端点：刷新页面 / 新开浏览器后，挂载到正在运行（或刚结束仍在缓冲）的会话事件流，
     * 先重放已产出内容，再继续接收实时事件。无活动通道时发 "gone" 让前端回退到数据库加载。
     */
    @GetMapping(value = "/chat/stream/attach", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter attachStream(@RequestParam("sessionId") String sessionId,
                                   HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (Objects.isNull(userId)) {
            SseEmitter emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event().name("error").data("Not authenticated"));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }
        if (!ownsSession(userId, sessionId) && !streamingService.isTaskRunning(sessionId)) {
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
        return streamingService.attachStream(sessionId);
    }

    @GetMapping(value = "/api/conversations", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<List<Map<String, Object>>> listConversations(HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (Objects.isNull(userId)) return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        var tasks = chatTaskRepository.findLatestTaskPerSession(userId);
        return ApiResponse.ok(tasks.stream().map(t -> Map.<String, Object>of(
            "sessionId", t.getSessionId(),
            "title", t.getQuestion().length() > 40 ? t.getQuestion().substring(0, 40) : t.getQuestion(),
            "updatedAt", Objects.nonNull(t.getCreatedAt()) ? t.getCreatedAt().toString() : ""
        )).toList());
    }

    @GetMapping(value = "/api/conversation", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Object> getConversation(@RequestParam("sessionId") String sessionId,
                                               HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (Objects.isNull(userId)) return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        var conv = conversationService.getConversationForUser(userId, sessionId);
        if (Objects.isNull(conv)) {
            return ApiResponse.ok(Map.of("exists", false, "sessionId", sessionId));
        }
        return ApiResponse.ok(conv);
    }

    @GetMapping(value = "/api/conversation/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Map<String, Object>> getConversationMessages(
            HttpServletRequest request,
            @RequestParam("sessionId") String sessionId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        Long userId = getUserIdFromCookie(request);
        if (Objects.isNull(userId)) return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        var tasks = chatTaskRepository.findByUserIdAndSessionIdOrderByCreatedAtDesc(
                userId, sessionId, org.springframework.data.domain.PageRequest.of(page, size));
        var list = new ArrayList<>(tasks.getContent());
        Collections.reverse(list);
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (var t : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", t.getId());
            row.put("question", t.getQuestion());
            row.put("answer", Optional.ofNullable(t.getAnswer()).orElse(""));
            row.put("createdAt", Objects.nonNull(t.getCreatedAt()) ? t.getCreatedAt().toString() : "");
            row.put("images", toConversationImageUrls(t.getImages()));
            mapped.add(row);
        }
        return ApiResponse.ok(Map.of(
            "tasks", mapped,
            "hasMore", tasks.hasNext()
        ));
    }

    /** chat_tasks.images 逗号分隔相对键 → 可回显的 HTTP 路径 */
    static List<String> toConversationImageUrls(String imagesCsv) {
        if (StringUtils.isBlank(imagesCsv)) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : imagesCsv.split(",")) {
            String p = part == null ? "" : part.trim().replace('\\', '/');
            if (p.isEmpty()) continue;
            if (p.startsWith("http://") || p.startsWith("https://") || p.startsWith("/")) {
                out.add(p);
                continue;
            }
            if (!p.startsWith("conversation-images/"))
                p = "conversation-images/" + p;
            out.add("/" + p);
        }
        return out;
    }

    @GetMapping(value = "/api/conversation/delete", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Void> deleteConversationApi(
            HttpServletRequest request,
            @RequestParam("sessionId") String sessionId) {
        Long userId = getUserIdFromCookie(request);
        if (Objects.isNull(userId)) return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        if (!ownsSession(userId, sessionId)) {
            return ApiResponse.fail(ErrorCode.AUTH_FORBIDDEN);
        }
        chatTaskRepository.deleteByUserIdAndSessionId(userId, sessionId);
        conversationService.deleteConversationForUser(userId, sessionId);
        return ApiResponse.ok();
    }

    @GetMapping(value = "/api/token-usage", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Object> tokenUsage(@RequestParam("sessionId") String sessionId, HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (Objects.isNull(userId)) return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        if (!ownsSession(userId, sessionId)) return ApiResponse.fail(ErrorCode.AUTH_FORBIDDEN);
        return ApiResponse.ok(TokenUsageTracker.get(sessionId));
    }

    @GetMapping(value = "/api/token-usage/all", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Object> allTokenUsage(HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (Objects.isNull(userId)) return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        // Per-user aggregate not tracked; return empty to avoid cross-tenant leak
        return ApiResponse.ok(Map.of());
    }

    // ========== 轨迹监控 ==========

    @GetMapping("/trace")
    public String showTracePage(HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (Objects.isNull(userId)) {
            return "login";
        }
        return "trace";
    }

    /** 轨迹页实时推送：落库一步推一步，替代前端轮询 */
    @GetMapping(value = "/api/traces/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTraces(@RequestParam("sessionId") String sessionId,
                                   HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (Objects.isNull(userId))
            return rejectTraceSse(MessageConstants.SSE_NOT_AUTHENTICATED);
        if (StringUtils.isBlank(sessionId) || !ownsSession(userId, sessionId))
            return rejectTraceSse(MessageConstants.SSE_FORBIDDEN);
        return traceSseHub.attach(sessionId);
    }

    private static SseEmitter rejectTraceSse(String message) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            emitter.send(SseEmitter.event().name("error").data(message));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /** Agent 节点全集目录（与 AgentStepNode 同步） */
    @GetMapping(value = "/api/traces/node-catalog", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object getTraceNodeCatalog() {
        return com.miniagent.agent.trace.AgentStepNode.catalog();
    }

    @GetMapping(value = "/api/traces", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Object> getTraces(
            HttpServletRequest request,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "executionId", required = false) String executionId) {
        Long userId = getUserIdFromCookie(request);
        if (Objects.isNull(userId)) return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        if (StringUtils.isNotBlank(sessionId) && !ownsSession(userId, sessionId)) {
            return ApiResponse.ok(List.of());
        }
        if (Objects.nonNull(executionId) && !executionId.isEmpty()) {
            return ApiResponse.ok(agentTraceStepRepository.findByExecutionIdOrderByTurnIndexAscIdAsc(executionId));
        }
        return ApiResponse.ok(agentTraceStepRepository.findBySessionIdOrderByTurnIndexAscIdAsc(sessionId));
    }

    /**
     * 规划决策轨迹（按 executionId）：GOAL_COMPILED / PROPOSAL / STATE_COMMIT / RECOVERY_* 等。
     */
    @GetMapping(value = "/api/planner/decisions", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Object> getPlannerDecisions(
            HttpServletRequest request,
            @RequestParam("executionId") String executionId) {
        Long userId = getUserIdFromCookie(request);
        if (Objects.isNull(userId)) return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        if (StringUtils.isBlank(executionId))
            return ApiResponse.fail(ErrorCode.CHAT_MESSAGE_EMPTY);
        List<AgentTraceStep> all =
                agentTraceStepRepository.findByExecutionIdOrderByTurnIndexAscIdAsc(executionId);
        if (all.isEmpty()) return ApiResponse.ok(List.of());
        String sessionId = all.get(0).getSessionId();
        if (StringUtils.isNotBlank(sessionId) && !ownsSession(userId, sessionId))
            return ApiResponse.ok(List.of());
        return ApiResponse.ok(com.miniagent.agent.planner.PlannerDecisionNodes.filter(
                all, AgentTraceStep::getStepType));
    }

    /** 获取某 session 下所有执行任务的列表（按 executionId 分组） */
    @GetMapping(value = "/api/traces/executions", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<List<Map<String, Object>>> getExecutions(
            @RequestParam("sessionId") String sessionId,
            HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (Objects.isNull(userId) || !ownsSession(userId, sessionId)) return ApiResponse.ok(List.of());
        List<AgentTraceStep> all = agentTraceStepRepository.findBySessionIdOrderByTurnIndexAscIdAsc(sessionId);
        // 按 executionId 分组，返回每个执行的摘要
        Map<String, List<AgentTraceStep>> grouped = new LinkedHashMap<>();
        for (AgentTraceStep s : all) {
            grouped.computeIfAbsent(s.getExecutionId(), k -> new ArrayList<>()).add(s);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            List<AgentTraceStep> steps = entry.getValue();
            AgentTraceStep first = steps.get(0);
            AgentTraceStep last = steps.get(steps.size() - 1);
            String question = first.getUserQuestion();
            // 尝试从 ANSWER 步骤获取答案摘要
            String answerSummary = steps.stream()
                    .filter(s -> "ANSWER".equals(s.getStepType()))
                    .map(s -> s.getContent())
                    .findFirst().orElse("");
            if (answerSummary.length() > 100) answerSummary = answerSummary.substring(0, 100) + "...";
            Map<String, Object> exec = new HashMap<>();
            exec.put("executionId", entry.getKey());
            exec.put("sessionId", sessionId);
            exec.put("userQuestion", Optional.ofNullable(question).orElse(""));
            exec.put("answerSummary", answerSummary);
            exec.put("stepCount", steps.size());
            exec.put("startTime", first.getCreatedAt());
            exec.put("endTime", last.getCreatedAt());
            exec.put("status", last.getStatus());
            result.add(exec);
        }
        return ApiResponse.ok(result);
    }

    @GetMapping(value = "/api/traces/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Map<String, Object>> getTraceSummary(
            HttpServletRequest request,
            @RequestParam(value = "sessionId", required = false) String sessionId,
            @RequestParam(value = "executionId", required = false) String executionId) {
        Long userId = getUserIdFromCookie(request);
        if (Objects.isNull(userId)) return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        if (StringUtils.isNotBlank(sessionId) && !ownsSession(userId, sessionId)) {
            return ApiResponse.fail(ErrorCode.AUTH_FORBIDDEN);
        }
        long totalSteps, totalTurns;
        Long totalDuration;
        List<Object[]> toolStats;
        List<Object[]> slowestSteps = List.of();

        if (Objects.nonNull(executionId) && !executionId.isEmpty()) {
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

        List<Map<String, Object>> tools = new ArrayList<>();
        for (Object[] row : toolStats) {
            Map<String, Object> m = new HashMap<>();
            m.put("name", row[0]);
            m.put("count", row[1]);
            m.put("avgDurationMs", Math.round(((Number) row[2]).doubleValue()));
            tools.add(m);
        }
        List<Map<String, Object>> slowest = new ArrayList<>();
        for (Object[] row : slowestSteps) {
            if (slowest.size() >= 5) break;
            Map<String, Object> m = new HashMap<>();
            m.put("stepType", row[0]);
            m.put("toolName", row[1]);
            m.put("durationMs", row[2]);
            slowest.add(m);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("totalSteps", totalSteps);
        result.put("totalTurns", totalTurns);
        result.put("totalDurationMs", Optional.ofNullable(totalDuration).orElse(0L));
        result.put("tools", tools);
        result.put("slowestSteps", slowest);
        return ApiResponse.ok(result);
    }

    // ========== 权限模式（按会话） ==========

    @GetMapping(value = "/api/permission-mode", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Object> getPermissionMode(@RequestParam("sessionId") String sessionId,
                                                  HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (Objects.isNull(userId)) return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        if (StringUtils.isBlank(sessionId)) {
            return ApiResponse.fail(ErrorCode.CONFIG_INVALID, "sessionId required");
        }
        return ApiResponse.ok(permissionStore.toView(sessionId));
    }

    @PutMapping(value = "/api/permission-mode", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Object> putPermissionMode(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (Objects.isNull(userId)) return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        String sessionId = Objects.isNull(body) ? null : String.valueOf(body.getOrDefault("sessionId", ""));
        if (StringUtils.isBlank(sessionId) || "null".equals(sessionId)) {
            return ApiResponse.fail(ErrorCode.CONFIG_INVALID, "sessionId required");
        }
        String action = Objects.isNull(body.get("action")) ? "set" : String.valueOf(body.get("action"));
        if ("approve_plan".equalsIgnoreCase(action)) {
            permissionStore.approvePlan(sessionId);
            eventCenter.appendUserMessage(sessionId, "【系统】用户已批准 Plan，请按 todo 开始执行写操作与交付。");
            return ApiResponse.ok(permissionStore.toView(sessionId));
        }
        if ("grant_ask".equalsIgnoreCase(action)) {
            String tool = Objects.isNull(body.get("tool")) ? "" : String.valueOf(body.get("tool"));
            permissionStore.grantAskTool(sessionId, tool);
            eventCenter.appendUserMessage(sessionId, "【系统】用户已批准工具 " + tool + "，请继续。");
            if (plannerStateStore.hasIncompleteGraph(sessionId)) {
                plannerStateStore.markResume(sessionId);
            }
            return ApiResponse.ok(permissionStore.toView(sessionId));
        }
        if (body.get("confirmPolicy") != null) {
            permissionStore.setConfirmPolicy(
                    sessionId, ConfirmPolicy.from(String.valueOf(body.get("confirmPolicy"))));
        }
        if (body.get("mode") != null) {
            permissionStore.setMode(sessionId, PermissionMode.from(String.valueOf(body.get("mode"))));
        } else if (body.get("confirmPolicy") == null) {
            permissionStore.setMode(sessionId, PermissionMode.from("default"));
        }
        return ApiResponse.ok(permissionStore.toView(sessionId));
    }

    @PostMapping(value = "/api/todo/confirm",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Object> confirmTodo(@RequestBody Map<String, Object> body,
                                           HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (Objects.isNull(userId)) {
            return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        }
        String sessionId = Objects.isNull(body)
                ? null : String.valueOf(body.getOrDefault("sessionId", ""));
        if (StringUtils.isBlank(sessionId) || "null".equals(sessionId)) {
            return ApiResponse.fail(ErrorCode.CONFIG_INVALID, "sessionId required");
        }
        if (!ownsSession(userId, sessionId)) {
            return ApiResponse.fail(ErrorCode.AUTH_FORBIDDEN);
        }
        int id = parseTodoId(body == null ? null : body.get("id"));
        if (id <= 0) {
            return ApiResponse.fail(ErrorCode.CONFIG_INVALID, "id required");
        }
        String[] err = new String[1];
        List<TaskTodoStore.TodoItem> items = todoStore.confirm(sessionId, id, "CONFIRM: user", err);
        if (items == null) {
            String detail = err[0] == null ? "" : err[0];
            if (detail.contains("未找到")) {
                return ApiResponse.fail(ErrorCode.TODO_NOT_FOUND, detail);
            }
            return ApiResponse.fail(ErrorCode.TODO_INVALID_STATE,
                    StringUtils.isBlank(detail) ? ErrorCode.TODO_INVALID_STATE.getMessage() : detail);
        }
        if (plannerStateStore.hasIncompleteGraph(sessionId)) {
            plannerStateStore.markResume(sessionId);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("confirmed", true);
        return ApiResponse.ok(data);
    }

    private static int parseTodoId(Object raw) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ========== MCP 状态 ==========

    @GetMapping(value = "/api/mcp/status", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Map<String, Object>> mcpStatus(HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (Objects.isNull(userId)) return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        boolean enabled = Objects.nonNull(mcpProperties) && mcpProperties.isEnabled();
        List<String> tools = Objects.isNull(mcpToolBridge) ? List.of() : mcpToolBridge.registeredToolNames();
        int serverCount = Objects.isNull(mcpProperties) || Objects.isNull(mcpProperties.getServers())
                ? 0 : mcpProperties.getServers().size();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("serverCount", serverCount);
        m.put("registeredTools", tools);
        m.put("toolCount", tools.size());
        return ApiResponse.ok(m);
    }

    @PostMapping(value = "/api/mcp/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Object> mcpRefresh(@RequestBody(required = false) Map<String, Object> body,
                                           HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (Objects.isNull(userId)) return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        if (Objects.isNull(mcpToolBridge)) return ApiResponse.fail(ErrorCode.MCP_NOT_ENABLED);
        try {
            String serverId = Objects.isNull(body) || Objects.isNull(body.get("serverId"))
                    ? null : String.valueOf(body.get("serverId"));
            if (StringUtils.isNotBlank(serverId) && !"null".equals(serverId)) {
                return ApiResponse.ok(mcpToolBridge.refreshServer(serverId));
            }
            return ApiResponse.ok(mcpToolBridge.refreshAll());
        } catch (Exception e) {
            return ApiResponse.fail(ErrorCode.MCP_CONNECTION_FAILED, Objects.nonNull(e.getMessage()) ? e.getMessage() : "refresh failed");
        }
    }

    // ========== 模型配置（按用户） ==========

    @GetMapping(value = "/api/model-config", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> getModelConfig(HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (Objects.isNull(userId)) return Map.of("success", false, "message", "Not authenticated");
        return userModelConfigService.getView(userId);
    }

    @PutMapping(value = "/api/model-config", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> putModelConfig(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (Objects.isNull(userId)) return Map.of("success", false, "message", "Not authenticated");
        if (Objects.nonNull(body) && Boolean.TRUE.equals(body.get("reset"))) {
            return userModelConfigService.resetToDefault(userId);
        }
        String presetId = Objects.isNull(body) ? null : strOrNull(body.get("presetId"));
        String baseUrl = Objects.isNull(body) ? null : strOrNull(body.get("baseUrl"));
        String modelName = Objects.isNull(body) ? null : strOrNull(body.get("modelName"));
        String apiKey = Objects.isNull(body) ? null : strOrNull(body.get("apiKey"));
        return userModelConfigService.save(userId, presetId, baseUrl, modelName, apiKey);
    }

    private static String strOrNull(Object v) {
        if (Objects.isNull(v)) return null;
        return String.valueOf(v);
    }
}
