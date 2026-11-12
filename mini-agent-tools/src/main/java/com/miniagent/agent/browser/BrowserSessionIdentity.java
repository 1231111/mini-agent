package com.miniagent.agent.browser;

import com.miniagent.agent.tool.WorkspaceContext;
import com.miniagent.common.context.OwnerContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Browser resources belong to the authenticated run, never to a model-selected session. */
public final class BrowserSessionIdentity {
    private BrowserSessionIdentity() {}

    public static String resolve(String requestedSessionId) {
        String session = WorkspaceContext.getSessionOverride();
        Long user = OwnerContext.currentUserId();
        String tenant = OwnerContext.currentTenantId();
        if (session == null || session.isBlank() || user == null || tenant == null || tenant.isBlank()) {
            throw new SecurityException("浏览器操作缺少可信用户与会话上下文");
        }
        if (requestedSessionId != null && !requestedSessionId.isBlank()
                && !"default".equals(requestedSessionId) && !session.equals(requestedSessionId)) {
            throw new SecurityException("不能操作其他聊天的浏览器会话；请省略 sessionId");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (tenant + "\u0000" + user + "\u0000" + session).getBytes(StandardCharsets.UTF_8));
            return "browser-" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
