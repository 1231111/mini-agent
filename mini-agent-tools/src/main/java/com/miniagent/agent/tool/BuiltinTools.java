package com.miniagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.browser.BrowserService;
import com.miniagent.agent.comfyui.ComfyUIService;
import com.miniagent.agent.comfyui.ImageQualityChecker;
import com.miniagent.memory.MemoryStore;
import com.miniagent.agent.skill.SkillStore;
import com.miniagent.agent.security.NetworkGuard;
import com.miniagent.agent.web.WebSearchService;
import com.miniagent.agent.web.ImageGenerationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.lang3.StringUtils;

/**
 * 内置工具集合：文件操作、HTTP 请求、命令执行、浏览器操控
 *
 * 类似 hermes-agent 的工具系统，每个工具由 name + description + parameters + handler 组成。
 */
@Slf4j
@Component
public class BuiltinTools {

    @Autowired
    private  ToolRegistry registry;
    @Autowired
    private  BrowserService browserService;
    @Autowired
    private  WebSearchService webSearchService;
    @Autowired
    private  MemoryStore memoryStore;
    @Autowired
    private  SkillStore skillStore;
    @Autowired
    private  ComfyUIService comfyuiService;
    @Autowired
    private  ImageQualityChecker qualityChecker;
    @Autowired
    private  ImageGenerationService imageGenerationService;
    @Autowired
    private  NetworkGuard networkGuard;

    @Value("${agent.tools.exec-enabled:false}")
    private boolean execEnabled;

    @Value("${agent.tools.allow-absolute-write:false}")
    private boolean allowAbsoluteWrite;

    /**
     * 工具结果缓存：同一用户消息内，对 read_file / list_files 的重复调用直接返回缓存。
     * Key = "toolName|argumentsJson"，Value = 上次执行结果。
     * 在每次新用户消息处理开始时由 AgentChatApplicationService 调用 clearToolCache() 清空。
     */
    private final ConcurrentHashMap<String, String> toolResultCache =
            new ConcurrentHashMap<>();

    /** 复用连接池，避免每次 httpGet 新建客户端 */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 清空工具结果缓存（每条新用户消息开始时调用） */
    public void clearToolCache() {
        toolResultCache.clear();
    }

    /**
     * 失效与某文件路径相关的 read_file 缓存。
     * write_file / edit_file 改动文件后调用，避免后续 read_file 读到改动前的旧内容
     * （read_file 缓存 key 形如 "read_file|{...\"path\":\"xxx\"...}"，按文件名子串匹配失效）。
     */
    private void invalidateReadCache(String path) {
        if (StringUtils.isBlank(path)) {
            toolResultCache.clear();
            return;
        }
        String norm = path.replace('\\', '/').trim();
        // 文件名（最后一段）作为兜底匹配，覆盖相对/绝对路径的不同写法
        String fileName = norm.contains("/") ? norm.substring(norm.lastIndexOf('/') + 1) : norm;
        toolResultCache.keySet().removeIf(k -> {
            String kn = k.replace('\\', '/');
            return kn.contains(norm) || (!StringUtils.isBlank(fileName) && kn.contains(fileName));
        });
    }


    @PostConstruct
    public void register() {
        registerFileTools();
        registerSearchAndEditTools();
        registerHttpTools();
        registerWebSearchTools();
        registerExecTool();
        registerBrowserTools();
        registerMemoryTool();
        registerSkillTools();
        registerImageGenerateTool();
        registerComfyUITools();
    }


    // ==================== 文件工具 ====================

    private void registerFileTools() {
        registry.register("read_file", "读取文件内容",
                Map.of(
                        "path", Map.of("type", "string", "description", "文件路径", "required", true),
                        "offset", Map.of("type", "integer", "description", "起始行号（默认1）"),
                        "limit", Map.of("type", "integer", "description", "最大行数（默认200）")
                ),
                args -> {
                    // 缓存命中检查
                    String cacheKey = "read_file|" + args;
                    String cached = toolResultCache.get(cacheKey);
                    if (Objects.nonNull(cached)) {
                        log.debug("read_file 缓存命中: {}", cacheKey);
                        return cached;
                    }
                    Map<String, Object> p = parseJson(args);
                    String path = (String) p.get("path");
                    int offset = p.containsKey("offset") ? ((Number) p.get("offset")).intValue() : 1;
                    int limit = p.containsKey("limit") ? ((Number) p.get("limit")).intValue() : 200;
                    String result = readFile(path, offset, limit);
                    toolResultCache.put(cacheKey, result);
                    return result;
                });

        registry.register("write_file", "写入文件内容。文件自动保存到 workspace/任务名/ 目录下，路径只需写文件名如 design.md 即可。"
                        + "【大文件必读】单轮输出有长度上限，超长文件（如完整的 3D 仿真 HTML、长代码）一次写不完会被截断。"
                        + "正确做法：第一次用 mode=\"overwrite\"（默认）写开头部分，之后多次用 mode=\"append\" 把剩余内容续写到同一文件，直到写完。不要试图一次塞进全部内容。",
                Map.of(
                        "path", Map.of("type", "string", "description", "文件路径", "required", true),
                        "content", Map.of("type", "string", "description", "文件内容（本次要写入/追加的片段）", "required", true),
                        "mode", Map.of("type", "string", "description", "写入模式：overwrite=覆盖（默认，用于首次写入）；append=追加到文件末尾（用于分块续写大文件）")
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    if (Boolean.TRUE.equals(p.get(PARSE_ERROR_KEY))) {
                        return "{\"error\":\"参数 JSON 不完整，疑似本次输出被长度上限截断。请改用分块写入：先 mode=overwrite 写前半部分，再多次 mode=append 续写剩余内容，每次内容不要太长。\"}";
                    }
                    String content = (String) p.get("content");
                    if (Objects.isNull(content)) {
                        return "{\"error\":\"缺少 content 参数（或参数被截断）。大文件请分块写入：首次 mode=overwrite，后续 mode=append。\"}";
                    }
                    boolean append = "append".equalsIgnoreCase(String.valueOf(p.get("mode")));
                    return writeFile((String) p.get("path"), content, append);
                });

        registry.register("list_files", "列出目录文件",
                Map.of(
                        "path", Map.of("type", "string", "description", "目录路径", "required", true),
                        "recursive", Map.of("type", "boolean", "description", "是否递归列出")
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    String path = (String) p.get("path");
                    boolean recursive = Boolean.TRUE.equals(p.get("recursive"));
                    return listFiles(path, recursive);
                });

        registry.register("read_package", "按Java包名读取包下所有源码文件。传包名如 com.miniagent.agent.intent，自动解析路径并返回全部代码。分析项目架构时优先用这个，不要逐个 read_file。",
                Map.of(
                        "package_name", Map.of("type", "string", "description", "Java包名，如 com.miniagent.agent.intent", "required", true),
                        "max_chars_per_file", Map.of("type", "integer", "description", "每个文件最大字符数（默认8000）")
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    String packageName = (String) p.get("package_name");
                    int maxChars = p.containsKey("max_chars_per_file") ? ((Number) p.get("max_chars_per_file")).intValue() : 8000;
                    return readPackage(packageName, maxChars);
                });
    }

    // ==================== HTTP 工具 ====================

    private void registerHttpTools() {
        registry.register("http_get", "发送 HTTP GET 请求并返回响应体",
                Map.of(
                        "url", Map.of("type", "string", "description", "目标 URL", "required", true)
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    return httpGet((String) p.get("url"));
                });
    }

    // ==================== Web 搜索工具（对标 hermes-agent） ====================

