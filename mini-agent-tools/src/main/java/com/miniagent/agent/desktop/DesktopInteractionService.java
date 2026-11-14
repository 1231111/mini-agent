package com.miniagent.agent.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.WorkspaceContext;
import com.miniagent.common.context.OwnerContext;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One physical input device: observations and input leases cannot be shared between chats. */
@Service
public class DesktopInteractionService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ACTIONS = Set.of("click", "double_click", "right_click", "type", "key", "scroll", "focus");
    private final DesktopBackend backend;
    private final Clock clock;
    @Value("${agent.desktop.enabled:false}") private boolean enabled;
    private volatile Observation observation;
    private String leaseOwner;
    private volatile InputLease inputLease;
    private Instant leaseUntil = Instant.EPOCH;
    private static final class InputLease {
        final String session;
        final AtomicBoolean revoked = new AtomicBoolean();
        volatile Thread worker;
        InputLease(String session) { this.session = session; }
        void revoke() {
            revoked.set(true);
            Thread active = worker;
            if (active != null && active != Thread.currentThread()) active.interrupt();
        }
    }
    private record Observation(String id, String owner, DesktopBackend.Window window,
                               List<DesktopBackend.Window> windows, Instant expires) {}

    /** Trusted approval boundary: never extend an observation or consume native input here. */
    public Optional<Instant> observationExpiresAt(Long userId, String tenant, String session, String id) {
        String requestedOwner;
        try { requestedOwner = identity(userId, tenant, session); }
        catch (IllegalArgumentException invalidOwner) { return Optional.empty(); }
        // Approval must not wait on a native action holding the service monitor.
        Observation observed = observation;
        InputLease lease = inputLease;
        if (!enabled || observed == null || lease == null || lease.revoked.get()
                || !Objects.equals(session, lease.session) || !requestedOwner.equals(observed.owner())
                || !observed.id().equals(id) || !clock.instant().isBefore(observed.expires())
                || observation != observed) return Optional.empty();
        return Optional.of(observed.expires());
    }

    public DesktopInteractionService() { this(new WindowsDesktopBackend(), Clock.systemUTC(), false); }
    DesktopInteractionService(DesktopBackend backend, Clock clock, boolean enabled) {
        this.backend = backend; this.clock = clock; this.enabled = enabled;
    }
    private static String identity(Long userId, String tenant, String session) {
        if (userId == null || tenant == null || tenant.isBlank() || session == null || session.isBlank())
            throw new IllegalArgumentException("桌面工具需要已认证的用户和当前会话");
        return tenant.length() + ":" + tenant + ":" + userId + ":" + session;
    }
    private static String owner() {
        return identity(OwnerContext.currentUserId(), OwnerContext.currentTenantId(), WorkspaceContext.getSessionOverride());
    }
    private ToolResult available() {
        if (!enabled) return ToolResult.failure(ToolErrorCode.PERMISSION_DENIED,
                "桌面控制未启用；本机客户端可配置 agent.desktop.enabled=true。", false);
        if (!backend.available()) return ToolResult.failure(ToolErrorCode.DEPENDENCY_UNAVAILABLE, backend.unavailableReason(), false);
        if (Thread.currentThread().isInterrupted()) return ToolResult.failure(ToolErrorCode.CANCELLED, "桌面操作已取消", false);
        return null;
    }
    private void expireLease() {
        if (leaseOwner != null && !clock.instant().isBefore(leaseUntil)) {
            releaseIdentity(leaseOwner);
        }
    }
    public synchronized ToolResult observe() {
        ToolResult unavailable = available(); if (unavailable != null) return unavailable;
        try {
            String owner = owner();
            expireLease();
            if (leaseOwner != null && !leaseOwner.equals(owner))
                return ToolResult.failure(ToolErrorCode.RESOURCE_BUSY, "本机桌面正由另一任务控制，请稍后再试。", true);
            if (inputLease != null && inputLease.revoked.get())
                return ToolResult.failure(ToolErrorCode.CANCELLED, "本任务的桌面控制已暂停或取消，请恢复任务后重新观察。", false);
            leaseOwner = owner;
            if (inputLease == null) inputLease = new InputLease(WorkspaceContext.getSessionOverride());
            leaseUntil = clock.instant().plusSeconds(120);
            return captureObservation(owner);
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENT, e.getMessage(), false);
        } catch (RuntimeException e) {
            observation = null;
            if (leaseOwner != null) {
                try { releaseIdentity(leaseOwner); }
                catch (RuntimeException cleanupFailure) { e.addSuppressed(cleanupFailure); }
            }
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, "桌面观察失败: " + e.getMessage(), false);
        }
    }
    private ToolResult captureObservation(String owner) {
        observation = null;
        try {
            Path root = WorkspaceContext.getRootOverride();
            if (root == null) throw new IllegalArgumentException("桌面截图需要可信的会话工作目录");
            Files.createDirectories(root);
            root = root.toRealPath();
            var foreground = backend.foreground();
            var capture = backend.capture(foreground);
            if (!WindowsDesktopBackend.sameTarget(foreground, backend.foreground()))
                throw new IllegalStateException("截图期间焦点改变，请重新观察");
            String id = UUID.randomUUID().toString();
            Path image = root.resolve("desktop-observation-" + id + ".png");
            if (!ImageIO.write(capture.image(), "png", image.toFile())) throw new IllegalStateException("PNG编码器不可用");
            var windows = backend.windows();
            var expires = clock.instant().plusSeconds(120);
            observation = new Observation(id, owner, capture.window(), windows, expires);
            leaseUntil = expires;
            ObjectNode result = JSON.createObjectNode(); result.put("success", true);
            result.put("observationId", id); result.put("expiresAt", expires.toString());
            result.put("coordinateSpace", "window_physical_pixels");
            result.put("imageWidth", capture.image().getWidth()); result.put("imageHeight", capture.image().getHeight());
            result.set("window", JSON.valueToTree(capture.window())); result.set("windows", JSON.valueToTree(windows));
            result.put("path", image.toString());
            result.putArray("tool_images").addObject().put("path", image.toString()).put("mimeType", "image/png");
            result.put("message", "请根据此截图选择一个动作；坐标相对截图左上角。每次动作后必须使用新的observationId，"
                    + "旧观察不能重放。切换应用用focus和此列表中的targetWindowId。权限审批可能临时切走焦点，执行时只恢复已批准的目标窗口。");
            return ToolResult.success(result.toString());
        } catch (Exception e) { throw new IllegalStateException(e.getMessage(), e); }
    }
    public synchronized ToolResult act(String observationId, DesktopBackend.Action action) {
        ToolResult unavailable = available(); if (unavailable != null) return unavailable;
        boolean started = false;
        InputLease activeLease = null;
        ToolResult result = null;
        try {
            String owner = owner(); expireLease();
            if (observation == null || !owner.equals(observation.owner()) || !owner.equals(leaseOwner)
                    || !observation.id().equals(observationId) || !clock.instant().isBefore(observation.expires()))
                return ToolResult.failure(ToolErrorCode.CONFLICT, "STALE_OBSERVATION: 观察已过期、已消费或不属于当前任务，请重新desktop_observe。", false);
            activeLease = inputLease;
            if (activeLease == null || activeLease.revoked.get())
                return ToolResult.failure(ToolErrorCode.CANCELLED, "桌面操作已取消，请重新观察。", false);
            validate(action, observation.window());
            DesktopBackend.Window target = observation.window();
            if ("focus".equals(action.action())) {
                target = observation.windows().stream().filter(w -> w.id().equals(action.targetWindowId())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("只能切换到当前观察中列出的窗口"));
            }
            // All validation precedes consumption; an attempted input is never automatically replayed.
            activeLease.worker = Thread.currentThread();
            if (activeLease.revoked.get() || Thread.currentThread().isInterrupted())
                return ToolResult.failure(ToolErrorCode.CANCELLED, "桌面操作已取消", false);
            observation = null;
            started = true;
            backend.activate(target);
            checkActive(activeLease);
            backend.execute(target, action);
            checkActive(activeLease);
            ToolResult observed = captureObservation(owner);
            checkActive(activeLease);
            ObjectNode data = (ObjectNode) JSON.readTree(observed.legacyText());
            data.put("action", action.action()); data.put("inputAccepted", true);
            data.put("verificationRequired", true); // Accepted input is not proof the business task succeeded.
            result = ToolResult.success(data.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            observation = null;
            result = ToolResult.unknown("桌面动作被中断，部分输入可能已生效；请重新观察，禁止直接重试。", null);
        } catch (Exception e) {
            if (started) observation = null;
            result = started ? ToolResult.unknown("桌面动作回执未确认: " + e.getMessage() + "；请重新观察，禁止直接重试。", null)
                    : ToolResult.failure(ToolErrorCode.INVALID_ARGUMENT, e.getMessage(), false);
        } finally {
            if (started) {
                try { backend.releaseInputs(); }
                catch (RuntimeException e) {
                    observation = null;
                    if (activeLease != null) activeLease.revoked.set(true);
                    result = ToolResult.unknown("无法确认桌面输入已释放: " + e.getMessage() + "；请检查键鼠状态，禁止直接重试动作。", null);
                }
            }
            if (activeLease != null) activeLease.worker = null;
        }
        return result;
    }
    private static void checkActive(InputLease lease) throws InterruptedException {
        if (lease.revoked.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException("桌面操作已撤销");
    }
    static void validate(DesktopBackend.Action action, DesktopBackend.Window window) {
        if (action == null || !ACTIONS.contains(action.action())) throw new IllegalArgumentException("不支持的桌面动作");
        if (Set.of("click", "double_click", "right_click").contains(action.action())
                || "scroll".equals(action.action()) && (action.x() != null || action.y() != null)) {
            if (action.x() == null || action.y() == null || action.x() < 0 || action.y() < 0
                    || action.x() >= window.width() || action.y() >= window.height())
                throw new IllegalArgumentException("点击坐标必须位于当前窗口截图内");
        }
        if ("type".equals(action.action()) && (action.text() == null || action.text().isEmpty() || action.text().length() > 2048))
            throw new IllegalArgumentException("单次输入必须为1至2048个UTF-16字符");
        if ("type".equals(action.action()) && action.text().chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("type仅接受文字；换行、Tab和其他控制键请单独使用key动作");
        if ("key".equals(action.action())) {
            if (action.key() == null || action.key().isBlank() || action.key().split("\\+", -1).length > 4)
                throw new IllegalArgumentException("按键组合最多4个按键");
            for (String key : action.key().split("\\+", -1)) WindowsDesktopBackend.keyCode(key);
        }
        if ("scroll".equals(action.action()) && (action.amount() == null || action.amount() == 0 || Math.abs((long) action.amount()) > 10))
            throw new IllegalArgumentException("单次滚动amount必须为-10至10且非0");
        if ("focus".equals(action.action()) && (action.targetWindowId() == null || action.targetWindowId().isBlank()))
            throw new IllegalArgumentException("focus必须指定观察列表中的targetWindowId");
    }
    public synchronized ToolResult release() {
        try {
            releaseIdentity(owner());
            return ToolResult.success("{\"success\":true,\"message\":\"已释放本任务的桌面观察与输入租约\"}");
        } catch (Exception e) { return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, e.getMessage(), false); }
    }
    public synchronized void releaseFor(Long userId, String tenant, String session) {
        releaseIdentity(identity(userId, tenant, session));
    }
    /** Called by authenticated pause/cancel endpoints and task finalization, never from model arguments. */
    public void revokeSession(String session) {
        InputLease lease = inputLease;
        if (lease == null || !Objects.equals(lease.session, session)) return;
        // Publish cancellation before taking the operation lock: a not-yet-registered
        // worker must also see it and cannot slip an input past a pause request.
        lease.revoke();
        synchronized (this) {
            if (inputLease == lease && leaseOwner != null) releaseIdentity(leaseOwner);
        }
    }
    private void releaseIdentity(String owner) {
        if (!owner.equals(leaseOwner)) return;
        observation = null;
        if (inputLease != null) inputLease.revoke();
        backend.releaseInputs(); leaseOwner = null; inputLease = null; leaseUntil = Instant.EPOCH;
    }
    @PreDestroy public synchronized void close() {
        observation = null;
        if (leaseOwner != null) backend.releaseInputs();
        leaseOwner = null; inputLease = null;
    }
}
