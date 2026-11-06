package com.miniagent.memory.contract;

import java.util.List;
import java.util.Objects;

/** 一条 SOP 修订的正文。修订不可变，内容哈希标识「同一份内容」。 */
public record ProcedureContent(String description, List<String> preconditions,
                               List<String> steps, List<String> successConditions) {

    public ProcedureContent {
        description = description == null ? "" : description.trim();
        preconditions = clean(preconditions);
        steps = clean(steps);
        successConditions = clean(successConditions);
    }

    /** 长度前缀编码，避免「a|b」与「a」「b」之类的拼接碰撞。 */
    public String hash() {
        StringBuilder sb = new StringBuilder();
        append(sb, description);
        for (List<String> list : List.of(preconditions, steps, successConditions)) {
            sb.append('[').append(list.size()).append(']');
            list.forEach(item -> append(sb, item));
        }
        return Hashes.sha256Hex(sb.toString());
    }

    /** 拼进安全扫描与提示词的纯文本视图。 */
    public String text() {
        return String.join("\n", description, String.join("\n", preconditions),
                String.join("\n", steps), String.join("\n", successConditions));
    }

    private static void append(StringBuilder sb, String value) {
        sb.append(value.length()).append(':').append(value);
    }

    private static List<String> clean(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).map(String::trim)
                .filter(s -> !s.isEmpty()).toList();
    }
}
