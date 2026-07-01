package com.miniagent.web;

import com.miniagent.application.AgentChatApplicationService;
import com.miniagent.agent.core.TokenUsageTracker;
import com.miniagent.config.service.AuthService;
import com.miniagent.config.service.FileStorageService;
import com.miniagent.web.dto.ChatRequest;
import com.miniagent.web.dto.FileAttachment;
import com.miniagent.web.dto.FileRef;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import com.miniagent.agent.web.FileContentExtractor;
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

@Controller
public class MiniAgentChatPageController {

    @Autowired
    private  AgentChatApplicationService agentService;
    @Autowired
    private  AuthService authService;
    @Autowired
    private  FileStorageService fileStorageService;
    @Autowired
    private  FileContentExtractor fileContentExtractor;
    @Autowired
    private  ChatMessageRepository chatMessageRepository;
    @Autowired
    private  ChatTaskRepository chatTaskRepository;

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
                    Cookie cookie = new Cookie("uid", String.valueOf(user.getId()));
                    cookie.setPath("/");
                    cookie.setMaxAge(7 * 24 * 3600);
                    cookie.setHttpOnly(true);
                    response.addCookie(cookie);
                    Cookie nameCookie = new Cookie("uname", user.getUsername());
                    nameCookie.setPath("/");
                    nameCookie.setMaxAge(7 * 24 * 3600);
                    response.addCookie(nameCookie);
                    return Map.<String, Object>of("success", true, "userId", user.getId(), "username", user.getUsername(), "displayName", user.getDisplayName());
                })
                .orElse(Map.of("success", false, "message", "Invalid credentials"));
    }

    @PostMapping(value = "/api/register", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> register(@RequestBody RegisterRequest req, HttpServletResponse response) {
        return authService.register(req.getUsername(), req.getPassword(), req.getDisplayName())
                .map(user -> {
                    Cookie cookie = new Cookie("uid", String.valueOf(user.getId()));
                    cookie.setPath("/");
                    cookie.setMaxAge(7 * 24 * 3600);
                    cookie.setHttpOnly(true);
                    response.addCookie(cookie);
                    Cookie nameCookie = new Cookie("uname", user.getUsername());
                    nameCookie.setPath("/");
                    nameCookie.setMaxAge(7 * 24 * 3600);
                    response.addCookie(nameCookie);
                    return Map.<String, Object>of("success", true, "userId", user.getId(), "username", user.getUsername());
                })
                .orElse(Map.of("success", false, "message", "Username already exists"));
    }

    @GetMapping("/api/logout")
    public String logout(HttpServletResponse response) {
        Cookie uidCookie = new Cookie("uid", "");
        uidCookie.setPath("/");
        uidCookie.setMaxAge(0);
        response.addCookie(uidCookie);
        Cookie unameCookie = new Cookie("uname", "");
        unameCookie.setPath("/");
        unameCookie.setMaxAge(0);
        response.addCookie(unameCookie);
        return "redirect:/";
    }


    @PostMapping(value = "/api/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> uploadFile(@RequestParam("file") MultipartFile file,
                                           @RequestParam(value = "sessionId", defaultValue = "default") String sessionId,
                                           HttpServletRequest request) {
        Long userId = getUserIdFromCookie(request);
        if (userId == null) return Map.of("success", false, "message", "Not authenticated");
        try {
            String base64 = java.util.Base64.getEncoder().encodeToString(file.getBytes());
            var saved = fileStorageService.saveFile(userId, sessionId, file.getOriginalFilename(), file.getContentType(), base64);
            return Map.of("success", true,
                          "filePath", saved.getFilePath(),
                          "filename", saved.getOriginalFilename(),
                          "mimeType", saved.getMimeType() != null ? saved.getMimeType() : "application/octet-stream",
                          "fileSize", saved.getFileSize());
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
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("uid".equals(cookie.getName())) {
                    try {
                        return Long.parseLong(cookie.getValue());
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
            }
        }
        return null;
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
        List<String> images = req.getImages() == null ? List.of() : req.getImages();
        List<FileAttachment> files = req.getFiles() == null ? List.of() : req.getFiles();
        StringBuilder queryTask = new StringBuilder(message != null ? message : "");
        // Handle fileRefs (pre-uploaded files)
        if (req.getFileRefs() != null && !req.getFileRefs().isEmpty()) {
            queryTask.append(getFileMessageContext(req.getFileRefs()));
        }
        return agentService.chatStreamMultimodal(userId, sessionId, message, images, files);
    }

    private String getFileMessageContext(List<FileRef> refs) {
        if (CollectionUtils.isEmpty(refs)) {
            return null;
        }
        StringBuilder fileMessage = new StringBuilder();
        for (var ref : refs) {
            long fsize = ref.getFileSize();
            if (fsize < 5000) {
                // Small file: read and embed
                try {
                    byte[] bytes = Files.readAllBytes(Path.of(ref.getFilePath()));
                    String text = new String(bytes, StandardCharsets.UTF_8);
                    fileMessage.append("\n\n--- File: ").append(ref.getFilename()).append(" ---\n");
                    fileMessage.append(text);
                    fileMessage.append("\n--- End File ---");
                } catch (Exception e) {
                    fileMessage.append("\n\n[File: ").append(ref.getFilename()).append(" at ").append(ref.getFilePath()).append("]");
                }
            } else {
                // Large file: just give path, let agent use read_file
                fileMessage.append("\n\n[User uploaded file: ").append(ref.getFilename()).append(" (").append(fsize).append(" bytes)]");
                fileMessage.append("\nFile saved at: ").append(ref.getFilePath());
                fileMessage.append("\nUse read_file tool to read specific sections. Do NOT read the entire file at once.");
            }
        }
        return fileMessage.toString();
    }

    /** Generate image static resources */
    private static final Path IMAGE_DIR = Paths.get(".", "generated-images");



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
            Path baseDir = IMAGE_DIR.toAbsolutePath().normalize();
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
    public Map<String, Object> taskStatus(@RequestParam("sessionId") String sessionId) {
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
    public Object getConversation(@RequestParam("sessionId") String sessionId) {
        var conv = agentService.getConversation(sessionId);
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
        chatTaskRepository.deleteBySessionId(sessionId);
        return java.util.Map.of("success", true);
    }

    @GetMapping(value = "/api/token-usage", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public TokenUsageTracker.UsageStats tokenUsage(@RequestParam("sessionId") String sessionId) {
        return TokenUsageTracker.get(sessionId);
    }

    @GetMapping(value = "/api/token-usage/all", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, TokenUsageTracker.UsageStats> allTokenUsage() {
        return TokenUsageTracker.getAll();
    }
}
