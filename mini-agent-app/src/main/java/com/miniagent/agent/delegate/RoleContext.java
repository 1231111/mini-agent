package com.miniagent.agent.delegate;

/**
 * 角色上下文
 * 使用 ThreadLocal 在请求链中传递用户选择的角色
 */
public class RoleContext {

    private static final ThreadLocal<String> CURRENT_ROLE = new ThreadLocal<>();

    /**
     * 设置当前角色
     * @param role 角色ID
     */
    public static void setRole(String role) {
        CURRENT_ROLE.set(role);
    }

    /**
     * 获取当前角色
     * @return 角色ID，如果未设置返回空字符串
     */
    public static String getRole() {
        String role = CURRENT_ROLE.get();
        return role != null ? role : "";
    }

    /**
     * 清除当前角色（请求结束后调用）
     */
    public static void clear() {
        CURRENT_ROLE.remove();
    }

    /**
     * 检查是否设置了角色
     * @return 是否有角色
     */
    public static boolean hasRole() {
        String role = CURRENT_ROLE.get();
        return role != null && !role.isEmpty();
    }
}
