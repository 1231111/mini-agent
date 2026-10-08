package com.miniagent.config.cloud;

/**
 * 云端账号服务返回的身份。
 *
 * <p>为什么不复用 web 层的 {@code UserDTO}：那个 DTO 带 {@code tenantId} 和 {@code token}，
 * 这两个字段拿到本地来是<b>无意义且危险</b>的 ——
 * {@code tenantId} 是云端那套租户体系里的 id，本地租户是「这台设备」的概念，混用会把数据挂错租户；
 * {@code token} 是云端会话凭证，本地鉴权还要查 {@code auth_sessions}，直接拿来当本地会话用必然 401。
 * 少带一个字段就少一处误用。
 *
 * @param externalId  云端账号的稳定 id，作为本地影子用户的映射键（必填）
 * @param username    云端用户名（本地可能因重名避让而不同，见 CloudAccountService）
 * @param displayName 云端显示名，可为空
 * @param role        云端角色名（如 {@code USER}），可为空；本地影子用户不采纳它，理由见 CloudAccountService
 */
public record CloudUser(String externalId, String username, String displayName, String role) {
}
