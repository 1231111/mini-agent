import com.miniagent.agent.browser.BrowserService;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * BrowserService 的「按包类型判定安装完整性」回归测试（2026-09-28）。
 *
 * <p>背景：Playwright 1.49 起 headless=true 用的是 chromium_headless_shell-&lt;rev&gt;，
 * 而出厂构建脚本用 --only-shell 跳过了完整的 chromium-&lt;rev&gt;。
 * 修复前的判定函数写死了完整包的布局（chrome-win/chrome.exe + chrome.dll），
 * 对 headless shell 目录必然返回 false —— 那会把「浏览器明明在位」误判成「未安装」。
 *
 * <p>这里直接反射调用真实的私有静态方法，不复制逻辑，所以能真正守住这次修复。
 */
public final class BrowserDirValidTest {

    /** 随包 browsers 根目录：优先读系统属性，其次读环境变量。 */
    private static Path browsersRoot() {
        String p = System.getProperty("playwright.browsers.path");
        if (p == null || p.isBlank()) p = System.getenv("PLAYWRIGHT_BROWSERS_PATH");
        if (p == null || p.isBlank()) throw new IllegalStateException("未指定 playwright.browsers.path");
        return Paths.get(p);
    }

    /** 在根目录下找第一个以 prefix 开头的子目录（版本号会变，不能写死）。 */
    private static Path findDir(Path root, String prefix) throws Exception {
        try (var s = Files.list(root)) {
            return s.filter(Files::isDirectory)
                    .filter(d -> d.getFileName().toString().startsWith(prefix))
                    .findFirst()
                    .orElse(null);
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = browsersRoot();
        Method check = BrowserService.class.getDeclaredMethod("isBrowserDirValid", Path.class, boolean.class);
        check.setAccessible(true);

        System.out.println("browsers 根目录: " + root);
        System.out.println();

        int failed = 0;

        // ── 用例 1：headless shell 目录，按 headlessShell=true 判定 → 必须 true
        //    这正是修复前会错的那条：旧判定去找 chrome-win/chrome.exe，找不到就判残缺。
        Path shell = findDir(root, "chromium_headless_shell-");
        if (shell == null) {
            System.out.println("[SKIP] 未找到 chromium_headless_shell-* 目录（当前构建没随包分发它？）");
        } else {
            boolean got = (boolean) check.invoke(null, shell, true);
            failed += expect("headless shell 目录 + headlessShell=true → 可用", true, got);
        }

        // ── 用例 2：同一个 headless shell 目录，按 headlessShell=false 判定 → 必须 false
        //    证明「两类包不能共用判定」这句话是成立的，不是嘴上说说。
        if (shell != null) {
            boolean got = (boolean) check.invoke(null, shell, false);
            failed += expect("headless shell 目录 + headlessShell=false → 不可用（布局不匹配）", false, got);
        }

        // ── 用例 3：完整 chromium 目录（若随包分发）→ headlessShell=false 必须 true
        Path full = findDir(root, "chromium-");
        if (full == null) {
            System.out.println("[SKIP] 未找到 chromium-* 完整包目录（--only-shell 构建下属正常）");
        } else {
            boolean got = (boolean) check.invoke(null, full, false);
            failed += expect("完整 Chromium 目录 + headlessShell=false → 可用", true, got);
        }

        // ── 用例 4：目录前缀互不误匹配（Playwright 生成目录名用下划线）
        //    registry/index.js:371 是 name.replace(/-/g,'_') + '-' + revision
        String shellName = shell == null ? "chromium_headless_shell-1148" : shell.getFileName().toString();
        failed += expect("chromium_headless_shell-* 不匹配前缀 \"chromium-\"",
                false, shellName.startsWith("chromium-"));
        failed += expect("chromium_headless_shell-* 匹配前缀 \"chromium_headless_shell-\"",
                true, shellName.startsWith("chromium_headless_shell-"));

        System.out.println();
        if (failed == 0) {
            System.out.println("全部通过");
        } else {
            System.out.println("失败 " + failed + " 项");
        }
        System.exit(failed == 0 ? 0 : 1);
    }

    private static int expect(String what, boolean want, boolean got) {
        boolean ok = want == got;
        System.out.printf("[%s] %s%n        期望=%s 实际=%s%n", ok ? "PASS" : "FAIL", what, want, got);
        return ok ? 0 : 1;
    }
}
