package com.miniagent.common;

/**
 * 会话角色枚举，替换散落在各处的 "user" / "assistant" / "system" 魔法字符串。
 */
public enum ChatRole {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system");

    private final String value;

    ChatRole(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /** 根据字符串值查找枚举，找不到返回 null */
    public static ChatRole of(String value) {
        if (value == null) return null;
        for (ChatRole r : values()) {
            if (r.value.equals(value)) return r;
        }
        return null;
    }
}
