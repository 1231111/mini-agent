package com.miniagent.memory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 一条记忆：用户问题 + 智能体回复概要（非全文）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemoryEntry {

    private String id = UUID.randomUUID().toString();
    private MemoryType type;
    private String userQuery;
    private String agentSummary;
    private String keyInsights;
    private LocalDateTime createdAt = LocalDateTime.now();
    private String sessionId;

    public static MemoryEntry of(MemoryType type, String userQuery, String agentSummary,
                                 String keyInsights, String sessionId) {
        MemoryEntry e = new MemoryEntry();
        e.setType(type);
        e.setUserQuery(userQuery);
        e.setAgentSummary(agentSummary);
        e.setKeyInsights(keyInsights);
        e.setSessionId(sessionId);
        return e;
    }

    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("id: ").append(id).append("\n");
        sb.append("type: ").append(type.name().toLowerCase()).append("\n");
        sb.append("date: ").append(createdAt.toLocalDate()).append("\n");
        if (sessionId != null) {
            sb.append("sessionId: ").append(sessionId).append("\n");
        }
        sb.append("---\n\n");
        sb.append("## 用户问题\n");
        sb.append(userQuery).append("\n\n");
        sb.append("## 智能体概要\n");
        sb.append(agentSummary).append("\n\n");
        if (keyInsights != null && !keyInsights.isBlank()) {
            sb.append("## 关键洞见\n");
            sb.append(keyInsights).append("\n\n");
        }
        sb.append("---\n记录时间: ").append(createdAt).append("\n");
        return sb.toString();
    }
}