    private void registerWebSearchTools() {
        registry.register("web_search", "搜索网页，返回结构化的搜索结果（标题、URL、描述）。",
                Map.of(
                        "query", Map.of("type", "string", "description", "搜索关键词", "required", true),
                        "limit", Map.of("type", "integer", "description", "返回结果数量（默认5，最大10）")
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    String query = (String) p.get("query");
                    int limit = p.containsKey("limit") ? Math.min(((Number) p.get("limit")).intValue(), 10) : 5;
                    return webSearchService.search(query, limit);
                });

        registry.register("web_extract", "抓取网页内容，返回纯文本。支持任何公开网页。对于大页面会截断到合理长度。",
                Map.of(
                        "url", Map.of("type", "string", "description", "要抓取的网页 URL", "required", true)
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    return webSearchService.extract((String) p.get("url"));
                });
    }

    // ==================== 代码检索 + 精确编辑（Cursor 风格） ====================

    private void registerSearchAndEditTools() {
        // search_code：ripgrep 优先，Java 正则兜底。定位代码/符号的首选，取代逐个 read_file 盲找。
        registry.register("search_code",
                "在代码/文本文件中按正则搜索内容，返回 文件:行号:匹配行。定位函数、类、符号、关键字时优先用这个，不要逐个 read_file 盲找。" +
                "path 默认项目根目录；glob 可限定文件类型如 *.java。",
                Map.of(
                        "pattern", Map.of("type", "string", "description", "正则表达式（ripgrep/Java 正则语法）", "required", true),
                        "path", Map.of("type", "string", "description", "搜索目录或文件，默认项目根目录"),
                        "glob", Map.of("type", "string", "description", "文件名过滤，如 *.java、*.{ts,tsx}"),
                        "max_results", Map.of("type", "integer", "description", "最大匹配条数，默认100")
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    String pattern = (String) p.get("pattern");
                    if (StringUtils.isBlank(pattern)) return "{\"error\":\"pattern 不能为空\"}";
                    String path = (String) p.get("path");
                    String glob = (String) p.get("glob");
                    int max = p.containsKey("max_results") ? ((Number) p.get("max_results")).intValue() : 100;
                    return searchCode(pattern, path, glob, max);
                });

        // edit_file：锚点替换，改已有文件只传 diff，不重发整文件。
        registry.register("edit_file",
                "精确编辑已有文件：把 old_string 替换为 new_string。修改已存在的文件用这个，不要用 write_file 重写整个文件。" +
                "old_string 必须与文件中的原文逐字符一致（含缩进），且默认必须唯一匹配——不唯一时请在 old_string 里多带几行上下文。删除内容时 new_string 传空字符串。",
                Map.of(
                        "path", Map.of("type", "string", "description", "要编辑的文件路径", "required", true),
                        "old_string", Map.of("type", "string", "description", "被替换的原文（逐字符一致，含缩进）", "required", true),
                        "new_string", Map.of("type", "string", "description", "替换后的新内容；删除则传空串", "required", true),
                        "replace_all", Map.of("type", "boolean", "description", "替换所有匹配（默认 false，只替换唯一一处）")
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    String path = (String) p.get("path");
                    String oldStr = (String) p.get("old_string");
                    String newStr = Objects.isNull(p.get("new_string")) ? "" : (String) p.get("new_string");
                    boolean replaceAll = Boolean.TRUE.equals(p.get("replace_all"));
                    return editFile(path, oldStr, newStr, replaceAll);
                });
    }

    /** search_code 实现：优先 ripgrep，失败回退 Java 正则扫描。 */
    private String searchCode(String pattern, String path, String glob, int maxResults) {
        Path root = (StringUtils.isBlank(path))
                ? Path.of(System.getProperty("user.dir")).toAbsolutePath()
                : resolveSearchPath(path);
        if (!Files.exists(root)) return "{\"error\":\"路径不存在: " + path + "\"}";

        String rg = ripgrepPath();
        if (Objects.nonNull(rg)) {
            String r = searchViaRipgrep(rg, pattern, root, glob, maxResults);
            if (Objects.nonNull(r)) return r;  // null 表示 rg 执行异常，回退
        }
        return searchViaJava(pattern, root, glob, maxResults);
    }

    /** 探测 ripgrep 是否可用，返回可执行路径或 null。结果缓存避免每次 where 调用。 */
    private static volatile String cachedRgPath;   // null=未探测; ""=不可用; 其它=路径
    private String ripgrepPath() {
        if (Objects.nonNull(cachedRgPath)) return cachedRgPath.isEmpty() ? null : cachedRgPath;
        synchronized (BuiltinTools.class) {
            if (Objects.nonNull(cachedRgPath)) return cachedRgPath.isEmpty() ? null : cachedRgPath;
            try {
                boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
                ProcessBuilder pb = win ? new ProcessBuilder("where", "rg")
                                        : new ProcessBuilder("which", "rg");
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                boolean ok = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && proc.exitValue() == 0;
                cachedRgPath = (ok && !StringUtils.isBlank(out)) ? out.lines().findFirst().orElse("rg").trim() : "";
            } catch (Exception e) {
                cachedRgPath = "";
            }
            return cachedRgPath.isEmpty() ? null : cachedRgPath;
        }
    }

