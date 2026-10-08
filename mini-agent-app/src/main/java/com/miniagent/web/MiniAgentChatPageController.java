package com.miniagent.web;

import com.miniagent.application.AgentChatApplicationService;
import com.miniagent.agent.core.TokenUsageTracker;
import com.miniagent.common.ApiResponse;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.MessageConstants;
import com.miniagent.common.permission.ExecPolicy;
import com.miniagent.config.cloud.CloudAccountException;
import com.miniagent.config.cloud.CloudAccountService;
import com.miniagent.config.entity.User;
import com.miniagent.config.security.JwtSessionService;
import com.miniagent.config.security.AuthenticatedUser;
import com.miniagent.config.security.SessionAuthorizationService;
import com.miniagent.config.service.AuthService;
import com.miniagent.config.service.DatabaseConversationStore;
import com.miniagent.config.service.FileStorageService;
import com.miniagent.config.service.SystemAdminService;
import com.miniagent.config.service.UserModelConfigService;
import com.miniagent.config.storage.MediaStorage;
import com.miniagent.agent.permission.ConfirmPolicy;
import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.permission.SessionPermissionStore;
import com.miniagent.agent.planner.PlannerStateStore;
import com.miniagent.agent.planner.StateSnapshot;
import com.miniagent.agent.planner.TaskGraph;
import com.miniagent.agent.planner.TodoStateProjector;
import com.miniagent.agent.todo.TaskTodoStore;
import com.miniagent.web.dto.ChatRequest;
import com.miniagent.web.dto.FileAttachment;
import com.miniagent.web.dto.FileRef;
import com.miniagent.web.dto.LoginRequest;
import com.miniagent.web.dto.MediaRef;
import com.miniagent.web.dto.RegisterRequest;
import lombok.extern.slf4j.Slf4j;
import com.miniagent.agent.web.MultimodalMedia;
import com.miniagent.web.dto.resp.AuthStatusDTO;
import com.miniagent.web.dto.resp.ConversationAbsentDTO;
import com.miniagent.web.dto.resp.ConversationMessagesDTO;
import com.miniagent.web.dto.resp.ConversationSummaryDTO;
import com.miniagent.web.dto.resp.McpStatusDTO;
import com.miniagent.web.dto.resp.NewConversationDTO;
import com.miniagent.web.dto.resp.TaskStatusDTO;
import com.miniagent.web.dto.resp.TodoConfirmDTO;
import com.miniagent.web.dto.resp.TokenUsageAllDTO;
import com.miniagent.web.dto.resp.TraceExecutionDTO;
import com.miniagent.web.dto.resp.TraceSummaryDTO;
import com.miniagent.web.dto.resp.UploadResultDTO;
import com.miniagent.web.dto.resp.UserDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
@Slf4j
public class MiniAgentChatPageController {

    private static final String MCP_REFRESH_AUDIT_ACTION = "MCP_REFRESH";
    private static final String MCP_SERVER_AUDIT_TARGET = "MCP_SERVER";
    private static final String ALL_MCP_SERVERS = "*";

    @Autowired
    private  AgentChatApplicationService agentService;
    @Autowired
    private  com.miniagent.application.ConversationService conversationService;
    @Autowired
    private  com.miniagent.application.ChatStreamingService streamingService;
    @Autowired
    private  AuthService authService;

    /**
     * 云端账号服务（{@code agent.auth.cloud.base-url}）。
     * 未配置时 {@code enabled()} 为 false，注册/登录自动退回本地账号流程 ——
     * 开发态和单机测试因此不需要为了登录一次去起第二份实例。
     */
    @Autowired
    private  CloudAccountService cloudAccountService;
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
    private SessionAuthorizationService sessionAuthorization;
    @Autowired
    private  com.miniagent.agent.trace.TraceSseHub traceSseHub;
    @Autowired
    private  com.miniagent.agent.core.SessionEventCenter eventCenter;
    @Autowired
    private  JwtSessionService jwtSessionService;
    @Autowired
    private  DatabaseConversationStore conversationStore;
    @Autowired
    private  UserModelConfigService userModelConfigService;
    @Autowired
    private SystemAdminService systemAdminService;
    @Autowired
    private MediaStorage mediaStorage;

    @Value("${file.upload.max-size:734003200}")
    private long maxUploadSizeBytes;
    @Autowired
    private SessionPermissionStore permissionStore;
    @Autowired
    private com.miniagent.agent.permission.ExecPolicyService execPolicyService;
    @Autowired
    private TaskTodoStore todoStore;
    @Autowired
    private PlannerStateStore plannerStateStore;
    @Autowired
    private TodoStateProjector todoProjector;
    @Autowired(required = false)
    private com.miniagent.agent.mcp.McpToolBridge mcpToolBridge;
    @Autowired(required = false)
    private com.miniagent.agent.mcp.McpProperties mcpProperties;

    /**
     * 页面入口。无 cookie 之后，浏览器导航请求带不了 Authorization 头，服务端在这里拿不到
     * 任何身份信息，所以不再做服务端分流 —— 一律返回 chat 骨架，由 chat.html 启动时读
     * sessionStorage 决定「继续渲染」还是跳 {@code /login}。
     */
    @GetMapping("/")
    public String showChatPage() {
        return "chat";
    }

    /** 登录页。独立成路由，供未登录态跳转。 */
    @GetMapping("/login")
    public String showLoginPage() {
        return "login";
    }

    /**
     * 会员中心。与登录页同理：这里只返回页面骨架，
     * 真正的数据由页面向 {@code /api/membership/**} 取，那些端点要求 Bearer。
     */
    @GetMapping("/membership")
    public String showMembershipPage() {
        return "membership";
    }

    // ========== Login / Register / Auth endpoints ==========

