package com.miniagent.web;

import com.miniagent.config.security.SecurityConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.ViewResolver;
import org.springframework.web.servlet.view.AbstractView;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * 页面导航与数据接口的鉴权边界。
 *
 * <p>本服务是无状态的：只认 {@code Authorization: Bearer}，没有 cookie，
 * 也不接受查询参数里的 token。而浏览器发起整页导航
 * （{@code window.location.href = '/trace'}）时<b>不会</b>带任何自定义头。
 * 所以页面骨架必须在服务端放行，让页面自己加载后用 fetch 带 Bearer 取数据；
 * 真没登录时由 security.js 收到第一个 401 再跳 /login。
 *
 * <p>曾经的 bug：{@code showTracePage} 里留了一句
 * {@code if (resolveUserId(request) == null) return "login";}，
 * 于是在 /trace 地址上直接渲染登录页。token 其实好好存在壳里，
 * 用户看到的却是"点轨迹被踢下线"。这组测试就是钉住这条边界不再被改回去。
 */
class TracePageNavigationTest {

    /**
     * 只关心"路由返回了哪个视图名"，不真的渲染 Thymeleaf。
     * standaloneSetup 默认装 InternalResourceViewResolver，GET /trace 返回 "trace"
     * 时它会把请求再 forward 回 /trace，报 "Circular view path" —— 那是测试脚手架
     * 没配视图解析器，不是控制器错了。
     */
    private static final ViewResolver NO_RENDER = (viewName, locale) -> new AbstractView() {
        @Override
        protected void renderMergedOutputModel(Map<String, Object> model,
                                              HttpServletRequest request,
                                              HttpServletResponse response) {
            // 空渲染
        }
    };

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new MiniAgentChatPageController())
                .setViewResolvers(NO_RENDER)
                .build();
    }

    /** 回归用例：不带 Bearer 的整页导航必须拿到 trace 骨架，不是 login。 */
    @Test
    void tracePageIsServedWithoutBearerOnNavigation() throws Exception {
        mvc().perform(get("/trace"))
                .andExpect(view().name("trace"));

        // 带 Bearer 也不能改变结果：这条路径压根不该读身份。
        mvc().perform(get("/trace").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(view().name("trace"));
    }

    /** 四个页面骨架一致，任何一个都不能在导航阶段被 Bearer 卡住。 */
    @Test
    void everyPageSkeletonIsServedWithoutBearer() throws Exception {
        mvc().perform(get("/")).andExpect(view().name("chat"));
        mvc().perform(get("/login")).andExpect(view().name("login"));
        mvc().perform(get("/membership")).andExpect(view().name("membership"));
        mvc().perform(get("/trace")).andExpect(view().name("trace"));
    }

    /**
     * 放行骨架 != 放行数据。
     *
     * <p>若有人为了"让轨迹页能用"顺手把数据接口也加进 PUBLIC_PATHS，
     * 任何人访问本机端口就能读走全部会话与执行记录 —— 这条断言防止那种修法。
     */
    @Test
    void pageRoutesArePublicButTraceDataApisAreNot() {
        List<String> publicPaths = Arrays.asList(SecurityConfig.PUBLIC_PATHS);
        assertTrue(publicPaths.contains("/trace"));
        assertTrue(publicPaths.contains("/membership"));
        assertTrue(publicPaths.contains("/js/**"), "security.js 本身必须能匿名加载，否则页面没法注入 Bearer");

        assertFalse(publicPaths.contains("/api/conversations"));
        assertFalse(publicPaths.contains("/api/traces"));
        assertFalse(publicPaths.contains("/api/executions"));
        assertFalse(publicPaths.contains("/api/conversations/{sessionId}/traces"));
    }
}
