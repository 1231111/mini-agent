package com.miniagent.agent.browser;

import com.microsoft.playwright.*;
import com.microsoft.playwright.Locator.AriaSnapshotOptions;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitUntilState;
import com.miniagent.agent.security.NetworkGuard;
import com.miniagent.common.MessageConstants;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

/**
 * 浏览器服务：用 Playwright 驱动无头 Chromium，提供页面快照和交互能力。
 *
 * 核心思路（对标 hermes-agent 的 agent-browser）：
 * - 不返回 HTML，而是返回 accessibility tree（aria snapshot）
 * - 每个 task 有独立的浏览器 session
 * - LLM 通过 ref ID（如 @1, @2）定位元素
 */
@Slf4j
@Service
public class BrowserService {

    /** 与 mini-agent-app/pom.xml 中 playwright 依赖版本一致，用于在 classpath 未展开时定位 jar */
    private static final String PLAYWRIGHT_MAVEN_VERSION = "1.49.0";

    private Playwright playwright;
    private Browser browser;
    private final Map<String, Page> pages = new ConcurrentHashMap<>();

    @Autowired
    private NetworkGuard networkGuard;

    /**
     * 确保浏览器实例已启动（懒初始化）
     * 首次启动时自动下载 Chromium（约 150MB）
     */
    private synchronized void ensureBrowser() {
        if (Objects.isNull(playwright)) {
            // 检测 Chromium 是否已下载，没有就自动安装
            ensureChromiumInstalled();
            playwright = Playwright.create();
        }
        if (Objects.isNull(browser)) {
            browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions()
                            .setHeadless(false)    // 改为有头模式，可见浏览器窗口
                            .setSlowMo(200));       // 放慢操作，方便观察
            log.info("浏览器已启动 (有头 Chromium)");
        }
    }

    /**
     * 自动检测并安装 Chromium
     * 调用 Playwright CLI 的 install 功能，等效于 mvn exec:java -Dexec.args="install chromium"
     */
    private void ensureChromiumInstalled() {
        Path browsersRoot = resolveBrowsersRoot();

        // 检查是否有 chromium 目录（版本号因 Playwright 版本而异）
        // 注意：仅判断目录存在不够——残缺安装（chrome.exe 在但 chrome.dll 缺失）会导致
        // 启动时报 "Failed to load Chrome DLL ... 0x7E"。必须校验关键文件完整。
        boolean installed = false;
        if (Files.exists(browsersRoot)) {
            try (var stream = Files.list(browsersRoot)) {
                installed = stream.anyMatch(p -> {
                    String n = p.getFileName().toString();
                    return n.startsWith("chromium-") && isChromiumDirValid(p);
                });
            } catch (IOException e) {
                // ignore
            }
        }

        if (installed) {
            log.debug("Chromium 已安装且完整（目录 {}）", browsersRoot);
            return;
        }

        log.info("Chromium 未安装或安装残缺（缺 chrome.dll 等核心文件），开始重新下载（约 150MB，请耐心等待）...");

        tryInstallViaCommand();
    }

    /**
     * 校验一个 chromium-* 目录是否是完整可用的安装。
     * 残缺安装的典型特征：chrome.exe（启动壳，约 2-3MB）在，但 chrome.dll（主程序模块，
     * 正常 100MB+）缺失或过小，启动时报 "Failed to load Chrome DLL ... 0x7E"。
     * 这里只校验 Windows 的关键文件；非 Windows 平台保持宽松（只要目录在即认为有效，
     * 交给 Playwright 自身处理）。
     */
    private static boolean isChromiumDirValid(Path chromiumDir) {
        if (!isWindows()) {
            return true; // 非 Windows：可执行文件命名/结构不同，不在此校验
        }
        Path chromeWin = chromiumDir.resolve("chrome-win");
        Path exe = chromeWin.resolve("chrome.exe");
        Path dll = chromeWin.resolve("chrome.dll");
        try {
            if (!Files.isRegularFile(exe) || !Files.isRegularFile(dll)) {
                return false;
            }
            // chrome.dll 正常上百 MB，残缺/占位文件远小于此。用 10MB 作为保守下限。
            return Files.size(dll) > 10_000_000L;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Playwright 默认浏览器根目录：Windows 为 {@code %LOCALAPPDATA%\ms-playwright}，Linux/mac 常为 {@code ~/.cache/ms-playwright}。
     */
    private static Path resolveBrowsersRoot() {
        String override = System.getProperty("playwright.browsers.path");
        if (StringUtils.isNotBlank(override)) {
            return Paths.get(override);
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            if (StringUtils.isNotBlank(local)) {
                return Paths.get(local, "ms-playwright");
            }
        }
        return Paths.get(System.getProperty("user.home"), ".cache", "ms-playwright");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 自动安装 Chromium：优先用当前 JDK 执行 {@code com.microsoft.playwright.CLI}（不要用 {@code java -jar}，官方 jar 无 Runnable 清单）。
     */
    private void tryInstallViaCommand() {
        if (tryInstallViaClasspathCli()) {
            return;
        }

        if (commandOnPath("npx") && tryInstallViaNpx()) {
            return;
        }

        if (commandOnPath("mvn") && tryInstallViaMaven()) {
            return;
        }

        log.error("""
                ═══════════════════════════════════════════
                Chromium 自动安装失败。请在本机手动执行（二选一）：

                  1) 使用当前 JDK（推荐，注意 -cp 不要写成 -jar）：
                     "%JAVA_HOME%\\bin\\java.exe" -cp "<playwright.jar路径>" com.microsoft.playwright.CLI install chromium

                  2) 已安装 Node 时：npx playwright install chromium

                下载慢或超时时可设置环境变量 PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT=600000（毫秒）后重试。
                安装完成后重启应用。
                ═══════════════════════════════════════════
                """);
    }

    private static boolean commandOnPath(String name) {
        try {
            ProcessBuilder pb = isWindows()
                    ? new ProcessBuilder("where", name)
                    : new ProcessBuilder("which", name);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean done = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            return done && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 使用 java -cp playwright.jar com.microsoft.playwright.CLI install chromium */
    private boolean tryInstallViaClasspathCli() {
        try {
            String playwrightJar = resolvePlaywrightJarPath();
            if (Objects.isNull(playwrightJar)) {
                log.debug("未解析到 playwright jar 路径");
                return false;
            }

            String javaExe = resolveJavaExecutable();
            log.info("找到 playwright jar: {}", playwrightJar);
            log.info("正在通过 CLI 安装 Chromium（约 150MB，弱网请耐心等待或增大 PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT）...");

            List<String> cmd = new ArrayList<>();
            cmd.add(javaExe);
            cmd.add("-cp");
            cmd.add(playwrightJar);
            cmd.add("com.microsoft.playwright.CLI");
            cmd.add("install");
            cmd.add("chromium");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            if (Objects.isNull(env.get("PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT"))) {
                env.put("PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT", "600000");
            }

            Process p = pb.start();

            try (var in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                int len;
                while ((len = in.read(buf)) != -1) {
                    String chunk = new String(buf, 0, len, StandardCharsets.UTF_8);
                    for (String line : chunk.split("\\R")) {
                        line = line.trim();
                        if (!StringUtils.isBlank(line)) {
                            log.info("  playwright: {}", line);
                        }
                    }
                }
            }

            boolean done = p.waitFor(600, java.util.concurrent.TimeUnit.SECONDS);
            if (done && p.exitValue() == 0) {
                log.info("Chromium 安装成功！");
                return true;
            }
            log.error("Chromium 安装失败，退出码: {}", done ? p.exitValue() : "超时");
            return false;
        } catch (Exception e) {
            log.debug("CLI classpath 方式安装失败", e);
        }
        return false;
    }

    private static String resolveJavaExecutable() {
        Path home = Paths.get(System.getProperty("java.home", ""));
        Path bin = home.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
        if (Files.isRegularFile(bin)) {
            return bin.toAbsolutePath().toString();
        }
        return "java";
    }

    private static String resolvePlaywrightJarPath() {
        String classpath = System.getProperty("java.class.path");
        if (Objects.nonNull(classpath)) {
            for (String entry : classpath.split(File.pathSeparator)) {
                if (entry.contains("playwright") && entry.endsWith(".jar") && Files.isRegularFile(Paths.get(entry))) {
                    return entry;
                }
            }
        }

        String ver = PLAYWRIGHT_MAVEN_VERSION;
        String jarName = "playwright-" + ver + ".jar";
        List<String> fallbacks = new ArrayList<>();
        String m2Local = System.getProperty("maven.repo.local");
        if (StringUtils.isNotBlank(m2Local)) {
            fallbacks.add(Paths.get(m2Local, "com", "microsoft", "playwright", "playwright", ver, jarName).toString());
        }
        fallbacks.add(Paths.get(System.getProperty("user.home"), ".m2", "repository", "com", "microsoft", "playwright", "playwright", ver, jarName).toString());

        for (String fp : fallbacks) {
            if (Files.isRegularFile(Paths.get(fp))) {
                return fp;
            }
        }
        return null;
    }

    /** 方式2：npx playwright install chromium */
    private boolean tryInstallViaNpx() {
        try {
            log.info("通过 npx 安装 Chromium...");
            ProcessBuilder pb = new ProcessBuilder("npx", "playwright", "install", "chromium");
            pb.redirectErrorStream(true);
            Process p = pb.start();

            Map<String, String> env = pb.environment();
            if (Objects.isNull(env.get("PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT"))) {
                env.put("PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT", "600000");
            }

            try (var in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                int len;
                while ((len = in.read(buf)) != -1) {
                    String chunk = new String(buf, 0, len, StandardCharsets.UTF_8);
                    if (chunk.contains("%") || chunk.contains("Downloading") || chunk.contains("installed")) {
                        for (String line : chunk.split("\\R")) {
                            if (!StringUtils.isBlank(line)) {
                                log.info("  {}", line.trim());
                            }
                        }
                    }
                }
            }

            boolean done = p.waitFor(600, java.util.concurrent.TimeUnit.SECONDS);
            if (done && p.exitValue() == 0) {
                log.info("Chromium 安装成功 (npx)");
                return true;
            }
        } catch (Exception e) {
            log.debug("npx 方式安装失败", e);
        }
        return false;
    }

    /** 方式3：mvn exec:java（需本机 PATH 中有 mvn） */
    private boolean tryInstallViaMaven() {
        try {
            log.info("通过 mvn 安装 Chromium...");
            ProcessBuilder pb = new ProcessBuilder("mvn", "-q", "exec:java",
                    "-Dexec.mainClass=com.microsoft.playwright.CLI",
                    "-Dexec.classpathScope=compile",
                    "-Dexec.args=install chromium");
            pb.redirectErrorStream(true);
            pb.directory(new java.io.File(System.getProperty("user.dir")));
            Map<String, String> env = pb.environment();
            if (Objects.isNull(env.get("PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT"))) {
                env.put("PLAYWRIGHT_DOWNLOAD_CONNECTION_TIMEOUT", "600000");
            }
            Process p = pb.start();

            try (var in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                while (in.read(buf) != -1) { /* 读完防阻塞 */ }
            }

            boolean done = p.waitFor(600, java.util.concurrent.TimeUnit.SECONDS);
            if (done && p.exitValue() == 0) {
                log.info("Chromium 安装成功 (mvn)");
                return true;
            }
        } catch (Exception e) {
            log.debug("mvn 方式安装失败", e);
        }
        return false;
    }

    /**
     * 获取或创建一个 page
     */
    private Page getPage(String sessionId) {
        // 空字符串/null → 用 "default" session
        if (StringUtils.isBlank(sessionId)) {
            sessionId = "default";
        }
        return pages.computeIfAbsent(sessionId, id -> {
            ensureBrowser();
            Page page = browser.newPage();
            log.info("创建新浏览器页面: {}", id);
            return page;
        });
    }

    // ==================== 工具方法 ====================

    /**
     * 打开网页
     */
    public String navigate(String sessionId, String url) {
        try {
            String blocked = networkGuard.validateUrl(url);
            if (Objects.nonNull(blocked)) return blocked;
            Page page = getPage(sessionId);
            page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(15000));

            String title = page.title();
            String snapshot = page.locator("body").ariaSnapshot();
            log.info("导航到: {} 标题: {}", url, title);

            return "页面标题: " + title + "\n\n" + formatSnapshot(snapshot);

        } catch (Exception e) {
            log.error("导航失败: {}", url, e);
            return String.format(MessageConstants.BROWSER_NAV_FAILED, e.getMessage());
        }
    }

    /**
     * 获取页面无障碍树快照
     */
    public String snapshot(String sessionId, boolean full) {
        try {
            Page page = getPage(sessionId);
            String snapshot;
            // Playwright 1.49：AriaSnapshotOptions 仅有 setTimeout，无 setDepth/setMode（高版本才有）。
            // full=true 时用更长超时，避免大型页面无障碍树生成超时。
            if (full) {
                snapshot = page.locator("body").ariaSnapshot(
                        new AriaSnapshotOptions().setTimeout(120_000));
            } else {
                snapshot = page.locator("body").ariaSnapshot();
            }
            return formatSnapshot(snapshot);

        } catch (Exception e) {
            log.error("获取快照失败", e);
            return String.format(MessageConstants.BROWSER_SNAPSHOT_FAILED, e.getMessage());
        }
    }

    /**
     * 点击。by：auto|ref|text|role|css|aria。数字 ref 只走快照，失败立即返回（不把编号当 aria-label）。
     */
    public String click(String sessionId, String ref) {
        return click(sessionId, ref, "auto");
    }

    public String click(String sessionId, String ref, String by) {
        if (StringUtils.isBlank(ref)) return MessageConstants.BROWSER_CLICK_FAILED_EMPTY_REF;
        String mode = StringUtils.isBlank(by) ? "auto" : by.trim().toLowerCase(Locale.ROOT);
        try {
            Page page = getPage(sessionId);
            boolean numeric = ref.chars().allMatch(Character::isDigit);
            if ("ref".equals(mode) || ("auto".equals(mode) && numeric)) {
                return clickBySnapshotRef(page, ref);
            }
            Locator loc = switch (mode) {
                case "text" -> page.getByText(ref, new Page.GetByTextOptions().setExact(true));
                case "role" -> roleLocator(page, ref);
                case "css" -> page.locator(ref).first();
                case "aria" -> page.locator("[aria-label*='" + ref + "'], [placeholder*='" + ref + "']").first();
                case "auto" -> page.getByText(ref, new Page.GetByTextOptions().setExact(true));
                default -> null;
            };
            if (Objects.isNull(loc)) {
                return String.format(MessageConstants.BROWSER_CLICK_FAILED_UNKNOWN_BY, mode);
            }
            return clickAndSnap(page, loc, mode, ref);
        } catch (Exception e) {
            log.error("点击失败: ref={} by={}", ref, mode, e);
            return String.format(MessageConstants.BROWSER_CLICK_FAILED, ref, mode, e.getMessage());
        }
    }

    private String clickBySnapshotRef(Page page, String ref) {
        final int refNum;
        try {
            refNum = Integer.parseInt(ref);
        } catch (NumberFormatException e) {
            return String.format(MessageConstants.BROWSER_CLICK_FAILED_NOT_DIGIT, ref);
        }
        String elementInfo = findElementByRef(page, refNum);
        if (Objects.isNull(elementInfo)) return String.format(MessageConstants.BROWSER_CLICK_FAILED_NO_REF, ref);
        log.info("click ref={} 对应元素: {}", ref, elementInfo);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"]+)\"").matcher(elementInfo);
        String name = m.find() ? m.group(1) : null;
        if (StringUtils.isBlank(name)) {
            return String.format(MessageConstants.BROWSER_CLICK_FAILED_NO_NAME, ref, elementInfo);
        }
        // 按快照角色选唯一策略；失败直接报错，不静默串联
        Locator loc = elementInfo.contains("button")
                ? page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(name))
                : elementInfo.contains("link")
                ? page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(name))
                : page.getByText(name);
        try {
            return clickAndSnap(page, loc, "ref", name);
        } catch (Exception e) {
            return String.format(MessageConstants.BROWSER_CLICK_FAILED_BY_NAME, ref, name, e.getMessage());
        }
    }

    private Locator roleLocator(Page page, String ref) {
        AriaRole role = AriaRole.BUTTON;
        String roleName = ref;
        int eq = ref.indexOf('=');
        if (eq > 0) {
            role = parseAriaRole(ref.substring(0, eq).trim());
            roleName = ref.substring(eq + 1).trim();
        }
        return page.getByRole(role, new Page.GetByRoleOptions().setName(roleName));
    }

    private String clickAndSnap(Page page, Locator locator, String strategy, String label) {
        locator.click(new Locator.ClickOptions().setTimeout(5000));
        page.waitForTimeout(1500);
        return String.format(MessageConstants.BROWSER_CLICK_SUCCESS, strategy, label) + "\n\n"
                + formatSnapshot(page.locator("body").ariaSnapshot());
    }

    private static AriaRole parseAriaRole(String raw) {
        try {
            return AriaRole.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (Exception e) {
            return AriaRole.BUTTON;
        }
    }

    /**
     * 在搜索框/输入框中输入文字
     * ref: 快照中的编号（如 "11"）或 placeholder 文本（如 "搜索"）
     * text: 要输入的文字
     */
    public String type(String sessionId, String ref, String text) {
        try {
            Page page = getPage(sessionId);
            int shortTimeout = 3000; // 每个策略最多等3秒，失败立即跳下一个

            // 策略1: ref 是数字 → 在快照中找到该编号对应的元素，用 aria label/name 匹配
            try {
                int refNum = Integer.parseInt(ref);
                String elementInfo = findElementByRef(page, refNum);
                if (Objects.nonNull(elementInfo)) {
                    log.info("ref={} 对应元素: {}", ref, elementInfo);
                    // 尝试用 placeholder / label 找输入框
                    Locator input = findInputByInfo(page, elementInfo);
                    if (Objects.nonNull(input)) {
                        input.fill(text, new Locator.FillOptions().setTimeout(shortTimeout));
                        return String.format(MessageConstants.BROWSER_INPUT_SUCCESS, ref, text) + "\n\n"
                                + formatSnapshot(page.locator("body").ariaSnapshot());
                    }
                }
            } catch (Exception ignored) {}

            // 策略2: ref 是文本 → 用 placeholder 匹配
            try {
                page.getByPlaceholder(ref).fill(text, new Locator.FillOptions().setTimeout(shortTimeout));
                return String.format(MessageConstants.BROWSER_INPUT_SUCCESS_PLACEHOLDER, ref, text) + "\n\n"
                        + formatSnapshot(page.locator("body").ariaSnapshot());
            } catch (Exception ignored) {}

            // 策略3: ref 是文本 → 用 aria label 匹配
            try {
                page.getByLabel(ref).fill(text, new Locator.FillOptions().setTimeout(shortTimeout));
                return String.format(MessageConstants.BROWSER_INPUT_SUCCESS_LABEL, ref, text) + "\n\n"
                        + formatSnapshot(page.locator("body").ariaSnapshot());
            } catch (Exception ignored) {}

            return String.format(MessageConstants.BROWSER_INPUT_FAILED_NOT_FOUND, ref);

        } catch (Exception e) {
            log.error("输入失败: ref={}", ref, e);
            return String.format(MessageConstants.BROWSER_INPUT_FAILED, e.getMessage());
        }
    }

    /**
     * 从无障碍树快照中找到 ref 编号对应的元素描述
     */
    private String findElementByRef(Page page, int refNum) {
        String raw = page.locator("body").ariaSnapshot();
        int currentRef = 0;
        for (String line : raw.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.contains("link") || trimmed.contains("button")
                    || trimmed.contains("textbox") || trimmed.contains("searchbox")
                    || trimmed.contains("checkbox") || trimmed.contains("combobox")
                    || trimmed.contains("radio") || trimmed.contains("heading")
                    || trimmed.contains("text")) {
                currentRef++;
                if (currentRef == refNum) {
                    return trimmed;
                }
            }
        }
        return null;
    }

    /**
     * 根据元素描述尝试找到输入框
     */
    private Locator findInputByInfo(Page page, String elementInfo) {
        // 提取引号中的文本（如 "搜索"）
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"]+)\"").matcher(elementInfo);
        String label = m.find() ? m.group(1) : null;

        if (elementInfo.contains("searchbox") || elementInfo.contains("textbox")
                || (Objects.nonNull(label) && (label.contains("搜索") || label.contains("search")))) {
            // 搜索框 / 输入框
            Locator byPlaceholder = page.locator("input[placeholder]").first();
            if (byPlaceholder.count() > 0) return byPlaceholder;
        }

        if (Objects.nonNull(label)) {
            try {
                return page.getByPlaceholder(label);
            } catch (Exception ignored) {}
            try {
                return page.getByLabel(label);
            } catch (Exception ignored) {}
        }

        // fallback: 第一个可见的 input
        Locator firstInput = page.locator("input:visible, textarea:visible").first();
        return firstInput.count() > 0 ? firstInput : null;
    }

    /**
     * 按键盘按键
     */
    public String press(String sessionId, String key) {
        try {
            Page page = getPage(sessionId);
            page.keyboard().press(key);
            return String.format(MessageConstants.BROWSER_PRESS_SUCCESS, key);
        } catch (Exception e) {
            log.error("按键失败: key={}", key, e);
            return String.format(MessageConstants.BROWSER_PRESS_FAILED, e.getMessage());
        }
    }

    /**
     * 滚动页面
     */
    public String scroll(String sessionId, String direction) {
        try {
            Page page = getPage(sessionId);
            if ("up".equalsIgnoreCase(direction)) {
                page.mouse().wheel(0, -500);
            } else {
                page.mouse().wheel(0, 500);
            }
            return String.format(MessageConstants.BROWSER_SCROLL_SUCCESS, direction);
        } catch (Exception e) {
            return String.format(MessageConstants.BROWSER_SCROLL_FAILED, e.getMessage());
        }
    }

    /**
     * 截图
     */
    public String screenshot(String sessionId) {
        try {
            Page page = getPage(sessionId);
            String path = "screenshot_" + sessionId + ".png";
            page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Paths.get(path))
                    .setFullPage(false));
            return String.format(MessageConstants.BROWSER_SCREENSHOT_SAVED, path);
        } catch (Exception e) {
            return String.format(MessageConstants.BROWSER_SCREENSHOT_FAILED, e.getMessage());
        }
    }

    /**
     * 关闭当前 session 的浏览器页面
     */
    public String close(String sessionId) {
        Page page = pages.remove(sessionId);
        if (Objects.nonNull(page)) {
            page.close();
            return String.format(MessageConstants.BROWSER_PAGE_CLOSED, sessionId);
        }
        return "没有找到 session: " + sessionId;
    }

    /**
     * 在页面执行 JavaScript
     */
    public String evaluate(String sessionId, String expression) {
        try {
            Page page = getPage(sessionId);
            Object result = page.evaluate(expression);
            return String.format(MessageConstants.BROWSER_EVAL_RESULT, Objects.nonNull(result) ? result.toString() : "null");
        } catch (Exception e) {
            return String.format(MessageConstants.BROWSER_EVAL_FAILED, e.getMessage());
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 给无障碍树快照的每一行加编号 ref
     * 原始:  - link "搜索"
     * 处理后: [1] - link "搜索"
     */
    private String formatSnapshot(String snapshot) {
        if (StringUtils.isBlank(snapshot)) {
            return MessageConstants.BROWSER_EMPTY_PAGE;
        }

        StringBuilder sb = new StringBuilder();
        String[] lines = snapshot.split("\n");
        int refNum = 1;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            // 只给有交互元素的行加 ref（link/button/input/textbox 等）
            if (trimmed.contains("link") || trimmed.contains("button")
                    || trimmed.contains("textbox") || trimmed.contains("checkbox")
                    || trimmed.contains("combobox") || trimmed.contains("radio")
                    || trimmed.contains("heading") || trimmed.contains("text")) {
                sb.append(String.format("[%d] %s%n", refNum++, trimmed));
            } else {
                sb.append(line).append("\n");
            }
        }

        return sb.toString();
    }

    @PreDestroy
    public void shutdown() {
        log.info("关闭所有浏览器会话...");
        pages.values().forEach(Page::close);
        pages.clear();
        if (Objects.nonNull(browser)) browser.close();
        if (Objects.nonNull(playwright)) playwright.close();
    }
}
