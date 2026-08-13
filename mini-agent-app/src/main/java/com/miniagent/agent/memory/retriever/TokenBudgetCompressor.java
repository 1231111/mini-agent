package com.miniagent.agent.memory.retriever;

import com.miniagent.memory.model.ScoredMemory;
import com.miniagent.memory.retriever.ContextCompressor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Token 预算压缩器：在 token 限制内截断记忆内容。
 * 简化实现：按字符数估算 token（中文约 1.5 char/token，英文约 4 char/token）。
 */
@Component
public class TokenBudgetCompressor implements ContextCompressor {

    private static final double CHARS_PER_TOKEN = 2.0; // 中英混合估算

    @Override
    public String compress(List<ScoredMemory> memories, int maxTokens) {
        if (memories == null || memories.isEmpty()) return "";

        int maxChars = (int) (maxTokens * CHARS_PER_TOKEN);
        StringBuilder sb = new StringBuilder();
        int used = 0;

        for (ScoredMemory sm : memories) {
            String content = sm.getMemory().getContent();
            if (content == null) continue;

            // 预估这段内容的字符数
            int entryChars = content.length() + 10; // 加上格式开销
            if (used + entryChars > maxChars) {
                // 截断
                int remaining = maxChars - used - 10;
                if (remaining > 50) {
                    // 能塞一部分
                    sb.append("- ").append(content, 0, Math.min(content.length(), remaining)).append("...\n");
                }
                break;
            }

            sb.append("- ").append(content).append("\n");
            used += entryChars;
        }

        return sb.toString().trim();
    }
}