    @PostMapping(value = "/api/tokens", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<UserDTO> login(@RequestBody LoginRequest req) {
        // 配了云端账号服务时，密码比对由云端完成 —— 本地根本拿不到用户的密码，
        // 库里影子用户的 password_hash 是一串只有系统自己知道、且随即丢弃的随机值。
        if (cloudAccountService.enabled()) {
            try {
                User user = cloudAccountService.login(req.getUsername(), req.getPassword());
                return ApiResponse.ok(toUserDto(user, jwtSessionService.issueToken(user.getId())));
            } catch (CloudAccountException e) {
                // 原样透传错误码，不归并成 AUTH_LOGIN_FAILED：
                // AUTH.01.01 才是"密码错"，AUTH.03.01 是"云连不上"。
                // 归并之后断网会显示成"用户名或密码错误"，用户会一直改密码。
                log.warn("云端登录失败: user={} code={} msg={}",
                        req.getUsername(), e.errorCode().getCode(), e.getMessage());
                return ApiResponse.fail(e.errorCode(), e.getMessage());
            }
        }
        return authService.login(req.getUsername(), req.getPassword())
                .map(user -> ApiResponse.ok(
                        toUserDto(user, jwtSessionService.issueToken(user.getId()))))
                .orElse(ApiResponse.fail(ErrorCode.AUTH_LOGIN_FAILED));
    }

    @PostMapping(value = "/api/users", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<UserDTO> register(@RequestBody RegisterRequest req) {
        // 账号落在云端，本地只建影子用户。刻意不在本地也存一份密码：
        // 那样同一台机器上会存在一个"可以绕过云端直接登录"的入口。
        if (cloudAccountService.enabled()) {
            try {
                User user = cloudAccountService.register(
                        req.getUsername(), req.getPassword(), req.getDisplayName());
                return ApiResponse.ok(toUserDto(user, jwtSessionService.issueToken(user.getId())));
            } catch (CloudAccountException e) {
                log.warn("云端注册失败: user={} code={} msg={}",
                        req.getUsername(), e.errorCode().getCode(), e.getMessage());
                return ApiResponse.fail(e.errorCode(), e.getMessage());
            }
        }
        AuthService.RegisterResult result = authService.register(
                req.getUsername(), req.getPassword(), req.getDisplayName());
        if (!result.success()) {
            // 按真实原因返回，不再把所有失败都说成「用户已存在」
            return ApiResponse.fail(result.error(), result.detail());
        }
        var user = result.user();
        return ApiResponse.ok(toUserDto(user, jwtSessionService.issueToken(user.getId())));
    }

    /**
     * 云端账号服务状态。供界面在用户还没输入账号时就说明「必须联网才能登录」，
     * 而不是让人输完密码再收到一条网络错误。
     */
    @GetMapping(value = "/api/auth/cloud-status", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<CloudAccountService.CloudStatus> cloudStatus() {
        CloudAccountService.CloudStatus status = cloudAccountService.status();
        // 未配置云端时返回 success=true + enabled=false：这是"本机走本地账号"这一正常状态，
        // 不是错误。回 fail 会让前端的错误处理把一个合法配置当成故障。
        return ApiResponse.ok(status);
    }

    /**
     * 云端网页注册成功后的自动登录。凭证由账号服务签发，用过即废。
     */
    @PostMapping(value = "/api/auth/desktop-login",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<UserDTO> desktopLogin(
            @RequestBody(required = false) DesktopLoginRequest req) {
        if (req == null || req.ticket() == null || req.ticket().isBlank()) {
            return ApiResponse.fail(ErrorCode.AUTH_SESSION_INVALID, "登录凭证无效");
        }
        if (!cloudAccountService.enabled()) {
            return ApiResponse.fail(ErrorCode.AUTH_CLOUD_NOT_CONFIGURED);
        }
        try {
            User user = cloudAccountService.loginWithTicket(req.ticket());
            return ApiResponse.ok(toUserDto(user, jwtSessionService.issueToken(user.getId())));
        } catch (CloudAccountException e) {
            log.warn("云端注册回跳登录失败: code={} msg={}",
                    e.errorCode().getCode(), e.getMessage());
            return ApiResponse.fail(e.errorCode(), e.getMessage());
        }
    }

    public record DesktopLoginRequest(String ticket) {
    }

    private static UserDTO toUserDto(User user, String token) {
        return UserDTO.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .tenantId(user.getTenantId())
                .role(user.getRole())
                .token(token)
                .build();
    }

    /**
     * 登出。改成 POST + JSON：无 cookie 之后没有任何「浏览器自动清除」的环节，
     * 服务端只负责吊销 Redis 会话并摘掉该用户的 SSE 流，清 sessionStorage 与跳转由前端做。
     */
    @DeleteMapping("/api/tokens")
    @ResponseBody
    public ApiResponse<Void> logout(HttpServletRequest request) {
        jwtSessionService.logout(request);
        return ApiResponse.ok();
    }

    /**
     * 新建会话，返回服务端签发的 sessionId。
     *
     * <p>为什么 id 必须由服务端签发：会话作用域接口的归属判定是
     * {@code chat_conversations.id -> user_id}。id 若是客户端自造，那么「归属记录」只能等
     * 首条消息落库才出现，在那之前服务端无法区分「这是他的新会话」和「他在用别人的 id」，
     * 只能退化成「这个 id 被别人占了吗」的猜测式放行 —— 那就是越权口子。
     * 改成签发即认领：id 与 userId 同时写库，此后所有校验都是严格判等。
     */
    @PostMapping(value = "/api/conversations", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<NewConversationDTO> newConversation(HttpServletRequest request) {
        Long userId = resolveUserId(request);
        if (Objects.isNull(userId)) {
            return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        }
        // 保留 s_ 前缀：只是可读性，代码里没有任何地方解析它。
        // UUID 去掉连字符后 32 位，加前缀 34 位，远低于 chat_conversations.id 的 100 上限。
        String sessionId = "s_" + UUID.randomUUID().toString().replace("-", "");
        if (!conversationStore.claim(userId, sessionId, null)) {
            // claim 只在 id 已存在且属主不同的时候返回 false。UUID 碰撞概率可忽略，
            // 走到这里说明是别的异常情况，直接报错比返回一个不可用的 id 好。
            log.error("新建会话认领失败 userId={} sessionId={}", userId, sessionId);
            return ApiResponse.fail(ErrorCode.CHAT_PERSIST_FAILED, "创建会话失败");
        }
        return ApiResponse.ok(new NewConversationDTO(sessionId));
    }


    @PostMapping(value = "/api/conversations/{sessionId}/files",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<UploadResultDTO> uploadFile(@RequestParam("file") MultipartFile file,
                                                       @PathVariable("sessionId") String sessionId,
                                                       HttpServletRequest request) {
        Long userId = resolveUserId(request);
        if (Objects.isNull(userId)) {
            return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        }
        sessionAuthorization.requireOwner(userId, sessionId);
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
        if (file.getSize() > maxUploadSizeBytes) {
            return ApiResponse.fail(ErrorCode.FILE_TOO_LARGE,
                    "文件过大: " + (file.getSize() / 1024 / 1024) + "MB，上限 "
                            + (maxUploadSizeBytes / 1024 / 1024) + "MB");
        }
        try {
            var saved = fileStorageService.saveUploaded(
                    userId, sessionId, originalName, contentType, file);
            return ApiResponse.ok(new UploadResultDTO(
                    saved.getFilePath(),
                    saved.getOriginalFilename(),
                    Optional.ofNullable(saved.getMimeType()).orElse("application/octet-stream"),
                    saved.getFileSize(),
                    mediaKind,
                    saved.getExtractedTextPath()));
        } catch (Exception e) {
            return ApiResponse.fail(ErrorCode.FILE_UPLOAD_ERROR, e.getMessage());
        }
    }

    @GetMapping(value = "/api/tokens/current", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<AuthStatusDTO> authStatus(HttpServletRequest request) {
        Long userId = resolveUserId(request);
        if (Objects.isNull(userId)) {
            return ApiResponse.ok(AuthStatusDTO.anonymous());
        }
        return authService.getUserById(userId)
                .map(user -> ApiResponse.ok(AuthStatusDTO.authenticated(
                        user.getId(), user.getUsername(), user.getDisplayName())))
                .orElse(ApiResponse.ok(AuthStatusDTO.anonymous()));
    }

    private Long resolveUserId(HttpServletRequest request) {
        Long fromAttr = JwtSessionService.userIdFromRequest(request);
        if (Objects.nonNull(fromAttr)) {
            return fromAttr;
        }
        return jwtSessionService.resolveUserIdAndRefresh(request);
    }

    /** Ensure session belongs to user. 判定逻辑只留 SessionAuthorizationService 一份。 */
    private boolean ownsSession(Long userId, String sessionId) {
        return sessionAuthorization.owns(userId, sessionId);
    }

    // ========== 执行中追加消息 ==========

    @PostMapping(value = "/api/conversations/{sessionId}/messages",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Void> appendUserMessage(@PathVariable("sessionId") String sessionId,
                                               @RequestBody Map<String, String> body,
                                               HttpServletRequest request) {
        Long userId = resolveUserId(request);
        if (Objects.isNull(userId)) {
            return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        }
        String message = body == null ? null : body.get("message");
        if (StringUtils.isBlank(sessionId) || StringUtils.isBlank(message)) {
            return ApiResponse.fail(ErrorCode.CONFIG_INVALID, "sessionId and message required");
        }
        if (!ownsSession(userId, sessionId)) {
            return ApiResponse.fail(ErrorCode.AUTH_FORBIDDEN);
        }
        boolean ok = eventCenter.appendUserMessage(sessionId, message);
        if (!ok) {
            return ApiResponse.fail(ErrorCode.CHAT_SESSION_NOT_FOUND, "No active task for this session");
        }
        return ApiResponse.ok();
    }

    @DeleteMapping(value = "/api/conversations/{sessionId}/task",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Void> cancelChat(@PathVariable("sessionId") String sessionId,
                                        HttpServletRequest request) {
        Long userId = resolveUserId(request);
        if (Objects.isNull(userId)) {
            return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        }
        if (StringUtils.isBlank(sessionId)) {
            return ApiResponse.fail(ErrorCode.CONFIG_INVALID, "sessionId required");
        }
        if (!ownsSession(userId, sessionId)) {
            return ApiResponse.fail(ErrorCode.AUTH_FORBIDDEN);
        }
        agentService.cancel(userId, sessionId);
        return ApiResponse.ok();
    }

    // ========== SSE streaming ==========

    @PostMapping(value = "/api/conversations/{sessionId}/messages/stream",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public SseEmitter chatStreamMultimodal(@PathVariable("sessionId") String sessionId,
                                           @RequestBody ChatRequest req,
                                           HttpServletRequest request) {
        if (req != null) {
            req.setSessionId(sessionId);
        }
        Long userId = resolveUserId(request);
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
        // 归属校验。会话 id 现在由 POST /api/conversations 签发，签发时就已经把
        // userId 写进 chat_conversations，所以这里可以做严格判等：不是自己的会话一律拒。
        // 此前的放行条件里带了 occupiedByAnyone —— 那是「客户端自造 id」时代的补丁，
        // 副作用是把「id 还没被任何人占用」当成合法新会话，等于给抢注留门。已删除。
        if (StringUtils.isBlank(sessionId) || !ownsSession(userId, sessionId)) {
            log.warn("拒绝向非属主会话发问 userId={} sessionId={}", userId, sessionId);
            return streamingService.createErrorEmitter("error", MessageConstants.SSE_FORBIDDEN);
        }
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

    /**
     * Authenticated generated-media boundary. Media is stored below an owner
     * directory and is never exposed through the generic static-resource chain.
     */
    @GetMapping("/api/generated-media/{owner}/{filename}")
    @ResponseBody
    public ResponseEntity<?> serveGeneratedMedia(@PathVariable("owner") String owner,
                                                 @PathVariable("filename") String filename,
                                                 HttpServletRequest request) {
        Object rawPrincipal = request == null ? null
                : request.getAttribute(JwtSessionService.ATTR_PRINCIPAL);
        if (!(rawPrincipal instanceof AuthenticatedUser principal)) {
            return ResponseEntity.status(401).body("Authentication required");
        }
        if (!safePathSegment(owner) || !safePathSegment(filename)) {
            return ResponseEntity.badRequest().body("Illegal path");
        }
        if (!Objects.equals(owner, String.valueOf(principal.userId()))
                && principal.role() != com.miniagent.config.entity.UserRole.SYSTEM_ADMIN) {
            return ResponseEntity.status(403).body("Forbidden");
        }

        try {
            Path root = mediaStorage.generatedRoot().toAbsolutePath().normalize();
            Path ownerRoot = root.resolve(owner).normalize();
            Path media = ownerRoot.resolve(filename).normalize();
            if (!ownerRoot.startsWith(root) || !media.startsWith(ownerRoot)) {
                return ResponseEntity.badRequest().body("Illegal path");
            }
            if (!Files.isRegularFile(media)) {
                return ResponseEntity.notFound().build();
            }
            String contentType = approvedInlineMediaType(filename, media);
            if (contentType == null) {
                return ResponseEntity.status(415).body("Unsupported media type");
            }
            // Range / If-Range / 206 / 416 全在共用出口里（音频拖进度条靠它），见 MediaResponses
            return MediaResponses.serve(media, filename, contentType, request);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to read media");
        }
    }

    /** 会话附件只能由会话 owner（或系统管理员）读取。 */
    @GetMapping("/api/conversation-media/{sessionId}/{filename}")
    @ResponseBody
    public ResponseEntity<?> serveConversationMedia(@PathVariable("sessionId") String sessionId,
                                                     @PathVariable("filename") String filename,
                                                     HttpServletRequest request) {
        Object rawPrincipal = request == null ? null
                : request.getAttribute(JwtSessionService.ATTR_PRINCIPAL);
        if (!(rawPrincipal instanceof AuthenticatedUser principal)) {
            return ResponseEntity.status(401).body("Authentication required");
        }
        if (!safePathSegment(sessionId) || !safePathSegment(filename)) {
            return ResponseEntity.badRequest().body("Illegal path");
        }
        if (principal.role() != com.miniagent.config.entity.UserRole.SYSTEM_ADMIN
                && !ownsSession(principal.userId(), sessionId)) {
            return ResponseEntity.status(403).body("Forbidden");
        }
        try {
            Path root = mediaStorage.conversationsDir().toAbsolutePath().normalize();
            Path sessionRoot = root.resolve(sessionId).normalize();
            Path media = sessionRoot.resolve(filename).normalize();
            if (!sessionRoot.startsWith(root) || !media.startsWith(sessionRoot)) {
                return ResponseEntity.badRequest().body("Illegal path");
            }
            if (!Files.isRegularFile(media)) {
                return ResponseEntity.notFound().build();
            }
            String contentType = approvedInlineMediaType(filename, media);
            if (contentType == null) {
                return ResponseEntity.status(415).body("Unsupported media type");
            }
            // Range / If-Range / 206 / 416 全在共用出口里（音频拖进度条靠它），见 MediaResponses
            return MediaResponses.serve(media, filename, contentType, request);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Failed to read media");
        }
    }

    private static boolean safePathSegment(String value) {
        return value != null && !value.isBlank()
                && value.length() <= 180
                && value.matches("[A-Za-z0-9][A-Za-z0-9._-]*")
                && !value.contains("..");
    }

    private static String approvedInlineMediaType(String filename, Path file) throws java.io.IOException {
        String lower = filename.toLowerCase(Locale.ROOT);
        String expected = lower.endsWith(".png") ? "image/png"
                : lower.endsWith(".jpg") || lower.endsWith(".jpeg") ? "image/jpeg"
                : lower.endsWith(".gif") ? "image/gif"
                : lower.endsWith(".webp") ? "image/webp"
                : lower.endsWith(".mp3") ? "audio/mpeg"
                : lower.endsWith(".wav") ? "audio/wav"
                : lower.endsWith(".mp4") ? "video/mp4"
                : null;
        if (expected == null) {
            return null;
        }
        String probed = Files.probeContentType(file);
        return probed == null || expected.equalsIgnoreCase(probed) ? expected : null;
    }

    // ========== Session sync API ==========

    @GetMapping(value = "/api/conversations/{sessionId}/task",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<TaskStatusDTO> taskStatus(@PathVariable("sessionId") String sessionId,
                                                 HttpServletRequest request) {
        Long userId = resolveUserId(request);
        if (Objects.isNull(userId)) {
            return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        }
        if (!ownsSession(userId, sessionId)) {
            return ApiResponse.ok(new TaskStatusDTO(sessionId, false));
        }
        boolean running = streamingService.isTaskRunning(sessionId);
        return ApiResponse.ok(new TaskStatusDTO(sessionId, running));
    }

    /**
     * 重连端点：刷新页面 / 新开浏览器后，挂载到正在运行（或刚结束仍在缓冲）的会话事件流，
     * 先重放已产出内容，再继续接收实时事件。无活动通道时发 "gone" 让前端回退到数据库加载。
     */
    @GetMapping(value = "/api/conversations/{sessionId}/messages/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter attachStream(@PathVariable("sessionId") String sessionId,
                                   HttpServletRequest request) {
        Long userId = resolveUserId(request);
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
        // 归属判定只认 ownsSession。SessionAuthorizationService 已经用 AgentTaskRun 覆盖了
        // 「任务执行中、会话行与 ChatTask 都还没落库」这个窗口，不需要再靠 isTaskRunning 放宽。
        // 此前写成 !owns && !isTaskRunning：isTaskRunning 只看 sessionId、不看 userId，
        // 于是任何知道 sessionId 的登录用户，只要该会话正在跑，就能挂上别人的事件流，
        // 连 attachClient 的整段历史重放（提问原文 / 思考 / 已产出回答）一起读走。
        if (!ownsSession(userId, sessionId)) {
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
        return streamingService.attachStream(sessionId, userId);
    }

    @GetMapping(value = "/api/conversations", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<List<ConversationSummaryDTO>> listConversations(HttpServletRequest request) {
        Long userId = resolveUserId(request);
        if (Objects.isNull(userId)) {
            return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        }
        var tasks = chatTaskRepository.findLatestTaskPerSession(userId);
        return ApiResponse.ok(tasks.stream().map(t -> new ConversationSummaryDTO(
                t.getSessionId(),
                t.getQuestion().length() > 40
                        ? t.getQuestion().substring(0, 40) : t.getQuestion(),
                Objects.nonNull(t.getCreatedAt()) ? t.getCreatedAt().toString() : ""
        )).toList());
    }

    @GetMapping(value = "/api/conversations/{sessionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Object> getConversation(@PathVariable("sessionId") String sessionId,
                                               HttpServletRequest request) {
        Long userId = resolveUserId(request);
        if (Objects.isNull(userId)) {
            return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        }
        var conv = conversationService.getConversationForUser(userId, sessionId);
        if (Objects.isNull(conv)) {
            return ApiResponse.ok(new ConversationAbsentDTO(false, sessionId));
        }
        return ApiResponse.ok(conv);
    }

    @GetMapping(value = "/api/conversations/{sessionId}/messages",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<ConversationMessagesDTO> getConversationMessages(
            HttpServletRequest request,
            @PathVariable("sessionId") String sessionId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        Long userId = resolveUserId(request);
        if (Objects.isNull(userId)) {
            return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        }
        var tasks = chatTaskRepository.findByUserIdAndSessionIdAndDeletedFalseOrderByCreatedAtDesc(
                userId, sessionId, org.springframework.data.domain.PageRequest.of(page, size));
        var list = new ArrayList<>(tasks.getContent());
        Collections.reverse(list);
        List<ConversationMessagesDTO.Task> mapped = new ArrayList<>();
        for (var t : list) {
            mapped.add(new ConversationMessagesDTO.Task(
                    t.getId(),
                    t.getQuestion(),
                    Optional.ofNullable(t.getAnswer()).orElse(""),
                    Objects.nonNull(t.getCreatedAt()) ? t.getCreatedAt().toString() : "",
                    toConversationImageUrls(sessionId, t.getImages())));
        }
        return ApiResponse.ok(new ConversationMessagesDTO(mapped, tasks.hasNext()));
    }

    /** chat_tasks.images 逗号分隔相对键 → 可回显的 HTTP 路径 */
    static List<String> toConversationImageUrls(String sessionId, String imagesCsv) {
        if (StringUtils.isBlank(imagesCsv)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : imagesCsv.split(",")) {
            String p = part == null ? "" : part.trim().replace('\\', '/');
            if (p.isEmpty()) {
                continue;
            }
            if (p.startsWith("http://") || p.startsWith("https://")
                    || p.startsWith("/api/generated-media/")) {
                out.add(p);
                continue;
            }
            if (p.startsWith("/conversation-images/")) {
                p = p.substring(1);
            }
            if (p.startsWith("conversation-images/")) {
                p = p.substring("conversation-images/".length());
            }
            String prefix = sessionId + "/";
            if (p.startsWith(prefix)) {
                p = p.substring(prefix.length());
            }
            if (safePathSegment(sessionId) && safePathSegment(p)) {
                out.add("/api/conversation-media/" + sessionId + "/" + p);
            }
        }
        return out;
    }

    @DeleteMapping(value = "/api/conversations/{sessionId}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Void> deleteConversationApi(
            HttpServletRequest request,
            @PathVariable("sessionId") String sessionId) {
        Long userId = resolveUserId(request);
        if (Objects.isNull(userId)) {
            return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        }
        if (!ownsSession(userId, sessionId)) {
            return ApiResponse.fail(ErrorCode.AUTH_FORBIDDEN);
        }
        chatTaskRepository.softDeleteByUserIdAndSessionId(userId, sessionId);
        conversationService.deleteConversationForUser(userId, sessionId);
        return ApiResponse.ok();
    }

    @GetMapping(value = "/api/conversations/{sessionId}/token-usage",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Object> tokenUsage(@PathVariable("sessionId") String sessionId,
                                          HttpServletRequest request) {
        Long userId = resolveUserId(request);
        if (Objects.isNull(userId)) {
            return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        }
        if (!ownsSession(userId, sessionId)) {
            return ApiResponse.fail(ErrorCode.AUTH_FORBIDDEN);
        }
        return ApiResponse.ok(TokenUsageTracker.get(sessionId));
    }

    @GetMapping(value = "/api/token-usage", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Object> allTokenUsage(HttpServletRequest request) {
        Long userId = resolveUserId(request);
        if (Objects.isNull(userId)) {
            return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        }
        // 按用户汇总还没做。返回空对象，避免把全站用量漏出去。
        return ApiResponse.ok(new TokenUsageAllDTO());
    }

    // ========== 轨迹监控 ==========

    @GetMapping("/trace")
    public String showTracePage(HttpServletRequest request) {
        Long userId = resolveUserId(request);
        if (Objects.isNull(userId)) {
            return "login";
        }
        return "trace";
    }

    /** 轨迹页实时推送：落库一步推一步，替代前端轮询 */
    @GetMapping(value = "/api/conversations/{sessionId}/traces",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTraces(@PathVariable("sessionId") String sessionId,
                                   HttpServletRequest request) {
        Long userId = resolveUserId(request);
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
    @GetMapping(value = "/api/trace-nodes", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Object getTraceNodeCatalog() {
        return com.miniagent.agent.trace.AgentStepNode.catalog();
    }

    @GetMapping(value = "/api/conversations/{sessionId}/steps",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Object> listSessionSteps(@PathVariable("sessionId") String sessionId,
                                                HttpServletRequest request) {
        return listSteps(request, sessionId, null);
    }

    @GetMapping(value = "/api/executions/{executionId}/steps",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Object> listExecutionSteps(@PathVariable("executionId") String executionId,
                                                  HttpServletRequest request) {
        return listSteps(request, null, executionId);
    }

    private ApiResponse<Object> listSteps(
            HttpServletRequest request,
            String sessionId,
            String executionId) {
        Long userId = resolveUserId(request);
        if (Objects.isNull(userId)) {
            return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        }
        if (StringUtils.isNotBlank(sessionId) && !ownsSession(userId, sessionId)) {
            return ApiResponse.ok(List.of());
        }
        if (Objects.nonNull(executionId) && !executionId.isEmpty()) {
            List<AgentTraceStep> traces =
                    agentTraceStepRepository.findByExecutionIdOrderByTurnIndexAscIdAsc(executionId);
            if (!ownsTraceSessions(userId, traces)) {
                return ApiResponse.ok(List.of());
            }
            if (StringUtils.isNotBlank(sessionId)
                    && traces.stream().anyMatch(step -> !sessionId.equals(step.getSessionId()))) {
                return ApiResponse.ok(List.of());
            }
            return ApiResponse.ok(traces);
        }
        return ApiResponse.ok(agentTraceStepRepository.findBySessionIdOrderByTurnIndexAscIdAsc(sessionId));
    }

    /**
     * 规划决策轨迹（按 executionId）：GOAL_COMPILED / PROPOSAL / STATE_COMMIT / RECOVERY_* 等。
     */
    @GetMapping(value = "/api/executions/{executionId}/decisions",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Object> getPlannerDecisions(
            HttpServletRequest request,
            @PathVariable("executionId") String executionId) {
        Long userId = resolveUserId(request);
        if (Objects.isNull(userId)) {
            return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        }
        if (StringUtils.isBlank(executionId))
            return ApiResponse.fail(ErrorCode.CHAT_MESSAGE_EMPTY);
        List<AgentTraceStep> all =
                agentTraceStepRepository.findByExecutionIdOrderByTurnIndexAscIdAsc(executionId);
        if (all.isEmpty()) {
            return ApiResponse.ok(List.of());
        }
        String sessionId = all.get(0).getSessionId();
        if (StringUtils.isNotBlank(sessionId) && !ownsSession(userId, sessionId))
            return ApiResponse.ok(List.of());
        return ApiResponse.ok(com.miniagent.agent.planner.PlannerDecisionNodes.filter(
                all, AgentTraceStep::getStepType));
    }

    /** 获取某 session 下所有执行任务的列表（按 executionId 分组） */
    @GetMapping(value = "/api/conversations/{sessionId}/executions",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<List<TraceExecutionDTO>> getExecutions(
            @PathVariable("sessionId") String sessionId,
            HttpServletRequest request) {
        Long userId = resolveUserId(request);
        if (Objects.isNull(userId) || !ownsSession(userId, sessionId)) {
            return ApiResponse.ok(List.of());
        }
        List<AgentTraceStep> all = agentTraceStepRepository.findBySessionIdOrderByTurnIndexAscIdAsc(sessionId);
        // 按 executionId 分组，返回每个执行的摘要
        Map<String, List<AgentTraceStep>> grouped = new LinkedHashMap<>();
        for (AgentTraceStep s : all) {
            grouped.computeIfAbsent(s.getExecutionId(), k -> new ArrayList<>()).add(s);
        }
        List<TraceExecutionDTO> result = new ArrayList<>();
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
            if (answerSummary.length() > 100) {
                answerSummary = answerSummary.substring(0, 100) + "...";
            }
            result.add(new TraceExecutionDTO(
                    entry.getKey(),
                    sessionId,
                    Optional.ofNullable(question).orElse(""),
                    answerSummary,
                    steps.size(),
                    first.getCreatedAt(),
                    last.getCreatedAt(),
                    last.getStatus()));
        }
        return ApiResponse.ok(result);
    }

    @GetMapping(value = "/api/conversations/{sessionId}/summary",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<TraceSummaryDTO> sessionSummary(@PathVariable("sessionId") String sessionId,
                                                       HttpServletRequest request) {
        return traceSummary(request, sessionId, null);
    }

    @GetMapping(value = "/api/executions/{executionId}/summary",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<TraceSummaryDTO> executionSummary(@PathVariable("executionId") String executionId,
                                                         HttpServletRequest request) {
        return traceSummary(request, null, executionId);
    }

    private ApiResponse<TraceSummaryDTO> traceSummary(
            HttpServletRequest request,
            String sessionId,
            String executionId) {
        Long userId = resolveUserId(request);
        if (Objects.isNull(userId)) {
            return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        }
        if (StringUtils.isNotBlank(sessionId) && !ownsSession(userId, sessionId)) {
            return ApiResponse.fail(ErrorCode.AUTH_FORBIDDEN);
        }
        long totalSteps, totalTurns;
        Long totalDuration;
        List<Object[]> toolStats;
        List<Object[]> slowestSteps = List.of();

        if (Objects.nonNull(executionId) && !executionId.isEmpty()) {
            List<AgentTraceStep> traces =
                    agentTraceStepRepository.findByExecutionIdOrderByTurnIndexAscIdAsc(executionId);
            if (!ownsTraceSessions(userId, traces)) {
                return ApiResponse.fail(ErrorCode.AUTH_FORBIDDEN);
            }
            if (StringUtils.isNotBlank(sessionId)
                    && traces.stream().anyMatch(step -> !sessionId.equals(step.getSessionId()))) {
                return ApiResponse.fail(ErrorCode.AUTH_FORBIDDEN);
            }
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

        List<TraceSummaryDTO.ToolStat> tools = new ArrayList<>();
        for (Object[] row : toolStats) {
            tools.add(new TraceSummaryDTO.ToolStat(
                    row[0] == null ? null : String.valueOf(row[0]),
                    ((Number) row[1]).longValue(),
                    Math.round(((Number) row[2]).doubleValue())));
        }
        List<TraceSummaryDTO.SlowStep> slowest = new ArrayList<>();
        for (Object[] row : slowestSteps) {
            if (slowest.size() >= 5) {
                break;
            }
            slowest.add(new TraceSummaryDTO.SlowStep(
                    row[0] == null ? null : String.valueOf(row[0]),
                    row[1] == null ? null : String.valueOf(row[1]),
                    row[2] instanceof Number n ? n : null));
        }
        return ApiResponse.ok(new TraceSummaryDTO(
                totalSteps,
                totalTurns,
                Optional.ofNullable(totalDuration).orElse(0L),
                tools,
                slowest));
    }

    private boolean ownsTraceSessions(Long userId, List<AgentTraceStep> traces) {
        if (traces == null || traces.isEmpty()) {
            return true;
        }
        return traces.stream()
                .map(AgentTraceStep::getSessionId)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .allMatch(id -> sessionAuthorization.owns(userId, id))
                && traces.stream().allMatch(step -> StringUtils.isNotBlank(step.getSessionId()));
    }

    // ========== 权限模式（按会话） ==========

    @GetMapping(value = "/api/conversations/{sessionId}/permission",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Object> getPermissionMode(@PathVariable("sessionId") String sessionId,
                                                  HttpServletRequest request) {
        Long userId = resolveUserId(request);
        if (Objects.isNull(userId)) {
            return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        }
        if (StringUtils.isBlank(sessionId)) {
            return ApiResponse.fail(ErrorCode.CONFIG_INVALID, "sessionId required");
        }
        sessionAuthorization.requireOwner(userId, sessionId);
        return ApiResponse.ok(permissionView(sessionId));
    }

    @PutMapping(value = "/api/conversations/{sessionId}/permission",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Object> putPermissionMode(@PathVariable("sessionId") String sessionId,
                                                 @RequestBody(required = false) Map<String, Object> body,
                                                 HttpServletRequest request) {
        Long userId = resolveUserId(request);
        if (Objects.isNull(userId)) {
            return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        }
        if (StringUtils.isBlank(sessionId)) {
            return ApiResponse.fail(ErrorCode.CONFIG_INVALID, "sessionId required");
        }
        sessionAuthorization.requireOwner(userId, sessionId);
        if (body == null) {
            body = Map.of();
        }
        String action = Objects.isNull(body.get("action")) ? "set" : String.valueOf(body.get("action"));
        if ("approve_plan".equalsIgnoreCase(action)) {
            permissionStore.approvePlan(sessionId);
            eventCenter.appendUserMessage(sessionId,
                    com.miniagent.common.MessageConstants.SYSTEM_MESSAGE_PREFIX
                            + "用户已批准 Plan，请按 todo 开始执行写操作与交付。");
            return ApiResponse.ok(permissionView(sessionId));
        }
        if ("grant_ask".equalsIgnoreCase(action)) {
            String tool = Objects.isNull(body.get("tool")) ? "" : String.valueOf(body.get("tool"));
            permissionStore.grantAskTool(sessionId, tool);
            eventCenter.appendUserMessage(sessionId,
                    com.miniagent.common.MessageConstants.SYSTEM_MESSAGE_PREFIX
                            + "用户已批准工具 " + tool + "，请继续。");
            return ApiResponse.ok(permissionView(sessionId));
        }

        // touched 用来区分"这次请求有没有显式改过任何一项"。
        // 原来的写法是 `else if (body.get("confirmPolicy") == null)`，只看 confirmPolicy 一项；
        // 加了 execPolicy 之后，只传 execPolicy 的请求会走到那个 else 里被强行重置成 default 模式 ——
        // 也就是"只想切执行策略，结果会话模式被悄悄改了"。这里改成显式记账。
        boolean touched = false;

        if (body.get("confirmPolicy") != null) {
            permissionStore.setConfirmPolicy(
                    sessionId, ConfirmPolicy.from(String.valueOf(body.get("confirmPolicy"))));
            touched = true;
        }
        if (body.get("execPolicy") != null) {
            String raw = String.valueOf(body.get("execPolicy")).trim();
            // 空串 / "default" / "inherit" 表示清除会话覆盖、跟随全局默认。
            if (raw.isEmpty() || "default".equalsIgnoreCase(raw) || "inherit".equalsIgnoreCase(raw)) {
                permissionStore.setExecPolicyOverride(sessionId, null);
            } else {
                ExecPolicy parsed = ExecPolicy.parse(raw);
                if (Objects.isNull(parsed)) {
                    // 刻意报错而不是兜底：静默兜底会让"我明明切到禁止了，怎么还在执行"极难查。
                    return ApiResponse.fail(ErrorCode.CONFIG_INVALID,
                            "execPolicy 取值无法识别: " + raw
                                    + "（可选 block / ask / allow，或 default 表示跟随全局）");
                }
                permissionStore.setExecPolicyOverride(sessionId, parsed);
            }
            touched = true;
        }
        if (body.get("mode") != null) {
            permissionStore.setMode(sessionId, PermissionMode.from(String.valueOf(body.get("mode"))));
            touched = true;
        } else if (!touched) {
            // 一个字段都没传的请求保持原有行为：回到 default 模式。
            permissionStore.setMode(sessionId, PermissionMode.from("default"));
        }
        return ApiResponse.ok(permissionView(sessionId));
    }

    /**
     * 权限视图 = 会话态 + 全局 exec 策略 + 最终生效的 exec 策略。
     *
     * <p>为什么要把"全局"和"生效"都发给前端：用户看到"需批准"时得知道这是自己设的、
     * 还是全局默认带下来的 —— 否则改完全局配置发现某个会话没跟着变，
     * 只能靠猜（那个会话有会话级覆盖）。前端也能据此把"跟随全局"渲染成灰色占位。
     */
    private Map<String, Object> permissionView(String sessionId) {
        Map<String, Object> view = new java.util.LinkedHashMap<>(permissionStore.toView(sessionId));
        ExecPolicy global = execPolicyService.globalDefault();
        ExecPolicy effective = execPolicyService.effective(sessionId);
        view.put("execPolicyGlobal", global.wireName());
        view.put("execPolicyGlobalLabel", global.labelZh());
        view.put("execPolicyEffective", effective.wireName());
        view.put("execPolicyEffectiveLabel", effective.labelZh());
        view.put("execPolicyFollowsGlobal", Objects.isNull(permissionStore.getExecPolicyOverride(sessionId)));
        view.put("execPolicyOptions", java.util.Arrays.stream(ExecPolicy.values())
                .map(p -> Map.of("value", p.wireName(), "label", p.labelZh()))
                .toList());
        return view;
    }

    @PatchMapping(value = "/api/conversations/{sessionId}/todos/{id}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Object> confirmTodo(@PathVariable("sessionId") String sessionId,
                                           @PathVariable("id") int id,
                                           HttpServletRequest request) {
        Long userId = resolveUserId(request);
        if (Objects.isNull(userId)) {
            return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        }
        if (StringUtils.isBlank(sessionId)) {
            return ApiResponse.fail(ErrorCode.CONFIG_INVALID, "sessionId required");
        }
        if (!ownsSession(userId, sessionId)) {
            return ApiResponse.fail(ErrorCode.AUTH_FORBIDDEN);
        }
        if (id <= 0) {
            return ApiResponse.fail(ErrorCode.CONFIG_INVALID, "id required");
        }
        var snapOpt = plannerStateStore.get(sessionId);
        if (snapOpt.isPresent() && !snapOpt.get().graph().isEmpty()) {
            StateSnapshot snap = snapOpt.get();
            TaskGraph next = todoProjector.confirmByTodoId(snap.graph(), id);
            if (next == snap.graph()) {
                return ApiResponse.fail(ErrorCode.TODO_INVALID_STATE,
                        "图节点不是等待确认状态");
            }
            try {
                plannerStateStore.commit(
                        sessionId, snap.version(), snap.withGraph(next));
            } catch (PlannerStateStore.VersionConflictException e) {
                return ApiResponse.fail(
                        ErrorCode.TODO_INVALID_STATE, "状态已变更，请刷新后重试");
            }
            todoProjector.project(sessionId, next);
            return ApiResponse.ok(new TodoConfirmDTO(id, true));
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
        return ApiResponse.ok(new TodoConfirmDTO(id, true));
    }

    // ========== MCP 状态 ==========

    @GetMapping(value = "/api/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<McpStatusDTO> mcpStatus(HttpServletRequest request) {
        Long userId = resolveUserId(request);
        if (Objects.isNull(userId)) {
            return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        }
        boolean enabled = Objects.nonNull(mcpProperties) && mcpProperties.isEnabled();
        List<String> tools = Objects.isNull(mcpToolBridge) ? List.of() : mcpToolBridge.registeredToolNames();
        int serverCount = Objects.isNull(mcpProperties) || Objects.isNull(mcpProperties.getServers())
                ? 0 : mcpProperties.getServers().size();
        return ApiResponse.ok(new McpStatusDTO(
                enabled, serverCount, tools, tools.size()));
    }

    @PostMapping(value = "/api/mcp/servers", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Object> mcpRefreshAll(HttpServletRequest request) {
        return mcpRefresh(null, request);
    }

    @PostMapping(value = "/api/mcp/servers/{serverId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ApiResponse<Object> mcpRefreshOne(@PathVariable("serverId") String serverId,
                                             HttpServletRequest request) {
        return mcpRefresh(serverId, request);
    }

    private ApiResponse<Object> mcpRefresh(String serverId, HttpServletRequest request) {
        Long userId = resolveUserId(request);
        if (Objects.isNull(userId)) {
            return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        }
        if (Objects.isNull(mcpToolBridge)) {
            return ApiResponse.fail(ErrorCode.MCP_NOT_ENABLED);
        }
        try {
            String target = StringUtils.isNotBlank(serverId) && !"null".equals(serverId)
                    ? serverId : ALL_MCP_SERVERS;
            systemAdminService.recordAudit(
                    userId,
                    MCP_REFRESH_AUDIT_ACTION,
                    MCP_SERVER_AUDIT_TARGET,
                    target,
                    null,
                    Map.of("scope", ALL_MCP_SERVERS.equals(target) ? "all" : "single"));
            if (StringUtils.isNotBlank(serverId) && !"null".equals(serverId)) {
                return ApiResponse.ok(mcpToolBridge.refreshServer(serverId));
            }
            return ApiResponse.ok(mcpToolBridge.refreshAll());
        } catch (Exception e) {
            return ApiResponse.fail(ErrorCode.MCP_CONNECTION_FAILED, Objects.nonNull(e.getMessage()) ? e.getMessage() : "refresh failed");
        }
    }

    // ========== 模型配置（按用户） ==========

    @GetMapping(value = "/api/model", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> getModelConfig(HttpServletRequest request) {
        Long userId = resolveUserId(request);
        if (Objects.isNull(userId)) {
            return Map.of("success", false, "message", "Not authenticated");
        }
        return userModelConfigService.getView(userId);
    }

    @PutMapping(value = "/api/model", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> putModelConfig(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Long userId = resolveUserId(request);
        if (Objects.isNull(userId)) {
            return Map.of("success", false, "message", "Not authenticated");
        }
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
        if (Objects.isNull(v)) {
            return null;
        }
        return String.valueOf(v);
    }
}