    private String searchViaRipgrep(String rg, String pattern, Path root, String glob, int maxResults) {
        try {
            List<String> cmd = new ArrayList<>(List.of(
                    rg, "--line-number", "--no-heading", "--color", "never",
                    "--max-count", String.valueOf(Math.max(1, maxResults))));
            if (StringUtils.isNotBlank(glob)) { cmd.add("--glob"); cmd.add(glob); }
            cmd.add(pattern);
            cmd.add(root.toString());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) { proc.destroyForcibly(); return "{\"error\":\"搜索超时（30s）\"}"; }
            // rg 退出码：0=有匹配 1=无匹配 2=出错。出错则回退 Java 实现
            if (proc.exitValue() == 2) return null;
            return formatSearchOutput(out, root, maxResults, "ripgrep");
        } catch (Exception e) {
            return null; // 回退 Java 实现
        }
    }

    private String searchViaJava(String pattern, Path root, String glob, int maxResults) {
        java.util.regex.Pattern re;
        try {
            re = java.util.regex.Pattern.compile(pattern);
        } catch (Exception e) {
            return "{\"error\":\"正则非法: " + e.getMessage() + "\"}";
        }
        java.util.regex.Pattern globRe = globToRegex(glob);
        StringBuilder sb = new StringBuilder();
        int[] count = {0};
        try (var stream = Files.walk(root)) {
            var it = stream.filter(Files::isRegularFile)
                    .filter(f -> !isBinaryOrIgnored(f))
                    .filter(f -> Objects.isNull(globRe) || globRe.matcher(f.getFileName().toString()).matches())
                    .iterator();
            while (it.hasNext() && count[0] < maxResults) {
                Path f = it.next();
                List<String> lines;
                try { lines = Files.readAllLines(f, StandardCharsets.UTF_8); }
                catch (Exception e) { continue; } // 跳过非 UTF-8/不可读
                for (int i = 0; i < lines.size() && count[0] < maxResults; i++) {
                    if (re.matcher(lines.get(i)).find()) {
                        sb.append(root.relativize(f).toString().replace('\\', '/'))
                          .append(':').append(i + 1).append(':')
                          .append(truncate(lines.get(i).strip(), 300)).append('\n');
                        count[0]++;
                    }
                }
            }
        } catch (Exception e) {
            return "{\"error\":\"搜索失败: " + e.getMessage() + "\"}";
        }
        if (count[0] == 0) return "（无匹配）pattern=" + pattern;
        return "匹配 " + count[0] + " 处（Java 正则扫描" + (count[0] >= maxResults ? "，已达上限" : "") + "）:\n" + sb;
    }

    private String formatSearchOutput(String raw, Path root, int maxResults, String engine) {
        if (StringUtils.isBlank(raw)) return "（无匹配）";
        String[] lines = raw.split("\n");
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String line : lines) {
            if (StringUtils.isBlank(line)) continue;
            if (n >= maxResults) break;
            // rg 输出绝对路径，转相对路径更紧凑
            String rel = line;
            String absRoot = root.toString().replace('\\', '/');
            String norm = line.replace('\\', '/');
            if (norm.startsWith(absRoot)) {
                rel = norm.substring(absRoot.length()).replaceFirst("^/", "");
            }
            sb.append(truncate(rel, 300)).append('\n');
            n++;
        }
        if (n == 0) return "（无匹配）";
        return "匹配 " + n + " 处（" + engine + (n >= maxResults ? "，已达上限" : "") + "）:\n" + sb;
    }

    /** edit_file 实现：校验唯一匹配后替换。 */
    private String editFile(String path, String oldStr, String newStr, boolean replaceAll) {
        if (StringUtils.isBlank(path)) return "{\"error\":\"path 不能为空\"}";
        if (Objects.isNull(oldStr) || oldStr.isEmpty()) return "{\"error\":\"old_string 不能为空（新建文件请用 write_file）\"}";
        if (oldStr.equals(newStr)) return "{\"error\":\"old_string 与 new_string 相同，无需编辑\"}";
        try {
            Path target = resolveSearchPath(path);
            if (!Files.exists(target)) return "{\"error\":\"文件不存在: " + path + "（新建文件用 write_file）\"}";
            String content = Files.readString(target, StandardCharsets.UTF_8);

            int occurrences = countOccurrences(content, oldStr);
            if (occurrences == 0) {
                return "{\"error\":\"未找到 old_string。请确认与原文逐字符一致（含缩进/换行），可先用 read_file 核对。\"}";
            }
            if (occurrences > 1 && !replaceAll) {
                return "{\"error\":\"old_string 匹配到 " + occurrences + " 处，不唯一。请在 old_string 里多带几行上下文使其唯一，或传 replace_all=true 全部替换。\"}";
            }

            String updated = replaceAll
                    ? content.replace(oldStr, newStr)
                    : content.substring(0, content.indexOf(oldStr)) + newStr
                      + content.substring(content.indexOf(oldStr) + oldStr.length());

            Files.writeString(target, updated, StandardCharsets.UTF_8);
            invalidateReadCache(path);
            int replaced = replaceAll ? occurrences : 1;
            log.info("文件已编辑: {}（替换 {} 处）", target.toAbsolutePath(), replaced);
            return "{\"success\":true,\"path\":\"" + target.toString().replace("\\", "/")
                    + "\",\"replaced\":" + replaced + "}";
        } catch (Exception e) {
            return "{\"error\":\"编辑文件失败: " + e.getMessage() + "\"}";
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) { n++; idx += needle.length(); }
        return n;
    }

    /**
     * 搜索/编辑专用路径解析：与 write_file 的 workspace 限制不同，
     * 这两个工具要能操作真实项目（绝对路径直接用），所以不强制锁进 workspace。
     */
    private Path resolveSearchPath(String path) {
        String normalized = path.replace('\\', '/').trim();
        if (normalized.equalsIgnoreCase("workspace")
                || normalized.regionMatches(true, 0, "workspace/", 0, 10))
            return resolveWorkspacePath(path);
        Path p = Path.of(normalized);
        if (p.isAbsolute()) return p.normalize();
        return Path.of(System.getProperty("user.dir")).toAbsolutePath()
                .resolve(normalized).normalize();
    }

    /** 把 glob（*.java、*.{ts,tsx}）转成文件名正则；null/空返回 null（不过滤）。 */
    private static java.util.regex.Pattern globToRegex(String glob) {
        if (StringUtils.isBlank(glob)) return null;
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> re.append("[^/]*");
                case '?' -> re.append('.');
                case '.' -> re.append("\\.");
                case '{' -> re.append('(');
                case '}' -> re.append(')');
                case ',' -> re.append('|');
                default -> re.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        try { return java.util.regex.Pattern.compile(re.toString()); }
        catch (Exception e) { return null; }
    }

    /** 跳过二进制文件和常见无关目录（target/.git/node_modules 等）。 */
    private static boolean isBinaryOrIgnored(Path f) {
        String s = f.toString().replace('\\', '/');
        if (s.contains("/target/") || s.contains("/.git/") || s.contains("/node_modules/")
                || s.contains("/.idea/") || s.contains("/build/") || s.contains("/dist/")) return true;
        String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = n.substring(dot + 1);
        return Set.of("class","jar","war","zip","gz","tar","png","jpg","jpeg","gif","ico","pdf",
                "exe","dll","so","bin","mp4","mp3","woff","woff2","ttf","eot","lock").contains(ext);
    }

    private void registerExecTool() {
        if (!execEnabled) {
            log.info("exec_command 未注册（agent.tools.exec-enabled=false）");
            return;
        }
        registry.register("exec_command", "执行命令（有安全检查，默认关闭）。工作目录为 workspace。当前运行环境是 Windows，优先使用 cmd / PowerShell 语法。搜索代码请用 search_code。",
                Map.of(
                        "command", Map.of("type", "string", "description", "要执行的命令", "required", true)
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    return execCommand((String) p.get("command"));
                });
    }

    // ==================== 浏览器工具 ====================

    private void registerBrowserTools() {
        registry.register("browser_navigate", "打开网页，返回页面标题和无障碍树快照",
                Map.of(
                        "url", Map.of("type", "string", "description", "要打开的网页地址", "required", true),
                        "sessionId", Map.of("type", "string", "description", "浏览器会话ID（可选，默认default）")
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    String url = (String) p.get("url");
                    String blocked = networkGuard.validateUrl(url);
                    if (Objects.nonNull(blocked)) return "{\"error\":\"" + blocked.replace("\"", "'") + "\"}";
                    String sid = (String) p.getOrDefault("sessionId", "default");
                    return browserService.navigate(sid, url);
                });

        registry.register("browser_snapshot", "获取当前页面的无障碍树快照，显示可交互元素的编号ref",
                Map.of(
                        "full", Map.of("type", "boolean", "description", "是否获取完整深度快照"),
                        "sessionId", Map.of("type", "string", "description", "浏览器会话ID")
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    boolean full = Boolean.TRUE.equals(p.get("full"));
                    String sid = (String) p.getOrDefault("sessionId", "default");
                    return browserService.snapshot(sid, full);
                });

        registry.register("browser_click",
                "点击页面元素。默认用快照编号 ref（如 \"10\"）。解析失败时不要盲重试同参，改 by 切换策略："
                        + "ref=仅编号；text=精确文本；role=button=名称 或 link=名称；css=CSS选择器；aria=aria-label/placeholder。",
                Map.of(
                        "ref", Map.of("type", "string",
                                "description", "快照编号（推荐）或文本/选择器，含义由 by 决定", "required", true),
                        "by", Map.of("type", "string",
                                "description", "解析策略: auto|ref|text|role|css|aria，默认 auto"),
                        "sessionId", Map.of("type", "string", "description", "浏览器会话ID")
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    String ref = strArg(p, "ref");
                    String by = strArg(p, "by");
                    if (StringUtils.isBlank(by)) by = "auto";
                    String sid = (String) p.getOrDefault("sessionId", "default");
                    return browserService.click(sid, ref, by);
                });

        registry.register("browser_type", "在输入框中输入文字",
                Map.of(
                        "ref", Map.of("type", "string", "description", "输入框编号或placeholder", "required", true),
                        "text", Map.of("type", "string", "description", "要输入的文字", "required", true),
                        "sessionId", Map.of("type", "string", "description", "浏览器会话ID")
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    String ref = (String) p.get("ref");
                    String text = (String) p.get("text");
                    String sid = (String) p.getOrDefault("sessionId", "default");
                    return browserService.type(sid, ref, text);
                });

        registry.register("browser_press", "按下键盘按键（Enter, Tab, Escape, ArrowDown 等）",
                Map.of(
                        "key", Map.of("type", "string", "description", "按键名称", "required", true),
                        "sessionId", Map.of("type", "string", "description", "浏览器会话ID")
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    String key = (String) p.get("key");
                    String sid = (String) p.getOrDefault("sessionId", "default");
                    return browserService.press(sid, key);
                });

        registry.register("browser_scroll", "滚动页面",
                Map.of(
                        "direction", Map.of("type", "string", "description", "滚动方向: up 或 down", "required", true),
                        "sessionId", Map.of("type", "string", "description", "浏览器会话ID")
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    String dir = (String) p.get("direction");
                    String sid = (String) p.getOrDefault("sessionId", "default");
                    return browserService.scroll(sid, dir);
                });

        registry.register("browser_screenshot", "截取当前页面截图并保存为 PNG 文件",
                Map.of(
                        "sessionId", Map.of("type", "string", "description", "浏览器会话ID")
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    String sid = (String) p.getOrDefault("sessionId", "default");
                    return browserService.screenshot(sid);
                });

        registry.register("browser_evaluate", "在页面中执行 JavaScript 代码",
                Map.of(
                        "expression", Map.of("type", "string", "description", "JavaScript 表达式", "required", true),
                        "sessionId", Map.of("type", "string", "description", "浏览器会话ID")
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    String expr = (String) p.get("expression");
                    String sid = (String) p.getOrDefault("sessionId", "default");
                    return browserService.evaluate(sid, expr);
                });

        registry.register("browser_extract_text",
                "抽取当前页正文并写入文件。飞书/wiki 有侧栏目录时会按目录逐章抽取，"
                        + "一次调用覆盖全部章节。代码块写成 Markdown 围栏，图片保存到"
                        + "同目录 images/。禁止 browser_evaluate 手动滚屏。",
                Map.of(
                        "path", Map.of("type", "string",
                                "description", "写入路径，默认 _source.md"),
                        "mode", Map.of("type", "string",
                                "description", "overwrite 或 append，默认 overwrite"),
                        "sessionId", Map.of("type", "string",
                                "description", "浏览器会话ID")
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    String sid = (String) p.getOrDefault("sessionId", "default");
                    String path = (String) p.get("path");
                    if (StringUtils.isBlank(path)) {
                        path = "_source.md";
                    }
                    boolean append = "append".equalsIgnoreCase(
                            String.valueOf(p.get("mode")));
                    Path mdFile = resolveWorkspacePath(path);
                    Path mdParent = mdFile.getParent();
                    Path imgDir = (mdParent == null ? mdFile : mdParent)
                            .resolve(EXTRACT_IMAGES_DIR);
                    String text = browserService.extractArticleText(sid, imgDir);
                    if (text.startsWith("抽取失败")) {
                        return text;
                    }
                    String chunk = append ? "\n\n" + text : text;
                    String written = writeFile(path, chunk, append);
                    if (!append && pathFileName(path).equalsIgnoreCase("_source.md")) {
                        writeFile("notes.md", chunk, false);
                    }
                    return written;
                });

        registry.register("browser_close", "关闭浏览器会话",
                Map.of(
                        "sessionId", Map.of("type", "string", "description", "浏览器会话ID")
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    String sid = (String) p.getOrDefault("sessionId", "default");
                    return browserService.close(sid);
                });
    }

    // ==================== 记忆工具（对标 hermes-agent memory） ====================

    private void registerMemoryTool() {
        registry.register("memory",
                "持久化记忆工具，保存跨会话的重要事实。两个存储目标：'memory'（Agent笔记：环境事实、项目约定、工具经验）和 'user'（用户画像：偏好、沟通风格、技术领域）。" +
                "三种操作：add（追加新条目）、replace（替换已有条目，需提供 old_text 匹配）、remove（删除条目）。" +
                "每轮对话都会注入记忆快照，保持紧凑，只存将来真正有用的。不要存任务进度、已完成工作的日志、临时状态。",
                Map.of(
                        "action", Map.of("type", "string", "description", "操作类型: add / replace / remove", "required", true),
                        "target", Map.of("type", "string", "description", "存储目标: memory（Agent笔记）或 user（用户画像）", "required", true),
                        "content", Map.of("type", "string", "description", "条目内容（add/replace 时必填）"),
                        "old_text", Map.of("type", "string", "description", "要匹配的旧文本片段（replace/remove 时必填，子串匹配）")
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    String action = (String) p.get("action");
                    String target = (String) p.get("target");
                    String content = (String) p.getOrDefault("content", "");
                    String oldText = (String) p.getOrDefault("old_text", "");

                    Map<String, Object> result;
                    switch (action) {
                        case "add":
                            result = memoryStore.add(target, content);
                            break;
                        case "replace":
                            result = memoryStore.replace(target, oldText, content);
                            break;
                        case "remove":
                            result = memoryStore.remove(target, oldText);
                            break;
                        default:
                            result = Map.of("success", false, "message",
                                    "未知操作: " + action + "。支持: add / replace / remove");
                    }
                    try {
                        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
                    } catch (Exception e) {
                        return "{\"success\":false,\"message\":\"序列化失败\"}";
                    }
                });
    }


    // ==================== 云端图像生成（多后端自动降级） ====================

    private void registerImageGenerateTool() {
        registry.register("image_generate",
                "生成图片（云端多后端自动降级：ChatAnywhere/MiMo/FAL/SiliconFlow/智谱CogView）。" +
                "不需要 ComfyUI 服务，适合快速生成概念图、插画等。英文 prompt 效果最好。",
                Map.of(
                        "prompt", Map.of("type", "string", "description", "图片描述（英文效果最好，尽量详细描述画面内容、风格、光影）", "required", true),
                        "aspect_ratio", Map.of("type", "string", "description", "比例: landscape(横版) / square(方形) / portrait(竖版)，默认 landscape")
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    String prompt = (String) p.get("prompt");
                    String ratio = (String) p.getOrDefault("aspect_ratio", "landscape");
                    return imageGenerationService.generate(prompt, ratio);
                });
    }

    // ==================== ComfyUI 工具（集成自 ClawHub skills） ====================

    private void registerComfyUITools() {
        // 1. 状态检查
        registry.register("comfyui_status", "检查 ComfyUI 服务是否在线，返回 GPU/VRAM 等系统信息。",
                Map.of(), args -> comfyuiService.getStatus());

        // 2. 列出工作流/节点
        registry.register("comfyui_workflows", "列出 ComfyUI 可用的节点和工作流类别。",
                Map.of(), args -> comfyuiService.listWorkflows());

        // 2.5 获取可用模型
        registry.register("comfyui_models", "列出 ComfyUI 中已安装的 checkpoint 模型。文生图前先查看可用模型。",
                Map.of(), args -> comfyuiService.getCheckpointModels());

        // 3. 执行工作流（通用）
        registry.register("comfyui_execute",
                "执行 ComfyUI 工作流。三种模式：1) action=run + workflow_json → 提交；2) action=status + prompt_id → 查询；3) action=upload + image_path → 上传图片。",
                Map.of(
                        "action", Map.of("type", "string", "description", "操作: run / status / upload", "required", true),
                        "workflow_json", Map.of("type", "string", "description", "ComfyUI API 格式的工作流 JSON（run 时必填）"),
                        "prompt_id", Map.of("type", "string", "description", "任务ID（status 时必填）"),
                        "image_path", Map.of("type", "string", "description", "本地图片路径（upload 时必填）")
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    String action = (String) p.get("action");
                    return switch (action) {
                        case "run" -> comfyuiService.submitWorkflow((String) p.get("workflow_json"));
                        case "status" -> comfyuiService.checkStatus((String) p.get("prompt_id"));
                        case "upload" -> comfyuiService.uploadImage((String) p.get("image_path"));
                        default -> "{\"error\":\"未知action: " + action + "\"}";
                    };
                });

        // 4. 文生图快捷方式
        registry.register("comfyui_txt2img",
                "通过 ComfyUI 文生图。必须先调 comfyui_models 查看可用模型，根据画风选择合适的 checkpoint。提交后返回图片结果。",
                Map.of(
                        "prompt", Map.of("type", "string", "description", "正向提示词（英文效果更好，尽量详细描述画面内容、风格、光影）", "required", true),
                        "negative_prompt", Map.of("type", "string", "description", "负向提示词（不想出现的元素，如 low quality, blurry, deformed 等，不填则用默认值）"),
                        "checkpoint", Map.of("type", "string", "description", "checkpoint 模型名（必填，从 comfyui_models 返回的列表中选择，根据画风选合适的模型）"),
                        "width", Map.of("type", "integer", "description", "宽度（默认768）"),
                        "height", Map.of("type", "integer", "description", "高度（默认768）"),
                        "steps", Map.of("type", "integer", "description", "采样步数（默认25，越高越细腻但越慢）"),
                        "cfg", Map.of("type", "number", "description", "CFG引导系数（默认7.0，越高越贴近提示词但可能过拟合）"),
                        "seed", Map.of("type", "integer", "description", "随机种子（不填则随机）")
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    return comfyuiService.txt2img(
                            (String) p.get("prompt"),
                            (String) p.getOrDefault("negative_prompt", ""),
                            p.containsKey("width") ? ((Number) p.get("width")).intValue() : 768,
                            p.containsKey("height") ? ((Number) p.get("height")).intValue() : 768,
                            (String) p.get("checkpoint"),
                            p.containsKey("steps") ? ((Number) p.get("steps")).intValue() : 25,
                            p.containsKey("cfg") ? ((Number) p.get("cfg")).doubleValue() : 7.0,
                            p.containsKey("seed") ? ((Number) p.get("seed")).longValue() : null
                    );
                });

        // 4.5 图生图（img2img）
        registry.register("comfyui_img2img",
                "图生图：上传参考图 + 提示词，在原图基础上重新绘制。denoise 控制变化程度（0=不变，1=完全重绘，推荐0.4~0.7）。图片路径从用户消息中获取（File saved at: 后的路径）。",
                Map.of(
                        "image_path", Map.of("type", "string", "description", "参考图片的本地路径（必填）", "required", true),
                        "prompt", Map.of("type", "string", "description", "正向提示词（描述想要的效果/风格变化）", "required", true),
                        "negative_prompt", Map.of("type", "string", "description", "负向提示词（不填用默认值）"),
                        "checkpoint", Map.of("type", "string", "description", "checkpoint 模型名（从 comfyui_models 选）"),
                        "denoise", Map.of("type", "number", "description", "去噪强度 0~1（默认0.6，越小越保留原图，越大变化越大）"),
                        "steps", Map.of("type", "integer", "description", "采样步数（默认25）"),
                        "cfg", Map.of("type", "number", "description", "CFG引导系数（默认7.0）"),
                        "seed", Map.of("type", "integer", "description", "随机种子（不填则随机）")
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    return comfyuiService.img2img(
                            (String) p.get("image_path"),
                            (String) p.get("prompt"),
                            (String) p.getOrDefault("negative_prompt", ""),
                            (String) p.get("checkpoint"),
                            p.containsKey("denoise") ? ((Number) p.get("denoise")).doubleValue() : 0.6,
                            p.containsKey("steps") ? ((Number) p.get("steps")).intValue() : 25,
                            p.containsKey("cfg") ? ((Number) p.get("cfg")).doubleValue() : 7.0,
                            p.containsKey("seed") ? ((Number) p.get("seed")).longValue() : null
                    );
                });

        // 4.8 图片质检
        registry.register("comfyui_check_quality",
                "检查 AI 生成图片的质量。传入图片路径，返回质量评分和问题列表。7分及以上合格，不合格时会给出改进建议。生成图片后务必调用此工具检查质量。",
                Map.of(
                        "image_path", Map.of("type", "string", "description", "图片本地路径（必填）", "required", true)
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    return qualityChecker.check((String) p.get("image_path"));
                });

        // 5. 图生视频
        registry.register("comfyui_img2video",
                "图片转视频（LTX-2）。上传图片并生成动态视频，约5-10分钟。",
                Map.of(
                        "image_path", Map.of("type", "string", "description", "输入图片路径", "required", true),
                        "movement", Map.of("type", "string", "description", "运动描述，如 gentle hair breeze")
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    return comfyuiService.img2video(
                            (String) p.get("image_path"),
                            (String) p.getOrDefault("movement", "gentle movement"));
                });

        // 6. TTS 语音合成
        registry.register("comfyui_tts",
                "通过 ComfyUI Qwen-TTS 将文本转语音。",
                Map.of(
                        "text", Map.of("type", "string", "description", "文本", "required", true),
                        "voice", Map.of("type", "string", "description", "语音风格")
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    return comfyuiService.tts(
                            (String) p.get("text"),
                            (String) p.get("voice"));
                });
    }

    private void registerSkillTools() {
        // skill_list - 列出所有已安装的 skill
        registry.register("skill_list",
                "List installed skills with name and description. Use this first to see what skills are available.",
                Map.of(),
                args -> {
                    String summary = skillStore.getSkillListSummary();
                    return summary.isEmpty() ? "No skills installed." : summary;
                });

        // skill_view - 加载 skill 完整内容
        registry.register("skill_view",
                "Load full content of a skill by name. Optionally load a sub-file (references, templates, scripts).",
                Map.of(
                        "name", Map.of("type", "string", "description", "Skill name", "required", true),
                        "file_path", Map.of("type", "string", "description", "Optional sub-file path like references/api.md")
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    String name = (String) p.get("name");
                    String filePath = (String) p.getOrDefault("file_path", "");
                    if (Objects.isNull(name)) return "{\"error\":\"name is required\"}";
                    if (!filePath.isEmpty()) {
                        String content = skillStore.viewSkillFile(name, filePath);
                        return Optional.ofNullable(content).orElse("File not found: " + name + "/" + filePath);
                    }
                    String content = skillStore.viewSkill(name);
                    return Optional.ofNullable(content).orElse("Skill not found: " + name);
                });

        // skill_manage - 创建/编辑/删除 skill
        registry.register("skill_manage",
                "Create, edit, patch, or delete skills. Skills are reusable instruction sets that persist across sessions.",
                Map.of(
                        "action", Map.of("type", "string", "description", "create | edit | patch | delete", "required", true),
                        "name", Map.of("type", "string", "description", "Skill name (lowercase, hyphens)", "required", true),
                        "description", Map.of("type", "string", "description", "Brief description (for create)"),
                        "content", Map.of("type", "string", "description", "Full SKILL.md body (for create/edit)"),
                        "category", Map.of("type", "string", "description", "Optional category subfolder (for create)"),
                        "old_text", Map.of("type", "string", "description", "Text to find (for patch)"),
                        "new_text", Map.of("type", "string", "description", "Replacement text (for patch)")
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    String action = (String) p.get("action");
                    String name = (String) p.get("name");
                    if (Objects.isNull(action) || Objects.isNull(name)) return "{\"error\":\"action and name are required\"}";
                    try {
                        Map<String, Object> result;
                        switch (action) {
                            case "create":
                                result = skillStore.createSkill(name,
                                        (String) p.get("description"),
                                        (String) p.get("content"),
                                        (String) p.get("category"));
                                break;
                            case "edit":
                                result = skillStore.editSkill(name, (String) p.get("content"));
                                break;
                            case "patch":
                                result = skillStore.patchSkill(name,
                                        (String) p.get("old_text"),
                                        (String) p.get("new_text"));
                                break;
                            case "delete":
                                result = skillStore.deleteSkill(name);
                                break;
                            default:
                                return "{\"error\":\"unknown action: " + action + "\"}";
                        }
                        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result);
                    } catch (Exception e) {
                        return "{\"success\":false,\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
                    }
                });
    }

    // ==================== 文件操作实现 ====================

    /** 默认 workspace：与 AgentDataPaths 对齐（miniagent.data.dir/workspace） */
    public static Path defaultWorkspaceRoot() {
        String data = System.getProperty("miniagent.data.dir");
        Path base = (StringUtils.isNotBlank(data))
                ? Path.of(data)
                : Path.of(System.getProperty("user.dir")).toAbsolutePath();
        return base.resolve("workspace").toAbsolutePath().normalize();
    }

    /** 有效 workspace 根：子 Agent 可覆盖为独立目录 */
    public static Path effectiveWorkspaceRoot() {
        Path override = WorkspaceContext.getRootOverride();
        return Objects.nonNull(override) ? override.toAbsolutePath().normalize() : defaultWorkspaceRoot();
    }

    /** 为子 Agent 准备独立写出目录：workspace/_sub/{safeId}/ */
    public static Path prepareSubagentWorkspace(String subSessionId) {
        String safe = Objects.isNull(subSessionId) ? "sub" : subSessionId.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safe.length() > 80) safe = safe.substring(0, 80);
        Path root = defaultWorkspaceRoot().resolve("_sub").resolve(safe).toAbsolutePath().normalize();
        try {
            java.nio.file.Files.createDirectories(root);
        } catch (Exception e) {
            throw new IllegalStateException("无法创建子 Agent workspace: " + root, e);
        }
        return root;
    }

    /**
     * 当前任务名（线程隔离，用于路由写文件到任务子目录）
     * AgentChatApplicationService 在每次用户消息时更新该值。
     */
    private static final ThreadLocal<String> currentTaskName = ThreadLocal.withInitial(() -> "default");

    public static void setCurrentTaskName(String name) {
        if (StringUtils.isBlank(name)) {
            currentTaskName.set("default");
            return;
        }
        // 1. 只取首行
        String slug = name.split("[\r\n]", 2)[0];
        // 2. 替换 Windows/Linux 非法路径字符、控制符和非 ASCII 字符。
        // Windows cmd/python 对中文路径容易乱码，工具工作目录统一用 ASCII slug。
        slug = slug.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_")
                .replaceAll("[^a-zA-Z0-9._-]", "_");
        // 3. 合并连续下划线，去首尾
        slug = slug.replaceAll("_{2,}", "_").replaceAll("^_|_$", "");
        // 4. 截断到 40 字符
        if (slug.length() > 40) slug = slug.substring(0, 40);
        currentTaskName.set(StringUtils.isBlank(slug) ? "task_" + Integer.toHexString(name.hashCode()) : slug);
    }

    public static String currentTaskName() {
        return currentTaskName.get();
    }

    public static void restoreTaskName(String name) {
        currentTaskName.set(StringUtils.isBlank(name) ? "default" : name);
    }

    public static void clearCurrentTaskName() {
        currentTaskName.remove();
    }

    private String readFile(String path, int offset, int limit) {
        try {
            Path target = resolveReadPath(path);
            if (!Files.exists(target)) return "{\"error\":\"文件不存在: " + path + "\"}";
            List<String> lines = Files.readAllLines(target, StandardCharsets.UTF_8);
            int from = Math.max(0, offset - 1);
            int to   = Math.min(lines.size(), from + limit);
            List<String> slice = lines.subList(from, to);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < slice.size(); i++) {
                sb.append(from + i + 1).append('|').append(slice.get(i)).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "{\"error\":\"读取文件失败: " + e.getMessage() + "\"}";
        }
    }

    private String readPackage(String packageName, int maxChars) {
        try {
            // 包名转路径：com.miniagent.agent.intent → src/main/java/com/miniagent/agent/intent

            String relativePath = packageName.replace('.', File.separatorChar);
            // 从项目根目录查找 src/main/java 下的包路径
            Path projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath();
            Path pkgDir = projectRoot.resolve("src").resolve("main").resolve("java").resolve(relativePath);

            // 如果当前目录找不到，向上一级查找（适配多模块项目）
            if (!Files.isDirectory(pkgDir)) {
                Path alt = projectRoot.resolve("mini-agent-app").resolve("src").resolve("main")
                        .resolve("java").resolve(relativePath);
                if (Files.isDirectory(alt)) pkgDir = alt;
            }
            if (!Files.isDirectory(pkgDir)) {
                // 尝试直接在项目根目录下搜索
                try (var stream = Files.walk(projectRoot, 6)) {
                    Optional<Path> found = stream
                            .filter(Files::isDirectory)
                            .filter(p -> p.endsWith(relativePath.replace('/', File.separatorChar)))
                            .findFirst();
                    if (found.isPresent()) pkgDir = found.get();
                }
            }

            if (!Files.isDirectory(pkgDir)) {
                return "{\"error\":\"包不存在: " + packageName + "（已尝试解析为 " + pkgDir + "）\"}";
            }

            List<Path> files = Files.walk(pkgDir, 1)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .toList();

            if (files.isEmpty()) {
                return "{\"files\":0,\"package\":\"" + packageName + "\"}";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("包 ").append(packageName).append(" 共 ").append(files.size()).append(" 个文件:\n\n");
            for (Path f : files) {
                String fileName = f.getFileName().toString();
                try {
                    String fileContent = Files.readString(f, StandardCharsets.UTF_8);
                    if (fileContent.length() > maxChars) {
                        fileContent = fileContent.substring(0, maxChars) + "\n... (截断，共 " + fileContent.length() + " 字符)";
                    }
                    sb.append("=== ").append(fileName).append(" ===\n");
                    sb.append(fileContent).append("\n\n");
                } catch (Exception e) {
                    sb.append("=== ").append(fileName).append(" === (读取失败: ").append(e.getMessage()).append(")\n\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "{\"error\":\"读取包失败: " + e.getMessage() + "\"}";
        }
    }

    private static final int KEEP_LARGE_FILE_MIN_BYTES = 10_000;
    private static final String EXTRACT_IMAGES_DIR = "images";

    private static String pathFileName(String path) {
        if (path == null) {
            return "";
        }
        String n = path.replace('\\', '/').trim();
        int i = n.lastIndexOf('/');
        return i < 0 ? n : n.substring(i + 1);
    }

    private String writeFile(String path, String content) {
        return writeFile(path, content, false);
    }

    private String writeFile(String path, String content, boolean append) {
        try {
            // 如果 path 只是文件名（无目录分隔符），自动放到 workspace/任务名/ 下
            Path target = resolveWorkspacePath(path);
            Files.createDirectories(target.getParent());
            // 如果是 markdown 文件，统一图片路径为 /static/images/ 前缀
            String finalContent = content;
            if (Objects.nonNull(path) && path.toLowerCase().endsWith(".md")) {
                finalContent = finalContent.replaceAll(
                    "!\\[([^\\]]*)\\]\\(generated-images/([^)]+)\\)",
                    "![$1](/static/images/$2)"
                );
            }

            if (append) {
                Files.writeString(target, finalContent, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } else if (Files.exists(target)) {
                long existing = Files.size(target);
                long incoming = finalContent.getBytes(StandardCharsets.UTF_8).length;
                if (existing >= KEEP_LARGE_FILE_MIN_BYTES && incoming * 2 < existing) {
                    return "{\"error\":\"拒绝用短文本覆盖已有大文件 "
                            + target.getFileName() + "（现有 " + existing
                            + " 字节，本次 " + incoming
                            + "）。请改写其他文件名，或用 mode=append。\"}";
                }
                Files.writeString(target, finalContent, StandardCharsets.UTF_8);
            } else {
                Files.writeString(target, finalContent, StandardCharsets.UTF_8);
            }
            invalidateReadCache(path);
            long totalBytes = Files.size(target);
            log.info("文件已{}: {} (本次 {} 字符, 当前共 {} 字节)",
                    append ? "追加" : "写入", target.toAbsolutePath(), finalContent.length(), totalBytes);
            return "{\"success\":true,\"mode\":\"" + (append ? "append" : "overwrite")
                    + "\",\"path\":\"" + target.toString().replace("\\", "/")
                    + "\",\"bytes_written\":" + finalContent.getBytes(StandardCharsets.UTF_8).length
                    + ",\"total_bytes\":" + totalBytes + "}";
        } catch (Exception e) {
            return "{\"error\":\"写入文件失败: " + e.getMessage() + "\"}";
        }
    }

    private String listFiles(String path, boolean recursive) {
        try {
            Path target = resolveReadPath(path);
            if (!Files.exists(target)) return "{\"error\":\"目录不存在: " + path + "\"}";
            StringBuilder sb = new StringBuilder();
            if (recursive) {
                Files.walk(target).filter(p -> !p.equals(target)).forEach(p ->
                        sb.append(target.relativize(p)).append(Files.isDirectory(p) ? "/" : "").append('\n'));
            } else {
                Files.list(target).forEach(p ->
                        sb.append(p.getFileName()).append(Files.isDirectory(p) ? "/" : "").append('\n'));
            }
            return sb.isEmpty() ? "（空目录）" : sb.toString();
        } catch (Exception e) {
            return "{\"error\":\"列出文件失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 提示词 / done_when 里的 {@code workspace/} 是数据目录别名，
     * 不是进程 CWD 下的相对路径。
     */
    public static String stripWorkspaceAlias(String path) {
        if (path == null) return "";
        String n = path.replace('\\', '/').trim();
        while (n.startsWith("./")) n = n.substring(2);
        if (n.equalsIgnoreCase("workspace")) return "";
        if (n.regionMatches(true, 0, "workspace/", 0, 10)) return n.substring(10);
        return n;
    }

    /**
     * 解析路径（写入）：默认强制落在真实 workspace 根下。
     * {@code workspace/foo.md} → {@code {dataDir}/workspace/foo.md}。
     */
    private Path resolveWorkspacePath(String path) {
        String task = WorkspaceContext.getTaskOverride();
        if (StringUtils.isBlank(task)) task = currentTaskName.get();
        return resolveWritePath(effectiveWorkspaceRoot(), task, path, allowAbsoluteWrite);
    }

    static Path resolveWritePath(Path workspaceRoot, String task, String path,
                                 boolean allowAbsolute) {
        Path root = workspaceRoot.toAbsolutePath().normalize();
        if (StringUtils.isBlank(path)) return root.resolve(task).normalize();
        String normalized = path.replace('\\', '/').trim();
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        boolean aliased = normalized.equalsIgnoreCase("workspace")
                || normalized.regionMatches(true, 0, "workspace/", 0, 10);
        Path resolved;
        if (aliased) {
            String rest = stripWorkspaceAlias(normalized);
            resolved = rest.isEmpty() ? root : root.resolve(rest).normalize();
        } else {
            Path p = Path.of(normalized);
            if (p.isAbsolute()) {
                resolved = p.toAbsolutePath().normalize();
            } else if (Objects.isNull(p.getParent())) {
                resolved = root.resolve(task).resolve(p).normalize();
            } else {
                resolved = root.resolve(p).normalize();
            }
        }
        if (!resolved.startsWith(root) && !allowAbsolute)
            throw new SecurityException("写入路径必须位于 workspace/ 内: " + resolved);
        return resolved;
    }

    /**
     * 读取场景（read_file / list_files）的路径解析。
     *
     * 与 {@link #resolveWorkspacePath} 的区别：写文件要锁进 workspace，但读文件
     * 经常是顺着 search_code / edit_file 给出的「项目根相对路径」来的。若仍按
     * workspace 解析，search→read→edit 这条编程主链路会断（文件被解析到
     * workspace/任务名/... 而真实文件在项目根下，报「文件不存在」）。
     *
     * 解析顺序：
     *   1. 绝对路径 → 直接用；
     *   2. 相对路径先按项目根解析，真实存在就用它（与 search_code/edit_file 对齐）；
     *   3. 都不存在 → 退回 {@link #resolveWorkspacePath}，保留读取自身产出物的原有语义。
     */
    private Path resolveReadPath(String path) {
        if (StringUtils.isBlank(path)) return resolveWorkspacePath(path);
        String normalized = path.replace('\\', '/').trim();
        if (normalized.equalsIgnoreCase("workspace")
                || normalized.regionMatches(true, 0, "workspace/", 0, 10))
            return resolveWorkspacePath(path);
        Path p = Path.of(normalized);
        if (p.isAbsolute()) return p.normalize();

        // 相对项目根解析；命中真实文件/目录就用它，对齐 search_code/edit_file。
        Path fromRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath()
                .resolve(normalized).normalize();
        if (Files.exists(fromRoot)) return fromRoot;

        // 否则退回 workspace 语义（读取自身产出物）。
        return resolveWorkspacePath(path);
    }

    // ==================== HTTP 工具实现 ====================

    private String httpGet(String url) {
        try {
            String blocked = networkGuard.validateUrl(url);
            if (Objects.nonNull(blocked)) return "{\"error\":\"" + blocked.replace("\"", "'") + "\"}";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "MiniAgent/1.0")
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = resp.body();
            if (body.length() > 8000) body = body.substring(0, 8000) + "\n…（已截断）";
            return body;
        } catch (Exception e) {
            return "{\"error\":\"HTTP GET 失败: " + e.getMessage() + "\"}";
        }
    }

    // ==================== 命令执行实现 ====================

    private static String redactSensitive(String s) {
        if (Objects.isNull(s)) return "";
        return s
                .replaceAll("(?i)(access_token=)[^&\\s\"'}\\]]+", "$1***")
                .replaceAll("(?i)(secret=)[^&\\s\"'}\\]]+", "$1***")
                .replaceAll("(?i)(api[_-]?key=)[^&\\s\"'}\\]]+", "$1***");
    }

    private static final java.util.regex.Pattern DANGEROUS_PATTERN = java.util.regex.Pattern.compile(
            "(rm\\s+-rf\\s+/|rm\\s+-rf\\s+\\*|/etc/(shadow|passwd)|"
                    + "cat.*/etc/|type.*\\\\Windows\\\\|"
                    + "curl.*\\$\\(|wget.*\\$\\(|"
                    + "python.*-c.*os\\.|python.*-c.*subprocess|"
                    + "powershell.*-enc|cmd.*\\/c.*echo|"
                    + ";\\s*rm|\\|\\s*rm|&&\\s*rm|"
                    + ";\\s*cat|\\|\\s*cat|&&\\s*cat)",
            java.util.regex.Pattern.CASE_INSENSITIVE
    );

    /**
     * 常驻/前台服务器命令模式：这类命令永不退出，前台执行会一直阻塞读流，把工具线程挂死。
     * 智能体在前台跑服务器也没意义（它无法与服务器交互、也看不到浏览器）。直接拒绝并给出正确做法。
     */
    private static final java.util.regex.Pattern LONG_RUNNING_SERVER_PATTERN = java.util.regex.Pattern.compile(
            "(?i)("
            + "python\\s+-m\\s+http\\.server|python\\s+-m\\s+SimpleHTTPServer"
            + "|http-server\\b|\\bnpx\\s+serve\\b|\\bserve\\b\\s+-"
            + "|npm\\s+(run\\s+)?(start|dev|serve)|yarn\\s+(start|dev|serve)|pnpm\\s+(start|dev|serve)"
            + "|vite\\b|next\\s+dev|nuxt\\s+dev|ng\\s+serve|webpack\\s+serve|webpack-dev-server"
            + "|flask\\s+run|uvicorn\\b|gunicorn\\b|php\\s+-S\\b"
            + "|spring-boot:run|\\bnodemon\\b|\\bwatch\\b"
            + ")"
    );

    private static final Set<String> DANGEROUS_COMMANDS = Set.of(
            "rm -rf /", "rm -rf /*", "rm -rf ~", "rm -rf .",
            "rmdir /s /q", "del /f /s /q",
            "mkfs", "mkfs.ext4", "mkfs.ntfs",
            "dd if=", "dd of=/dev",
            "> /dev/sda", "> /dev/nvme",
            "chmod 777", "chmod -R 777", "chown root",
            "sudo su", "sudo -i",
            "shutdown", "reboot", "halt", "poweroff",
            "systemctl stop", "systemctl disable",
            "format c:", "format d:",
            "del /f /s /q C:\\", "rd /s /q C:\\",
            "nc -e", "ncat", "socat",
            "/etc/shadow", "/etc/passwd",
            "SAM", "SYSTEM", "SECURITY",
            "base64 -d", "certutil -decode",
            "powershell -enc", "cmd /c echo"
    );

    private String execCommand(String command) {
        if (!execEnabled) {
            return "{\"error\":\"exec_command 已禁用（agent.tools.exec-enabled=false）\"}";
        }
        // 安全加固：多层防护

        // 第1层：危险命令黑名单检查
        String lc = command.toLowerCase().trim();
        for (String bad : DANGEROUS_COMMANDS) {
            if (lc.contains(bad.toLowerCase())) {
                log.warn("安全拦截 - 黑名单命中: {}", redactSensitive(command));
                return "{\"error\":\"命令被安全策略拒绝：该命令包含危险操作。\"}";
            }
        }

        // 第2层：正则模式匹配（防止编码绕过）
        if (DANGEROUS_PATTERN.matcher(command).find()) {
            log.warn("安全拦截 - 危险模式命中: {}", redactSensitive(command));
            return "{\"error\":\"命令被安全策略拒绝：检测到危险操作模式。\"}";
        }

        // 第3层：命令注入防护（检测链式命令）
        if (containsCommandInjection(command)) {
            log.warn("安全拦截 - 命令注入检测: {}", redactSensitive(command));
            return "{\"error\":\"命令被安全策略拒绝：检测到可能的命令注入。\"}";
        }

        // 第3.5层：常驻/前台服务器命令拦截。这类命令永不退出，前台跑会把工具线程挂死，
        // 且智能体也无法与服务器交互。直接拒绝并引导正确做法。
        if (LONG_RUNNING_SERVER_PATTERN.matcher(command).find()) {
            log.warn("安全拦截 - 常驻服务器命令: {}", redactSensitive(command));
            return "{\"error\":\"该命令会启动一个常驻服务器（永不退出），不能在前台执行——会一直阻塞。\","
                    + "\"hint\":\"不需要起服务器来'验证'前端产物：HTML/JS 文件写好后直接交付给用户，由用户在浏览器打开即可。"
                    + "若确实要本地预览，请让用户自己执行启动命令，不要由你在工具里前台运行。\"}";
        }

        try {
            boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
            ProcessBuilder pb = isWindows
                    ? new ProcessBuilder("cmd.exe", "/c", command)
                    : new ProcessBuilder("bash", "-c", command);

            // 第4层：工作目录限制在 workspace（避免扫用户主目录）
            File workDirFile = defaultWorkspaceRoot().toFile();
            if (!workDirFile.exists()) workDirFile.mkdirs();
            pb.directory(workDirFile);
            log.info("命令执行目录: {}", workDirFile.getAbsolutePath());

            pb.environment().put("PYTHONUTF8", "1");
            pb.environment().put("PYTHONIOENCODING", "UTF-8");
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            // 关键修复：异步读流 + 主线程限时等待。
            // 旧实现先 readAllBytes() 再 waitFor()，而 readAllBytes() 会阻塞到进程关闭 stdout
            // （即进程退出）为止——对常驻/输出不止的进程永远读不到 EOF，使后面的 30s 超时形同虚设。
            // 现在用独立线程 drain 输出，主线程 waitFor(timeout)，超时即 destroyForcibly，超时真正生效。
            final java.nio.charset.Charset cs = isWindows
                    ? java.nio.charset.Charset.forName("GBK")
                    : java.nio.charset.StandardCharsets.UTF_8;
            final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            Thread drainer = new Thread(() -> {
                try (var in = proc.getInputStream()) {
                    byte[] chunk = new byte[8192];
                    int n;
                    while ((n = in.read(chunk)) != -1) {
                        synchronized (buf) { buf.write(chunk, 0, n); }
                    }
                } catch (Exception ignored) {
                    // 进程被 destroyForcibly 时读流会抛异常，属预期，忽略
                }
            }, "exec-cmd-drainer");
            drainer.setDaemon(true);
            drainer.start();

            boolean finished = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                drainer.interrupt();
                String partial;
                synchronized (buf) { partial = new String(buf.toByteArray(), cs); }
                if (partial.length() > 2000) partial = partial.substring(0, 2000) + "\n…（已截断）";
                return "{\"error\":\"命令执行超时（30s），已强制终止。若是启动服务器/常驻进程，请改为交付文件由用户自行运行。\","
                        + "\"partial_output\":" + jsonString(partial) + "}";
            }
            drainer.join(2000); // 等读流线程把剩余输出读完
            int exitCode = proc.exitValue();
            String out;
            synchronized (buf) { out = new String(buf.toByteArray(), cs); }
            if (out.length() > 6000) out = out.substring(0, 6000) + "\n…（已截断）";
            return "exit_code=" + exitCode + "\n" + out;
        } catch (Exception e) {
            return "{\"error\":\"命令执行失败: " + e.getMessage() + "\"}";
        }
    }

    /** 把字符串安全编码为 JSON 字符串字面量（含引号），用于拼装错误返回。 */
    private static String jsonString(String s) {
        if (Objects.isNull(s)) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * 检测命令注入（链式命令、管道、子shell等）
     */
    private boolean containsCommandInjection(String command) {
        String trimmed = command.trim().toLowerCase();

        // 1. 真正危险的操作：删除系统文件、读取敏感文件、下载执行
        String[] dangerous = {
            "rm -rf /", "rm -rf *", "del /s /q ", "del /f /s /q",
            "/etc/shadow", "/etc/passwd", "c:\\windows\\system32",
            "format ", "shutdown", "reboot",
            "eval ", "exec ", "subprocess", "os.system"
        };
        for (String bad : dangerous) {
            if (trimmed.matches(".*" + bad + ".*")) return true;
        }

        // 2. 允许正常命令操作：管道 |、链接 && ||、分号 ; 都是正常语法
        //    只拦截重定向到项目外目录
        if (trimmed.contains(">") && !trimmed.contains("> workspace/")
                && !trimmed.contains("> ./") && !trimmed.contains("> nul")) {
            return true;
        }
        return false;
    }

    // ==================== JSON 解析 ====================

    @SuppressWarnings("unchecked")
    /** 工具参数 JSON 解析失败时的标记键。值为 true 表示传入的 arguments 不是合法 JSON
     *  （最常见原因：模型单轮输出被长度上限截断，tool_call 的 arguments 是半截 JSON）。
     *  各工具 handler 可据此回报"参数被截断"而不是把 null 当正常值写出去。 */
    static final String PARSE_ERROR_KEY = "__parse_error__";

    private Map<String, Object> parseJson(String json) {
        if (StringUtils.isBlank(json)) return new HashMap<>();
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> m = om.readValue(json, Map.class);
            return m;
        } catch (Exception e) {
            // 不再静默吞掉：返回带错误标记的可变 Map，调用方可识别"参数 JSON 残缺/被截断"。
            log.warn("工具参数 JSON 解析失败（疑似输出被截断，长度={}）: {}",
                    json.length(), e.getMessage());
            Map<String, Object> err = new HashMap<>();
            err.put(PARSE_ERROR_KEY, Boolean.TRUE);
            return err;
        }
    }

    /** 截断超长字符串，附省略标记。供搜索结果控 token。 */
    private static String truncate(String s, int max) {
        if (Objects.isNull(s)) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    static String strArg(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return Objects.isNull(v) ? "" : String.valueOf(v).trim();
    }

    static int intArg(Map<String, Object> p, String key, int defaultValue) {
        Object v = p.get(key);
        if (Objects.isNull(v)) return defaultValue;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
