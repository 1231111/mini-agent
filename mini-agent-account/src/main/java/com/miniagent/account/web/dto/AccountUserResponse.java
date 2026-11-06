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
 * <p>{@code memoryToken} 和 {@code memoryRefreshCredential} 仅用于调用 memory-service，
 * 不作为桌面端本地会话凭证。桌面端本地会话仍由本地后端独立签发和校验。
 *
 * <p>{@code userId} 是 {@code Long}，调用方那边按字符串取再当映射键用，
 * 不对它的形状做假设（换成 UUID 也不用改代码）。
 */
public record AccountUserResponse(Long userId, String username, String displayName,
                                  Long tenantId, UserRole role, String memoryToken,
                                  String memoryRefreshCredential) {

    public static AccountUserResponse of(User user) {
        return new AccountUserResponse(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getTenantId(), user.getRole(), null, null);
    }

    public static AccountUserResponse of(User user, String memoryToken, String memoryRefreshCredential) {
        return new AccountUserResponse(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getTenantId(), user.getRole(), memoryToken, memoryRefreshCredential);
    }
}
