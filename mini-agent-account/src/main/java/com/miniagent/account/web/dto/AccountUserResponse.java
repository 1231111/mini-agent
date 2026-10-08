package com.miniagent.account.web.dto;

import com.miniagent.account.entity.User;
import com.miniagent.account.entity.UserRole;

/**
 * 登录 / 注册成功的响应体。
 *
 * <p><b>字段名是与调用方的线上契约，改名等于破坏兼容。</b>
 * 客户机本地后端的 {@code CloudAccountClient.parse()} 按名字读
 * {@code data.userId} / {@code data.username} / {@code data.displayName} / {@code data.role}，
 * 其中前两个是必需的 —— 少任何一个它会抛 {@code AUTH.03.03}（"返回内容无法解析"），
 * 而那个错误在界面上显示成"云端版本与本机不一致"，排查方向会完全跑偏。
 *
 * <p><b>刻意没有 token 字段。</b>账号服务不签发会话，登录成功只回答"你是谁"。
 * 会话由收到请求的那一侧自己签：客户机本地后端签本地 JWT，云端 agent 签自己的。
 * 这样 HS256 密钥只需要存在于签发方，不必在两个服务之间同步 ——
 * 少一处可以配错、且配错之后表现为"部分用户莫名 401"的地方。
 *
 * <p>{@code userId} 是 {@code Long}，调用方那边按字符串取再当映射键用，
 * 不对它的形状做假设（换成 UUID 也不用改代码）。
 */
public record AccountUserResponse(Long userId, String username, String displayName,
                                  Long tenantId, UserRole role) {

    public static AccountUserResponse of(User user) {
        return new AccountUserResponse(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getTenantId(), user.getRole());
    }
}
