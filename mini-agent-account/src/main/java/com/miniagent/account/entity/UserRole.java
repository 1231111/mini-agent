package com.miniagent.account.entity;

/**
 * 角色。取值必须与云端 agent 的同名枚举逐字一致 —— 它存在
 * {@code users.role} 这一列里，写进去的是 {@code @Enumerated(EnumType.STRING)} 的字符串。
 * 两边枚举名一旦不同，云端 agent 读这条记录时会直接抛 {@code IllegalArgumentException}。
 */
public enum UserRole {
    USER,
    TENANT_ADMIN,
    SYSTEM_ADMIN
}
