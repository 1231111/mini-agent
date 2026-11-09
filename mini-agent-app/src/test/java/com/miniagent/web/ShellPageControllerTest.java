package com.miniagent.web;

import com.miniagent.config.security.SecurityConfig;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellPageControllerTest {

    @Test
    void pageRoutesReturnViewsUnconditionally() {
        // 方法签名里没有 HttpServletRequest，天然无法做 Bearer 判断 —— 页面骨架无条件放行。
        ShellPageController controller = new ShellPageController();
        assertEquals("memory", controller.memory());
        assertEquals("knowledge", controller.knowledge());
        assertEquals("apps", controller.apps());
    }

    @Test
    void pageSkeletonsArePublicButShellDataApisAreNot() {
        assertTrue(Arrays.asList(SecurityConfig.PUBLIC_PATHS).contains("/memory"));
        assertTrue(Arrays.asList(SecurityConfig.PUBLIC_PATHS).contains("/knowledge"));
        assertTrue(Arrays.asList(SecurityConfig.PUBLIC_PATHS).contains("/apps"));

        // 数据接口一条都不能在放行名单里：必须逐条带 Bearer + 会话归属校验。
        Arrays.stream(SecurityConfig.PUBLIC_PATHS)
                .forEach(path -> assertFalse(path.startsWith("/api/shell"),
                        "数据接口不能被放行: " + path));
    }
}
