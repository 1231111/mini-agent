package com.miniagent.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Shell 的页面骨架路由。
 *
 * <p><b>这里无条件返回视图，绝不做 Bearer 鉴权。</b>浏览器导航请求带不了 Authorization 头，
 * 服务端在页面路由上拿不到任何身份信息 —— 这正是 {@code /trace} 的既有教训：若在页面路由里
 * 拦 Bearer，用户直接连页面都打不开，而页面本身又不泄露任何用户数据。真正的边界在数据接口：
 * {@link ShellApiController} 里每个 {@code /api/shell/**} 都逐条 {@code resolveUserId}
 * + 会话归属校验，由前端读 sessionStorage、显式带上 Bearer 后再请求。
 *
 * <p>因此这三个路径必须放进 {@code SecurityConfig.PUBLIC_PATHS}，与 {@code /}、
 * {@code /login}、{@code /trace}、{@code /membership} 同等对待。
 */
@Controller
public class ShellPageController {

    @GetMapping("/memory")
    public String memory() {
        return "memory";
    }

    @GetMapping("/knowledge")
    public String knowledge() {
        return "knowledge";
    }

    @GetMapping("/apps")
    public String apps() {
        return "apps";
    }
}
