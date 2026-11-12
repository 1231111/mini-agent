package com.miniagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.browser.BrowserService;
import com.miniagent.agent.browser.BrowserSessionIdentity;
import com.miniagent.agent.browser.BrowserUploadFiles;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.agent.skill.SkillStore;
import com.miniagent.agent.song.LyriaMusicService;
import com.miniagent.agent.song.MiniMaxMusicService;
import com.miniagent.agent.song.SongGenerationService;
import com.miniagent.agent.security.GuardedHttpClient;
import com.miniagent.agent.security.NetworkGuard;
import com.miniagent.common.SecurityUtils;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.lang3.StringUtils;

// 新增参数类导入
import com.miniagent.agent.tool.impl.*;

/**
 * 内置工具集合：文件操作、HTTP 请求、命令执行、浏览器操控
 *
 * 类似 hermes-agent 的工具系统，每个工具由 name + description + parameters + handler 组成。
 */
@Slf4j
@Component
public class BuiltinTools {

    private static final String BROWSER_EVALUATE = "browser_evaluate";

    @Autowired
    private  ToolRegistry registry;
    @Autowired
    private  BrowserService browserService;
    @Autowired
    private  WebSearchService webSearchService;
    /**
     * 漏掉 {@code @Autowired} 时字段为 null，{@code skill_list} / {@code skill_view} /
     * {@code skill_manage} 三个工具注册照常成功、调用才 NPE —— 症状是「模型说工具调用出错」，
     * 而工具清单里明明有它。改动本类字段时逐个确认注解在位。
     */
    @Autowired
    private  SkillStore skillStore;
    @Autowired
    private  ImageGenerationService imageGenerationService;
    @Autowired
    private  SongGenerationService songGenerationService;
    @Autowired
    private  MiniMaxMusicService miniMaxMusicService;
    @Autowired
    private  LyriaMusicService lyriaMusicService;
    @Autowired
    private  NetworkGuard networkGuard;

    /**
     * 出网统一走这个客户端：它手动处理重定向，**每一跳**都重新过 {@link NetworkGuard}，
     * 且限制响应体大小、跨域剥离凭证、拒绝 HTTPS→HTTP 降级。
     *
     * <p>不要再新建裸 {@code HttpClient + Redirect.NORMAL}：那样只校验第一个 URL，
     * 攻击者用一个 302 就能把请求打到内网或云元数据端点（169.254.169.254）。</p>
     */
    @Autowired
    private  GuardedHttpClient guardedHttpClient;

    @Value("${agent.tools.allow-absolute-write:false}")
    private boolean allowAbsoluteWrite;
    @Value("${agent.browser.evaluate-enabled:false}")
    private boolean browserEvaluateEnabled;

    /**
     * 工具结果缓存：同一用户消息内，对 read_file / list_files 的重复调用直接返回缓存。
     * Key = "toolName|argumentsJson"，Value = 上次执行结果。
     * 在每次新用户消息处理开始时由 AgentChatApplicationService 调用 clearToolCache() 清空。
     */
    private final ConcurrentHashMap<String, String> toolResultCache =
            new ConcurrentHashMap<>();

    /**
     * 读观察账本：本次用户消息内「模型看过哪个文件的哪个版本」。
     *
     * <h2>要解决的问题</h2>
     *
     * <p>{@code write_file} 的 overwrite 模式此前是<b>无条件覆盖</b>，唯一的拦截是
     * 「用不到一半大小的短文本盖大文件」那条启发式。于是存在一条静默数据损坏路径：
     * 模型 read_file 拿到内容 → 中间人（用户手动编辑、git 操作、其他工具）改了同一个文件
     * → 模型基于旧内容 write_file → <b>别人的改动被无声回滚，没有任何报错</b>。</p>
     *
     * <p>{@link ToolConcurrencyPolicy} 的 {@code WRITE_BY_PATH} 锁解决不了这个：
     * 它防的是「同一时刻两个写并发」，防不了「读了很久之后再写」—— 后者在时间维度上是空的。</p>
     *
     * <h2>为什么按内容指纹而不是按修改时间</h2>
     *
     * <p>Windows 上 {@code lastModifiedTime} 会因为云同步、杀毒软件扫描而变化，但内容没变；
     * 反过来，同一秒内的两次写入时间戳可能完全相同，内容却变了。时间戳只能当快速路径，
     * 判定必须落在内容上。所以这里存 SHA-256，每次比对现算。</p>
     *
     * <h2>为什么 edit_file 不拦</h2>
     *
     * <p>{@code edit_file} 靠 {@code old_string} 逐字符唯一匹配，本身就保证了「被编辑的那段没变」，
     * 一致性强度不比整文件指纹弱。给它加整文件校验反而会误伤这条合法高频路径：
     * <pre>read → edit(第1处) → edit(第2处)</pre>
     * 第一次 edit 已经改了文件，第二次 edit 手上的观察立刻过期，正常连续的增量编辑会全被拒。
     * 所以 edit_file 只<b>更新</b>账本，不做前置校验。</p>
     *
     * <p>key 用规范化后的绝对路径（{@code toRealPath()} 不可用时退回
     * {@code toAbsolutePath().normalize()}）：模型可能用 {@code Foo.java}、
     * {@code ./Foo.java}、{@code workspace/Foo.java}、绝对路径四种写法指向同一个文件，
     * 按入参字符串记账会导致「读过但认不出来」。</p>
     */
    private final ConcurrentHashMap<String, ReadObservation> readObservations =
            new ConcurrentHashMap<>();

    /**
     * 一次读取观察。{@code partial == true} 表示只读了文件的一部分，
     * <b>不能</b>作为整文件覆盖的依据。
     *
     * @param digest SHA-256 十六进制
     */
    private record ReadObservation(String digest, boolean partial) {}

    /** 清空工具结果缓存（每条新用户消息开始时调用） */
    public void clearToolCache() {
        toolResultCache.clear();
        // 读观察随缓存一起作废：新用户消息开始时，上一轮看到的文件版本不再可信。
        readObservations.clear();
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

    // ==================== 读观察账本 ====================

    /**
     * 账本 key：规范化绝对路径。
     *
     * <p>{@code toRealPath()} 能解开符号链接与 junction（同一个文件的不同写法会归到同一个 key），
     * 但要求文件已存在；写入前文件可能还不存在，此时退回词法规范化。</p>
     */
    private static String observationKey(Path target) {
        try {
            return target.toRealPath().toString();
        } catch (Exception ignored) {
            return target.toAbsolutePath().normalize().toString();
        }
    }

    /** 现算文件内容 SHA-256（十六进制）。文件不存在返回空串。 */
    private static String digestOf(Path target) {
        if (!Files.isRegularFile(target)) {
            return "";
        }
        try (var in = Files.newInputStream(target)) {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            // 算不出来就不记账：宁可退化成「无观察」，也不要拿一个假指纹去误拦正确写入。
            log.debug("计算内容指纹失败: {}", target, e);
            return "";
        }
    }

    /** 登记一次读取观察。{@code partial} 只读了一部分时置 true。 */
    private void rememberRead(Path target, boolean partial) {
        String digest = digestOf(target);
        if (digest.isEmpty()) {
            return;
        }
        readObservations.put(observationKey(target), new ReadObservation(digest, partial));
    }

    /**
     * 写入成功后把账本更新成「写入后的内容」。
     *
     * <p>必须更新而不是删除：删除会让紧随其后的第二次写入变成「无观察」而被拒；
     * 更新成新指纹才能让 {@code write → write}、{@code write → edit} 这类连续操作畅通。</p>
     */
    private void rememberWrite(Path target) {
        String digest = digestOf(target);
        if (digest.isEmpty()) {
            return;
        }
        readObservations.put(observationKey(target), new ReadObservation(digest, false));
    }

    /**
     * 整文件覆盖前的准入判定。
     *
     * @return {@code null} 表示允许覆盖；否则为可直接返回给模型的 CONFLICT JSON
     */
    private String checkOverwriteAllowed(Path target, String path, boolean force) {
        if (force) {
            return null;
        }
        ReadObservation observed = readObservations.get(observationKey(target));
        if (observed == null) {
            return errorJson(ToolErrorCode.CONFLICT,
                    "拒绝覆盖未读取过的文件 " + path + "（该文件已存在）。"
                            + "先 read_file 看清现有内容再决定怎么写；"
                            + "如果确实要用当前内容整体替换，传 force=true。");
        }
        if (observed.partial()) {
            return errorJson(ToolErrorCode.CONFLICT,
                    "拒绝覆盖 " + path + "：上次只读取了它的一部分，不足以判断整体替换会丢掉什么。"
                            + "请完整读一遍（不传 offset/limit），或传 force=true 明确承担覆盖风险。");
        }
        String current = digestOf(target);
        if (!current.isEmpty() && !current.equals(observed.digest())) {
            return errorJson(ToolErrorCode.CONFLICT,
                    "拒绝覆盖 " + path + "：它在被读取之后已被修改（内容指纹不一致），"
                            + "直接写回会静默回滚这些改动。请重新 read_file 拿到最新内容后重写；"
                            + "若确认要用当前内容覆盖，传 force=true。");
        }
        return null;
    }


    @PostConstruct
    public void register() {
        registerFileTools();
        registerSearchAndEditTools();
        registerHttpTools();
        registerWebSearchTools();
        registerExecTool();
        registerBrowserTools();
        registerSkillTools();
        registerImageGenerateTool();
        registerSongGenerateTool();
        registerMiniMaxMusicTool();
        registerLyriaMusicTool();
    }


    // ==================== 文件工具 ====================

    private void registerFileTools() {
        // 使用新的实体类注册方式
        registry.register("read_file", "读取文件内容",
                ReadFileParams.class,
                params -> {
                    // 缓存命中检查
                    String cacheKey = "read_file|" + params.toJson();
                    String cached = toolResultCache.get(cacheKey);
                    if (Objects.nonNull(cached)) {
                        log.debug("read_file 缓存命中: {}", cacheKey);
                        return cached;
                    }
                    String result = readFile(params.getPath(), 
                                          params.getOffsetOrDefault(), 
                                          params.getLimitOrDefault());
                    toolResultCache.put(cacheKey, result);
                    return result;
                });

        registry.register("write_file",
                "写入文件内容。新建文件用本工具；修改已存在的文件请改用 edit_file，不要用本工具重写整个文件。\n"
                        + "路径写文件名即可，落在 workspace 根下，如 design.md。"
                        + "子任务隔离目录仅在子 Agent 覆盖 workspace 时使用。\n"
                        + "【覆盖前会校验】overwrite 一个已存在的文件时，工具会核对它现在的内容是否仍等于你读到的版本。"
                        + "没读过、只读了片段、或读过之后被别人改过，都会被拒绝 —— 因为写回旧内容会静默回滚那些改动，且不会有任何报错。"
                        + "被拒绝时的正确做法是重新 read_file 拿最新内容；只有用户明确要求整体覆盖才传 force=true。\n"
                        + "返回值里若带 url 字段（图片类：svg/png/jpg/gif/webp），说明该图可以显示在对话里 —— "
                        + "回复时请原样用 ![说明](url)，不要改写成相对路径或本地绝对路径，"
                        + "工作区没有静态资源映射，只有这个 url 能加载。\n"
                        + "【大文件必读】单轮输出有长度上限，超长文件（如完整的 3D 仿真 HTML、长代码）一次写不完会被截断。"
                        + "正确做法：第一次用 mode=\"overwrite\"（默认）写开头部分，之后多次用 mode=\"append\" 把剩余内容续写到同一文件，直到写完。不要试图一次塞进全部内容。",
                WriteFileParams.class,
                params -> {
                    if (Objects.isNull(params.getContent())) {
                        return "{\"error\":\"缺少 content 参数（或参数被截断）。大文件请分块写入：首次 mode=overwrite，后续 mode=append。\"}";
                    }
                    return writeFile(params.getPath(), params.getContent(),
                            params.isAppendMode(), params.isForce());
                });

        registry.register("list_files", "列出目录文件",
                ListFilesParams.class,
                params -> listFiles(params.getPath(), params.isRecursiveOrDefault()));

        registry.register("read_package", "按Java包名读取包下所有源码文件。传包名如 com.miniagent.agent.task，自动解析路径并返回全部代码。分析项目架构时优先用这个，不要逐个 read_file。",
                ReadPackageParams.class,
                params -> readPackage(params.getPackageName(), params.getMaxCharsPerFileOrDefault()));
    }

    // ==================== HTTP 工具 ====================

    private void registerHttpTools() {
        registry.register("http_get", "发送 HTTP GET 请求并返回响应体",
                HttpGetParams.class,
                params -> httpSend("GET", params.getUrl(), null, null));
        registry.register("http_post",
                "发送 HTTP POST。写接口（如微信草稿箱 draft/add）用这个；只读用 http_get。"
                        + "首次调用会请用户批准。",
                HttpPostParams.class,
                params -> httpSend("POST", params.getUrl(), params.getBody(), params.getContentTypeOrDefault()));
    }

    // ==================== Web 搜索工具（对标 hermes-agent） ====================

    private void registerWebSearchTools() {
        registry.register("web_search",
                "搜索网页，返回结构化结果以及搜索服务提供的发布日期线索。查询涉及今天/今日/昨天或明确日期时，必须按上下文时区换算并传 dateFrom/dateTo（YYYY-MM-DD）；服务会加入日期限定并剔除已知超窗结果。搜索发布日期尚未经过原文核验；所有新闻候选都必须用 web_extract 核验原文发布日期后才能引用。\n"
                        + "搜索用简洁关键词，不用完整句子（用 \"Spring Boot 事务失效\" 而不是 \"我想知道 Spring Boot 里事务为什么会失效\"）。\n"
                        + "对结果准确性不确定时，用 web_extract 提取原文确认，不要直接拿搜索摘要下结论。",
                WebSearchParams.class,
                params -> webSearchService.search(params.getQuery(), params.getLimitOrDefault(),
                        params.getDateFrom(), params.getDateTo()));

        registry.register("web_extract", "抓取网页原文，返回正文及页面可识别的发布日期。用它核验新闻发布日期；date_verified=true 仅表示原文页面元数据中找到发布日期。对于大页面会截断到合理长度。",
                WebExtractParams.class,
                params -> webSearchService.extract(params.getUrl()));
    }

    // ==================== 代码检索 + 精确编辑（Cursor 风格） ====================

    private void registerSearchAndEditTools() {
        // search_code：ripgrep 优先，Java 正则兜底。定位代码/符号的首选，取代逐个 read_file 盲找。
        registry.register("search_code",
                "在代码/文本文件中按正则搜索内容，返回 文件:行号:匹配行。定位函数、类、符号、关键字时优先用这个，不要逐个 read_file 盲找。" +
                "path 默认项目根目录；glob 可限定文件类型如 *.java。",
                SearchParams.class,
                params -> {
                    if (StringUtils.isBlank(params.getPattern())) {
                        return "{\"error\":\"pattern 不能为空\"}";
                    }
                    return searchCode(params.getPattern(), 
                                    params.getPath(), 
                                    params.getGlob(), 
                                    params.getMaxResultsOrDefault());
                });

        // edit_file：锚点替换，改已有文件只传 diff，不重发整文件。
        registry.register("edit_file",
                "精确编辑已有文件：把 oldString 替换为 newString。修改已存在的文件用这个，不要用 write_file 重写整个文件。" +
                "oldString 必须与文件中的原文逐字符一致（含缩进），且默认必须唯一匹配——不唯一时请在 oldString 里多带几行上下文。删除内容时 newString 传空字符串。",
                EditFileParams.class,
                params -> editFile(params.getPath(), 
                                 params.getOldString(), 
                                 params.getNewString() != null ? params.getNewString() : "", 
                                 params.isReplaceAllOrDefault()));
    }

    /** search_code 实现：优先 ripgrep，失败回退 Java 正则扫描。 */
    private String searchCode(String pattern, String path, String glob, int maxResults) {
        Path root = (StringUtils.isBlank(path))
                ? Path.of(System.getProperty("user.dir")).toAbsolutePath()
                : resolveSearchPath(path);
        if (!Files.exists(root)) {
            return errorJson(ToolErrorCode.NOT_FOUND, "路径不存在: " + path);
        }

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
        if (Objects.nonNull(cachedRgPath)) {
            return cachedRgPath.isEmpty() ? null : cachedRgPath;
        }
        synchronized (BuiltinTools.class) {
            if (Objects.nonNull(cachedRgPath)) {
                return cachedRgPath.isEmpty() ? null : cachedRgPath;
            }
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
            if (proc.exitValue() == 2) {
                return null;
            }
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
        if (count[0] == 0) {
            return "（无匹配）pattern=" + pattern;
        }
        return "匹配 " + count[0] + " 处（Java 正则扫描" + (count[0] >= maxResults ? "，已达上限" : "") + "）:\n" + sb;
    }

    private String formatSearchOutput(String raw, Path root, int maxResults, String engine) {
        if (StringUtils.isBlank(raw)) {
            return "（无匹配）";
        }
        String[] lines = raw.split("\n");
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String line : lines) {
            if (StringUtils.isBlank(line)) {
                continue;
            }
            if (n >= maxResults) {
                break;
            }
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
        if (n == 0) {
            return "（无匹配）";
        }
        return "匹配 " + n + " 处（" + engine + (n >= maxResults ? "，已达上限" : "") + "）:\n" + sb;
    }

    /** edit_file 实现：校验唯一匹配后替换。 */
    private String editFile(String path, String oldStr, String newStr, boolean replaceAll) {
        if (StringUtils.isBlank(path)) {
            return "{\"error\":\"path 不能为空\"}";
        }
        if (Objects.isNull(oldStr) || oldStr.isEmpty()) {
            return "{\"error\":\"old_string 不能为空（新建文件请用 write_file）\"}";
        }
        if (oldStr.equals(newStr)) {
            return "{\"error\":\"old_string 与 new_string 相同，无需编辑\"}";
        }
        try {
            Path target = resolveSearchPath(path);
            if (!Files.exists(target)) {
                return errorJson(ToolErrorCode.NOT_FOUND, "文件不存在: " + path + "（新建文件用 write_file）");
            }
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
            // 更新（不是清空）读观察：让 read → edit → edit 这类连续增量编辑继续放行。
            rememberWrite(target);
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
     * 搜索/编辑专用路径解析。
     *
     * <p>与 write_file 不同，这里不要求落在 workspace（编码任务要操作真实项目源码），
     * 但也**不是无约束**：结果必须落在 {@link PathGuard} 允许的根内。
     * 此前这里是"绝对路径直接用"，等于把 {@code edit_file} 变成任意文件改写 ——
     * 写一行到 {@code ~/.ssh/authorized_keys} 就是主机长期驻留。</p>
     */
    private Path resolveSearchPath(String path) {
        String normalized = path.replace('\\', '/').trim();
        if (normalized.equalsIgnoreCase("workspace")
                || normalized.regionMatches(true, 0, "workspace/", 0, 10))
            return resolveWorkspacePath(path);
        Path p = Path.of(normalized);
        if (p.isAbsolute()) {
            return requireAllowed(p, "edit/search");
        }
        Path fromRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath()
                .resolve(normalized).normalize();
        if (Files.exists(fromRoot)) {
            return requireAllowed(fromRoot, "edit/search");
        }
        // 项目根下不存在就退回 workspace：agent 自己 write_file 产出的文件落在
        // workspace 根，否则 write → edit 这条链路会断在「文件不存在」。
        Path fromWorkspace = resolveWorkspacePath(path);
        return Files.exists(fromWorkspace) ? fromWorkspace : requireAllowed(fromRoot, "edit/search");
    }

    /** 统一的越界拒绝点：所有读/改/写路径都要经过它。 */
    private static Path requireAllowed(Path candidate, String purpose) {
        PathGuard.assertAllowed(candidate, purpose);
        return candidate;
    }

    /** 把 glob（*.java、*.{ts,tsx}）转成文件名正则；null/空返回 null（不过滤）。 */
    private static java.util.regex.Pattern globToRegex(String glob) {
        if (StringUtils.isBlank(glob)) {
            return null;
        }
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
        if (dot < 0) {
            return false;
        }
        String ext = n.substring(dot + 1);
        return Set.of("class","jar","war","zip","gz","tar","png","jpg","jpeg","gif","ico","pdf",
                "exe","dll","so","bin","mp4","mp3","woff","woff2","ttf","eot","lock").contains(ext);
    }

    private void registerExecTool() {
        // 这里刻意不再读 agent.tools.exec-enabled / exec-policy 来打"免批还是需批"的日志：
        // 策略的真正裁决点是 ExecPolicyService（mini-agent-planner），
        // 它已经在自己启动时打过 "exec_command 策略: 全局默认 = ..."。
        // 在 tools 模块再解析一遍配置就会出现两份解析逻辑，正是漂移的来源。
        // 而且 tools 依赖 common、不依赖 loop（反向依赖会成环），本来就拿不到那个服务。
        log.info("exec_command 已注册；执行策略由 ExecPolicyService 裁决（见其启动日志）");
        // 这里刻意用完整 builder 而不是便捷注册：exec_command 需要挂「按参数算超时」的函数，
        // 而便捷注册只能给一个注册期常量（git status 与 mvnw package 不该共用同一个预算）。
        registry.register(Tool.builder()
                .name("exec_command")
                .description("在 workspace 目录下执行一条 shell 命令并返回输出；当前是 Windows，用 cmd / PowerShell 语法。\n"
                        + "什么时候用：跑构建/测试/脚本、调用命令行工具、验证刚生成的产出物能否真正运行。\n"
                        + "什么时候不要用（改用专用工具，更快也更安全）：\n"
                        + "- 读文件 → read_file；写文件 → write_file；列目录 → list_files\n"
                        + "- 搜代码 → search_code（不要用 findstr / Select-String 全库扫）\n"
                        + "- 抓网页 → web_extract；请求接口 → http_get / http_post\n"
                        + "慢命令必须显式声明超时：默认 " + ExecCommandParams.DEFAULT_TIMEOUT_SECONDS
                        + " 秒，上限 " + ExecCommandParams.MAX_TIMEOUT_SECONDS
                        + " 秒（如 mvnw/npm 构建、跑测试、装依赖，请把 timeout 传到 300 以上）。\n"
                        + "命令返回非 0 退出码不一定是失败：grep/findstr 的 1 表示没匹配到，"
                        + "这类结果会带 [exit-note] 提示，不要重试同一条命令。\n"
                        + "输出过长时会落盘到 workspace/_tool-output/ 并在结果里给出路径，"
                        + "需要完整内容用 read_file 读那个文件，不要靠重跑命令看输出。\n"
                        + "危险操作（递归删除、覆盖已有文件、格式化磁盘、git 强制推送或重置）执行前必须先向用户确认。\n"
                        + "不要跑交互式命令（会挂到超时）；需要确认时改用非交互参数（如 -y / --yes）。\n"
                        + "全局未开启时首次调用会请用户批准。")
                .parameters(ToolParams.generateSchema(ExecCommandParams.class))
                .sideEffect(ToolConcurrencyPolicy.sideEffectOf("exec_command"))
                .idempotent(ToolConcurrencyPolicy.isIdempotent("exec_command"))
                .streamPrefetchSafe(ToolConcurrencyPolicy.isStreamPrefetchSafe("exec_command"))
                .executionProfile(ToolConcurrencyPolicy.profileOf("exec_command"))
                .adaptiveTimeoutSeconds(ExecCommandParams::outerGateSecondsOf)
                .handler(json -> execCommand(ToolParams.fromJson(json, ExecCommandParams.class)))
                .build());
    }

    // ==================== 浏览器工具 ====================

    private void registerBrowserTools() {
        registry.register("browser_navigate",
                "打开公网 HTTP/HTTPS 网页并返回页面标题和无障碍树快照。不要用它预览本地文件、file://、localhost 或本机服务器；"
                        + "工作区生成的 HTML 请用 read_file 或 exec_command 做静态检查，不要更换端口反复尝试。",
                BrowserNavigateParams.class,
                params -> {
                    String blocked = networkGuard.validateUrl(params.getUrl());
                    if (Objects.nonNull(blocked)) {
                        return errorJson(ToolErrorCode.PERMISSION_DENIED,
                                "浏览器导航被安全策略拒绝：" + blocked
                                        + "。此工具仅用于公网 HTTP/HTTPS 网页，不能预览 file://、localhost 或工作区本地文件；"
                                        + "请停止更换端口重试，改用 read_file 或 exec_command 做静态验证。");
                    }
                    return withBrowserSession(params.getSessionId(), session -> browserService.navigate(session, params.getUrl()));
                });

        registry.register("browser_snapshot", "获取当前页面的无障碍树快照与观察标识。使用返回的完整 ref（如 b12.3）；页面改变后旧 ref 失效。",
                BrowserSnapshotParams.class,
                params -> withBrowserSession(params.getSessionId(), session -> browserService.snapshot(session, params.isFullOrDefault())));

        registry.register("browser_form_inspect", "识别当前网页表单：返回字段ref、类型、标签、必填、约束、选项、当前值和校验错误；包含隐藏上传控件，密码值不回显。每次填写后重新识别获取最新ref。",
                BrowserFormInspectParams.class,
                params -> withBrowserSession(params.getSessionId(), browserService::formInspect));
        registry.register("browser_form_verify", "回读一个最新字段ref，检查实际值及页面约束。可传一种expected参数核对文本、勾选、选中value或文件名；核验通过不代表已提交。",
                BrowserFormVerifyParams.class,
                params -> withBrowserSession(params.getSessionId(), session -> browserService.formVerify(session,
                        params.getRef(), params.getExpectedValue(), params.getExpectedChecked(), params.getExpectedValues(), params.getExpectedFiles())));
        registry.register("browser_select", "选择原生select下拉框的指定选项，支持多选；只用最新ref。values传value或by=label传精确显示名称。返回实际选值及新快照；自定义下拉使用快照click/press。",
                BrowserSelectParams.class,
                params -> withBrowserSession(params.getSessionId(), session -> browserService.select(session,
                        params.getRef(), params.getValues(), params.getByOrDefault())));
        registry.register("browser_set_checked", "将原生复选框或单选框设为明确的checked状态，不进行盲目翻转；使用最新ref。单选框不能直接取消，应选择同组其他项。",
                BrowserSetCheckedParams.class,
                params -> params.getChecked() == null ? errorJson(ToolErrorCode.INVALID_ARGUMENT, "checked必须明确为true或false")
                        : withBrowserSession(params.getSessionId(), session -> browserService.setChecked(session, params.getRef(), params.getChecked())));
        registry.register("browser_upload", "给表单文件字段上传附件，会向网站传输文件。使用browser_form_inspect返回的file字段ref；paths仅限当前会话工作区或本人已上传附件，最多10个/总50MiB。操作前须在用户授权范围内。",
                BrowserUploadParams.class,
                params -> withBrowserSession(params.getSessionId(), session -> {
                    try {
                        String dataDir = System.getProperty("miniagent.data.dir");
                        Long user = OwnerContext.currentUserId();
                        Path uploads = StringUtils.isBlank(dataDir) || user == null ? null : Path.of(dataDir, "media", "uploads", user.toString());
                        return browserService.upload(session, params.getRef(), BrowserUploadFiles.resolve(effectiveWorkspaceRoot(), uploads, params.getPaths()));
                    } catch (SecurityException denied) {
                        return errorJson(ToolErrorCode.PERMISSION_DENIED, denied.getMessage());
                    } catch (IllegalArgumentException invalid) {
                        return errorJson(ToolErrorCode.INVALID_ARGUMENT, invalid.getMessage());
                    } catch (java.io.IOException missing) {
                        return errorJson(ToolErrorCode.NOT_FOUND, "上传文件不存在或无法读取");
                    }
                }));

        registry.register("browser_click",
                "点击页面元素。默认使用最新快照完整 ref（如 b12.3），不能只传数字。STALE_OBSERVATION 时重新获取快照。"
                        + "也可明确选择 text=精确文本、role=button=名称、css=唯一选择器、aria=唯一标签。禁止结果未知后直接重试。",
                BrowserClickParams.class,
                params -> withBrowserSession(params.getSessionId(), session -> browserService.click(session,
                                            params.getRef(), 
                                            params.getByOrDefault())));

        registry.register("browser_type", "在输入框中输入文字",
                BrowserTypeParams.class,
                params -> withBrowserSession(params.getSessionId(), session -> browserService.type(session,
                                           params.getRef(), 
                                           params.getText())));

        registry.register("browser_press", "按下键盘按键（Enter, Tab, Escape, ArrowDown 等）",
                BrowserPressParams.class,
                params -> withBrowserSession(params.getSessionId(), session -> browserService.press(session, params.getKey())));

        registry.register("browser_scroll", "滚动页面",
                BrowserScrollParams.class,
                params -> withBrowserSession(params.getSessionId(), session -> browserService.scroll(session, params.getDirection())));

        registry.register("browser_screenshot", "截取当前页面截图，在最近点击位置添加蓝色光圈指针，并保存到 workspace 供对话预览",
                BrowserScreenshotParams.class,
                params -> withBrowserSession(params.getSessionId(), session -> browserService.screenshot(session, effectiveWorkspaceRoot())));

        if (browserEvaluateEnabled) {
            registry.register(BROWSER_EVALUATE, "在页面中执行 JavaScript 代码",
                    BrowserEvaluateParams.class,
                    params -> withBrowserSession(params.getSessionId(), session -> browserService.evaluate(
                            session, params.getScript())));
        }

        registry.register("browser_extract_text",
                "抽取当前页正文并写入文件。飞书/wiki 有侧栏目录时会按目录逐章抽取，"
                        + "一次调用覆盖全部章节。代码块写成 Markdown 围栏，图片保存到"
                        + "同目录 images/。禁止 browser_evaluate 手动滚屏。",
                BrowserExtractTextParams.class,
                params -> withBrowserSession(params.getSessionId(), session -> {
                    String path = params.getPath();
                    if (StringUtils.isBlank(path)) {
                        path = "_source.md";
                    }
                    boolean append = "append".equalsIgnoreCase(params.getMode());
                    Path mdFile = resolveWorkspacePath(path);
                    Path mdParent = mdFile.getParent();
                    Path imgDir = (mdParent == null ? mdFile : mdParent)
                            .resolve(EXTRACT_IMAGES_DIR);
                    String text = browserService.extractArticleText(session, imgDir);
                    if (text.startsWith("抽取失败")) {
                        return text;
                    }
                    String chunk = append ? "\n\n" + text : text;
                    // 工具自有产物（默认 _source.md / 内部镜像 notes.md）跳过一致性校验：
                    // 内容是刚抽取出来的，不存在「写回陈旧内容」这回事，重抽同页要能直接覆盖。
                    // 模型显式指定的其他路径仍走校验 —— 那里确实可能覆盖到用户的项目文件。
                    boolean toolOwnedArtifact = StringUtils.isBlank(params.getPath())
                            || pathFileName(path).equalsIgnoreCase("_source.md");
                    String written = writeFile(path, chunk, append, toolOwnedArtifact);
                    if (!append && pathFileName(path).equalsIgnoreCase("_source.md")) {
                        writeFile("notes.md", chunk, false, true);
                    }
                    return written;
                }));

        registry.register("browser_close", "关闭浏览器会话",
                BrowserCloseParams.class,
                params -> withBrowserSession(params.getSessionId(), browserService::close));
    }

    private String withBrowserSession(String requestedSession, java.util.function.Function<String, String> operation) {
        try {
            return operation.apply(BrowserSessionIdentity.resolve(requestedSession));
        } catch (SecurityException denied) {
            return errorJson(ToolErrorCode.PERMISSION_DENIED, denied.getMessage());
        }
    }

    // ==================== 云端图像生成（多后端自动降级） ====================

    private void registerImageGenerateTool() {
        registry.register(Tool.builder()
                .name("image_generate")
                .description("生成图片（云端多后端自动降级：ChatAnywhere/MiMo/FAL/SiliconFlow/智谱CogView）。\n" +
                        "适合快速生成概念图、插画等。英文 prompt 效果最好，中文也可以用。\n" +
                        "这是默认出图入口：架构图/流程图也先走它。后端全部失败时工具会被移除，\n" +
                        "届时架构图/流程图改走 render_diagram，插画类如实告知用户。\n" +
                        "后端由工具自动选择，无需关心细节；工具直接返回可渲染的图片链接（Markdown 格式）。\n" +
                        "用户只是要看图时：原样输出图片链接，不要额外解释、不要说\"图片已生成\"。\n" +
                        "用户要求把图写进文档时：先用 todo 拆成「生图 → 定位文档 → 写入图片链接」，\n" +
                        "在主循环里串行执行 image_generate 再写文件；不要派子 Agent，也不要用 ASCII 图凑数。")
                .parameters(Map.of(
                        "prompt", Map.of("type", "string", "description", "图片描述（英文效果最好，尽量详细描述画面内容、风格、光影）", "required", true),
                        "aspect_ratio", Map.of("type", "string", "description", "比例: landscape(横版) / square(方形) / portrait(竖版)，默认 landscape")
                ))
                .sideEffect(ToolConcurrencyPolicy.sideEffectOf("image_generate"))
                .idempotent(ToolConcurrencyPolicy.isIdempotent("image_generate"))
                .streamPrefetchSafe(ToolConcurrencyPolicy.isStreamPrefetchSafe("image_generate"))
                // 内层预算取配置（image.gen.total-budget-seconds，工具到点自己强杀并返回可控终态），
                // 外层闸门 = 预算 + REMOTE_GENERATION 档的 30s 余量。注册期从实际配置派生，
                // 配置调大时闸门自动跟着走 —— 「外层 = 内层 + 余量」不变式由此机械成立，
                // 不会再出现内层 500s / 外层 150s 的倒挂。
                .executionProfile(ToolConcurrencyPolicy.profileOf("image_generate")
                        .withBudget(imageGenerationService.totalBudgetSeconds()))
                .handler(args -> {
                    Map<String, Object> p = parseJson(args);
                    String prompt = (String) p.get("prompt");
                    String ratio = (String) p.getOrDefault("aspect_ratio", "landscape");
                    return imageGenerationService.generate(prompt, ratio);
                })
                .build());
    }

    // ==================== 云端生歌（SenseAudio） ====================

    /**
     * 生歌工具：整首歌由云端产出，本地只负责提交、续查、下载落盘。
     *
     * <p>超时闸门在 {@code ToolConcurrencyPolicy} 里按 {@link SongGenerateParams#outerGateSeconds()}
     * 登记。这个数字必须比工具内部的轮询预算宽 —— 外层先触发会被判成「终态未知」并中止整轮。
     */
    private void registerSongGenerateTool() {
        registry.register(SongGenerateParams.TOOL_NAME,
                "创作一整首歌（作词 + 作曲 + 演唱），走 SenseAudio 云端，约需 3 分钟。\n" +
                "不需要本地算力。成功后返回可直接播放的音频链接（Markdown 格式）；" +
                "音频已由工具下载到本地，不要自己再去下载或转存。\n" +
                "用户只是要听歌时：原样输出音频链接，不要额外解释、不要说「歌曲已生成」。\n" +
                "若返回 status=PENDING（歌还在生成）：先告诉用户还在生成中，" +
                "下一轮再调一次 song_generate 并把 taskId 原样传回来续查，不要重复提交。",
                SongGenerateParams.class,
                songGenerationService::generate);
    }

    private void registerMiniMaxMusicTool() {
        registry.register(MiniMaxMusicParams.TOOL_NAME,
                "用 MiniMax Music 生成一首带人声（或纯音乐）的歌，同步接口，约 1～3 分钟。\n" +
                "SenseAudio 的 song_generate 人声失败时改用本工具。\n" +
                "有歌词就传 lyrics；没有歌词只传 prompt，服务会先写词再作曲。\n" +
                "成功后返回可播放的 Markdown 音频链接，不要自己再下载。",
                MiniMaxMusicParams.class,
                miniMaxMusicService::generate);
    }

    private void registerLyriaMusicTool() {
        registry.register(LyriaMusicParams.TOOL_NAME,
                "用 Google Lyria 3.5 生成一首带人声（或纯音乐）的完整歌曲，同步接口，约 2～4 分钟。\n" +
                "SenseAudio 的 song_generate 人声失败时改用本工具。需要 GEMINI_API_KEY。\n" +
                "prompt 必填，可在文案里写时长；有歌词就传 lyrics。\n" +
                "成功后返回可播放的 Markdown 音频链接，不要自己再下载。",
                LyriaMusicParams.class,
                lyriaMusicService::generate);
    }


    private void registerSkillTools() {
        // skill_list - 列出所有已安装的 skill
        registry.registerStructured("skill_list",
                "List installed skills with name and description. Use this first to see what skills are available.",
                Map.of(),
                args -> {
                    String summary = skillStore.getSkillListSummary();
                    return ToolResult.success(summary.isEmpty() ? "No skills installed." : summary);
                });

        // skill_view - 加载 skill 完整内容
        registry.registerStructured("skill_view",
                "Load full content of a skill by name. Optionally load a sub-file (references, templates, scripts).",
                Map.of(
                        "name", Map.of("type", "string", "description", "Skill name", "required", true),
                        "file_path", Map.of("type", "string", "description", "Optional sub-file path like references/api.md")
                ),
                args -> {
                    Map<String, Object> p = parseJson(args);
                    String name = p.get("name") instanceof String value ? value.trim() : "";
                    String filePath = p.get("file_path") instanceof String value ? value.trim() : "";
                    if (name.isEmpty()) {
                        return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENT, "name is required", false);
                    }
                    if (!filePath.isEmpty()) {
                        SkillStore.SkillFileResult result = skillStore.readSkillFile(name, filePath);
                        return switch (result.status()) {
                            case FOUND -> ToolResult.success(result.content());
                            case PATH_REJECTED -> ToolResult.failure(ToolErrorCode.PERMISSION_DENIED,
                                    "Skill file path is outside the skill directory", false);
                            case NOT_FOUND -> ToolResult.failure(ToolErrorCode.NOT_FOUND,
                                    "File not found: " + name + "/" + filePath, false);
                            case READ_FAILED -> ToolResult.failure(ToolErrorCode.INTERNAL_ERROR,
                                    "Unable to read skill file: " + name + "/" + filePath, false);
                        };
                    }
                    String content = skillStore.viewSkill(name);
                    return content == null
                            ? ToolResult.failure(ToolErrorCode.NOT_FOUND, "Skill not found: " + name, false)
                            : ToolResult.success(content);
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
                    if (Objects.isNull(action) || Objects.isNull(name)) {
                        return "{\"error\":\"action and name are required\"}";
                    }
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

    /**
     * {@code exec_command} 的工作目录。
     *
     * <p>必须是"生效工作区"而不是默认工作区：会话绑定项目目录、子 Agent 隔离目录都通过
     * {@link WorkspaceContext#getRootOverride()} 生效，写死默认值会让命令跑在别处 ——
     * 文件写进项目、构建产物落在数据目录，且子 Agent 能在父工作区里互相踩（审计 CMD-05）。</p>
     *
     * <p>目录不存在时建出来：绑定目录在校验阶段已存在，但子 Agent 目录可能还没创建。</p>
     */
    static File execWorkingDirectory() {
        File dir = effectiveWorkspaceRoot().toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /** 为子 Agent 准备独立写出目录：workspace/_sub/{safeId}/ */
    public static Path prepareSubagentWorkspace(String subSessionId) {
        String safe = Objects.isNull(subSessionId) ? "sub" : subSessionId.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (safe.length() > 80) {
            safe = safe.substring(0, 80);
        }
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
        if (slug.length() > 40) {
            slug = slug.substring(0, 40);
        }
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

    private static final int READ_LINE_CAP = 4000;

    private String readFile(String path, int offset, int limit) {
        try {
            Path target = resolveReadPath(path);
            if (!Files.exists(target)) {
                return errorJson(ToolErrorCode.NOT_FOUND, "文件不存在: " + path);
            }
            int from = Math.max(1, offset);
            int maxLines = Math.max(0, limit);
            StringBuilder sb = new StringBuilder();
            int lineNo = 0;
            int written = 0;
            boolean hitLimit = false;
            try (var reader = Files.newBufferedReader(target, StandardCharsets.UTF_8)) {
                String line;
                while ((line = readLineCapped(reader, READ_LINE_CAP)) != null) {
                    lineNo++;
                    if (lineNo < from) {
                        continue;
                    }
                    if (written >= maxLines) {
                        // 行数上限截断：文件后面还有内容没读，这条观察不能当整文件依据。
                        hitLimit = true;
                        break;
                    }
                    sb.append(lineNo).append('|').append(line).append('\n');
                    written++;
                }
            }
            // 登记读观察：只有「从头读完、未被行数截断」才算整文件观察。
            rememberRead(target, from > 1 || hitLimit);
            if (written == 0 && lineNo > 0 && from > lineNo) {
                return "{\"error\":\"起始行 " + offset + " 超出文件末尾，该文件共 "
                        + lineNo + " 行\"}";
            }
            if (written == 0 && lineNo == 0) {
                return "";
            }
            return sb.toString();
        } catch (Exception e) {
            return "{\"error\":\"读取文件失败: " + e.getMessage() + "\"}";
        }
    }

    /** 单行超过上限就截断并丢弃本行剩余字符，避免无换行的大文件把整文件读进内存。 */
    private static String readLineCapped(java.io.BufferedReader reader, int cap) throws java.io.IOException {
        int ch = reader.read();
        if (ch < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        boolean capped = false;
        while (ch >= 0 && ch != '\n') {
            if (ch != '\r' && sb.length() < cap) {
                sb.append((char) ch);
            } else if (ch != '\r') {
                capped = true;
            }
            ch = reader.read();
        }
        if (capped) {
            sb.append('…');
        }
        return sb.toString();
    }

    private String readPackage(String packageName, int maxChars) {
        try {
            // 包名转路径：com.miniagent.agent.tool → src/main/java/com/miniagent/agent/tool

            String relativePath = packageName.replace('.', File.separatorChar);
            // 从项目根目录查找 src/main/java 下的包路径
            Path projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath();
            Path pkgDir = projectRoot.resolve("src").resolve("main").resolve("java").resolve(relativePath);

            // 如果当前目录找不到，向上一级查找（适配多模块项目）
            if (!Files.isDirectory(pkgDir)) {
                Path alt = projectRoot.resolve("mini-agent-app").resolve("src").resolve("main")
                        .resolve("java").resolve(relativePath);
                if (Files.isDirectory(alt)) {
                    pkgDir = alt;
                }
            }
            if (!Files.isDirectory(pkgDir)) {
                // 尝试直接在项目根目录下搜索
                try (var stream = Files.walk(projectRoot, 6)) {
                    Optional<Path> found = stream
                            .filter(Files::isDirectory)
                            .filter(p -> p.endsWith(relativePath.replace('/', File.separatorChar)))
                            .findFirst();
                    if (found.isPresent()) {
                        pkgDir = found.get();
                    }
                }
            }

            if (!Files.isDirectory(pkgDir)) {
                return errorJson(ToolErrorCode.NOT_FOUND, "包不存在: " + packageName + "（已尝试解析为 " + pkgDir + "）");
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
        return writeFile(path, content, false, false);
    }

    private String writeFile(String path, String content, boolean append) {
        return writeFile(path, content, append, false);
    }

    private String writeFile(String path, String content, boolean append, boolean force) {
        try {
            // 如果 path 只是文件名（无目录分隔符），默认写到 workspace 根；
            // 子 Agent 通过 WorkspaceContext.taskOverride 隔离。
            Path target = resolveWorkspacePath(path);
            Files.createDirectories(target.getParent());
            // 保留受保护的媒体 URL；不再把旧路径改写成匿名静态资源。
            String finalContent = content;

            if (append) {
                Files.writeString(target, finalContent, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } else if (Files.exists(target)) {
                // 先过一致性闸门：覆盖一个「没读过」或「读过之后又被改过」的文件，
                // 会把别人的改动静默回滚掉。append 不需要（它不丢内容）。
                String refusal = checkOverwriteAllowed(target, path, force);
                if (Objects.nonNull(refusal)) {
                    return refusal;
                }
                long existing = Files.size(target);
                long incoming = finalContent.getBytes(StandardCharsets.UTF_8).length;
                if (existing >= KEEP_LARGE_FILE_MIN_BYTES && incoming * 2 < existing) {
                    return errorJson(ToolErrorCode.CONFLICT,
                            "拒绝用短文本覆盖已有大文件 " + target.getFileName()
                                    + "（现有 " + existing + " 字节，本次 " + incoming
                                    + "）。请改写其他文件名，或用 mode=append。");
                }
                Files.writeString(target, finalContent, StandardCharsets.UTF_8);
            } else {
                Files.writeString(target, finalContent, StandardCharsets.UTF_8);
            }
            invalidateReadCache(path);
            rememberWrite(target);
            long totalBytes = Files.size(target);
            log.info("文件已{}: {} (本次 {} 字符, 当前共 {} 字节)",
                    append ? "追加" : "写入", target.toAbsolutePath(), finalContent.length(), totalBytes);
            String mediaUrl = workspaceMediaUrl(target);
            return "{\"success\":true,\"mode\":\"" + (append ? "append" : "overwrite")
                    + "\",\"path\":\"" + target.toString().replace("\\", "/")
                    + "\",\"bytes_written\":" + finalContent.getBytes(StandardCharsets.UTF_8).length
                    + ",\"total_bytes\":" + totalBytes
                    + (Objects.isNull(mediaUrl) ? "" : ",\"url\":\"" + mediaUrl + "\"")
                    + "}";
        } catch (Exception e) {
            return "{\"error\":\"写入文件失败: " + e.getMessage() + "\"}";
        }
    }

    /** 能在对话里内联回显的图片扩展名；与 {@code /api/workspace-media} 的放行集合一致。 */
    private static final Set<String> INLINE_IMAGE_EXTS = Set.of("svg", "png", "jpg", "jpeg", "gif", "webp");

    /**
     * 直接落在<b>工作区根目录</b>的图片 → 可回显 URL；其余情况返回 null。
     *
     * <h2>为什么工具结果里要塞这个字段</h2>
     *
     * <p>工作区没有静态资源映射，图片只有 {@code /api/workspace-media/{filename}} 这一个受控读端点。
     * 不把 URL 告诉模型，它就会自己拼一个相对路径写进 markdown（实证：
     * {@code write_file -> cyberpunk_city_night.svg}，最终回复里的图片地址既没有路由、前端也不认），
     * 结果就是"文件生成了但对话里什么都没有"。
     *
     * <p>只给根目录下的文件：端点是扁平的，子目录里的文件给了 URL 反而让模型拼错。
     * 文件名做百分号编码 —— 中文文件名（{@code 秋日不打烊-封面.svg}）直接塞进 URL 会被浏览器截断。
     *
     * <p><b>工作区被覆盖时追加 {@code ?sid=}</b>：会话绑定了自己的目录后文件不在
     * {@code {dataDir}/workspace} 下，端点按默认根去找必然 404。带上会话标识，端点才能
     * 按该会话的绑定目录解析。未覆盖工作区时不加参数，URL 形态与既有客户端保持一致。
     */
    static String workspaceMediaUrl(Path target) {
        try {
            Path root = effectiveWorkspaceRoot().toAbsolutePath().normalize();
            Path abs = target.toAbsolutePath().normalize();
            if (!root.equals(abs.getParent())) {
                return null;
            }
            String name = abs.getFileName().toString();
            int dot = name.lastIndexOf('.');
            if (dot <= 0 || !INLINE_IMAGE_EXTS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT))) {
                return null;
            }
            String url = "/api/workspace-media/" + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8)
                    .replace("+", "%20");
            String sessionId = WorkspaceContext.getSessionOverride();
            if (WorkspaceContext.getRootOverride() != null && sessionId != null && !sessionId.isBlank()) {
                url += "?sid=" + java.net.URLEncoder.encode(sessionId, StandardCharsets.UTF_8)
                        .replace("+", "%20");
            }
            return url;
        } catch (Exception e) {
            return null;
        }
    }

    private String listFiles(String path, boolean recursive) {
        try {
            Path target = resolveReadPath(path);
            if (!Files.exists(target)) {
                return errorJson(ToolErrorCode.NOT_FOUND, "目录不存在: " + path);
            }
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
        if (path == null) {
            return "";
        }
        String n = path.replace('\\', '/').trim();
        while (n.startsWith("./")) {
            n = n.substring(2);
        }
        if (n.equalsIgnoreCase("workspace")) {
            return "";
        }
        if (n.regionMatches(true, 0, "workspace/", 0, 10)) {
            return n.substring(10);
        }
        return n;
    }

    /**
     * 解析路径（写入）：默认强制落在真实 workspace 根下。
     * {@code workspace/foo.md} → {@code {dataDir}/workspace/foo.md}。
     */
    private Path resolveWorkspacePath(String path) {
        Path resolved = resolveWritePath(
                effectiveWorkspaceRoot(), writeTaskDir(), path, allowAbsoluteWrite);
        // 词法前缀检查挡不住 junction/符号链接（workspace 里放一个指向 C:\Users 的
        // junction，前缀仍然"合法"而真实落点在根之外）。这里补一层真实路径判定。
        return requireAllowed(resolved, "write");
    }

    /** Office/文档工具统一落盘路径（对齐 AgentDataPaths workspace，禁止 user.dir/workspace）。 */
    public static Path resolveOutputPath(String path) {
        Path resolved = resolveWritePath(
                effectiveWorkspaceRoot(), writeTaskDir(), path, false);
        return requireAllowed(resolved, "write");
    }

    /** 主会话写根目录；仅子 Agent 的 taskOverride 才分子目录。 */
    public static String writeTaskDir() {
        String override = WorkspaceContext.getTaskOverride();
        return override == null ? "" : override;
    }

    static Path resolveWritePath(Path workspaceRoot, String task, String path,
                                 boolean allowAbsolute) {
        Path root = workspaceRoot.toAbsolutePath().normalize();
        if (StringUtils.isBlank(path)) {
            return StringUtils.isBlank(task) ? root : root.resolve(task).normalize();
        }
        String normalized = path.replace('\\', '/').trim();
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
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
                resolved = StringUtils.isBlank(task)
                        ? root.resolve(p).normalize()
                        : root.resolve(task).resolve(p).normalize();
            } else {
                resolved = root.resolve(p).normalize();
            }
        }
        if (!resolved.startsWith(root) && !allowAbsolute) {
            Path rebased = rebaseIntoWorkspace(root, resolved);
            if (Objects.isNull(rebased)) {
                throw new SecurityException("写入路径必须位于 workspace/ 内: " + resolved);
            }
            return rebased;
        }
        return resolved;
    }

    /**
     * read_file / search_code 按项目根解析，write_file 却锁在数据目录 workspace，
     * 模型顺着读链路会拼出 {projectDir}/workspace/xxx 再来写，直接拒会让
     * 多文件任务连续失败。这里把最后一个 workspace 段之后的部分重挂到真实根下。
     * 结果仍强制落在 root 内，不放宽写入边界。
     */
    private static Path rebaseIntoWorkspace(Path root, Path resolved) {
        int idx = -1;
        for (int i = resolved.getNameCount() - 1; i >= 0; i--) {
            if ("workspace".equalsIgnoreCase(resolved.getName(i).toString())) {
                idx = i;
                break;
            }
        }
        if (idx < 0 || idx == resolved.getNameCount() - 1) {
            return null;
        }
        Path tail = resolved.subpath(idx + 1, resolved.getNameCount());
        Path rebased = root.resolve(tail).normalize();
        return rebased.startsWith(root) ? rebased : null;
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
        if (StringUtils.isBlank(path)) {
            return resolveWorkspacePath(path);
        }
        String normalized = path.replace('\\', '/').trim();
        if (normalized.equalsIgnoreCase("workspace")
                || normalized.regionMatches(true, 0, "workspace/", 0, 10))
            return resolveWorkspacePath(path);
        Path p = Path.of(normalized);
        if (p.isAbsolute()) {
            // 读侧同样受限：read_file 是 PLAN_SAFE 工具，无需批准，
            // 直通绝对路径等于把"读主机任意文件"变成默认能力。
            return requireAllowed(p, "read");
        }

        // 相对项目根解析；命中真实文件/目录就用它，对齐 search_code/edit_file。
        Path fromRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath()
                .resolve(normalized).normalize();
        if (Files.exists(fromRoot)) {
            return requireAllowed(fromRoot, "read");
        }

        // 否则退回 workspace 语义（读取自身产出物）。
        return resolveWorkspacePath(path);
    }

    // ==================== HTTP 工具实现 ====================

    private String httpSend(String method, String url, String body, String contentType) {
        try {
            String blocked = networkGuard.validateUrl(url);
            if (Objects.nonNull(blocked)) {
                return "{\"error\":\"" + blocked.replace("\"", "'") + "\"}";
            }
            // 走 GuardedHttpClient：重定向每一跳都重新校验，响应体有硬上限，
            // 跨域自动剥离授权头。原先用静态 HTTP 客户端 + Redirect.NORMAL，
            // 一次 302 就能绕过这里的首跳校验打到内网。
            Map<String, String> headers = new java.util.LinkedHashMap<>();
            headers.put("User-Agent", "MiniAgent/1.0");
            if ("POST".equals(method)) {
                headers.put("Content-Type", StringUtils.isBlank(contentType)
                        ? "application/json; charset=utf-8" : contentType);
            }
            GuardedHttpClient.Response resp = guardedHttpClient.send(
                    method, url, body, headers, Duration.ofSeconds(20), 2 * 1024 * 1024);
            String respBody = resp.bodyText();
            if (respBody.length() > 8000) {
                respBody = respBody.substring(0, 8000) + "\n…（已截断）";
            }
            if ("POST".equals(method)) {
                return "HTTP " + resp.statusCode() + "\n" + respBody;
            }
            return respBody;
        } catch (Exception e) {
            return "{\"error\":\"HTTP " + method + " 失败: " + e.getMessage() + "\"}";
        }
    }

    // ==================== 命令执行实现 ====================

    private static String redactSensitive(String s) {
        return SecurityUtils.redactSensitive(s);
    }

    private static final java.util.regex.Pattern DANGEROUS_PATTERN = java.util.regex.Pattern.compile(            "(rm\\s+-rf\\s+/|rm\\s+-rf\\s+\\*|/etc/(shadow|passwd)|"
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

    /** 单个工具结果内联给模型的字符上限；超出部分落盘，只回显末尾。 */
    private static final int MAX_INLINE_OUTPUT_CHARS = 6000;

    /** 落盘目录（在 workspace 下，read_file 直接读得到）。 */
    private static final String TOOL_OUTPUT_DIR = "_tool-output";

    /** 超时场景回显的部分输出上限：够看清停在哪一步，又不至于把上下文吃满。 */
    private static final int MAX_TIMEOUT_PARTIAL_CHARS = 2000;

    /** 单次命令捕获的子进程输出上限（1 MiB）：够诊断，且不会把堆读爆。 */
    private static final int MAX_EXEC_CAPTURE_BYTES = 1024 * 1024;

    /** 输出硬上限（16 MiB）：超过即判定失控并强杀进程树（`yes` 之类）。 */
    private static final int MAX_EXEC_HARD_CAPTURE_BYTES = 16 * 1024 * 1024;

    private static final DateTimeFormatter OUTPUT_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private String execCommand(ExecCommandParams params) {
        // 是否允许执行由 AgentLoop 会话授权闸门决定；此处只做命令安全检查。
        String command = Objects.isNull(params) ? null : params.getCommand();
        if (StringUtils.isBlank(command)) {
            return "{\"error\":\"command 不能为空\"}";
        }
        int timeoutSeconds = params.requestedTimeoutSeconds();
        log.info("执行命令（timeout={}s, 描述={}）: {}", timeoutSeconds,
                Objects.isNull(params.getDescription()) ? "-" : params.getDescription(),
                redactSensitive(command));

        // 第1层：危险命令黑名单检查
        String lc = command.toLowerCase().trim();
        for (String bad : DANGEROUS_COMMANDS) {
            if (lc.contains(bad.toLowerCase())) {
                log.warn("安全拦截 - 黑名单命中: {}", redactSensitive(command));
                return errorJson(ToolErrorCode.PERMISSION_DENIED, "命令被安全策略拒绝：该命令包含危险操作。");
            }
        }

        // 第2层：正则模式匹配（防止编码绕过）
        if (DANGEROUS_PATTERN.matcher(command).find()) {
            log.warn("安全拦截 - 危险模式命中: {}", redactSensitive(command));
            return errorJson(ToolErrorCode.PERMISSION_DENIED, "命令被安全策略拒绝：检测到危险操作模式。");
        }

        // 第3层：命令注入防护（检测链式命令）
        if (containsCommandInjection(command)) {
            log.warn("安全拦截 - 命令注入检测: {}", redactSensitive(command));
            return errorJson(ToolErrorCode.PERMISSION_DENIED, "命令被安全策略拒绝：检测到可能的命令注入。");
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

            // 第4层：工作目录限制在工作区（避免扫用户主目录）。
            // 目录选择单独抽成 execWorkingDirectory()，因为它必须跟着"生效工作区"走，
            // 而这一点只有单元测试守得住（真实跑一次 agent 才能观察到）。
            File workDirFile = execWorkingDirectory();
            pb.directory(workDirFile);
            log.info("命令执行目录: {}", workDirFile.getAbsolutePath());

            // 环境变量沙箱：只继承启动进程必需的白名单变量。
            // 默认继承整份环境时，子进程能看到 LLM_API_KEY / 数据库口令 / 云凭证，
            // 一条 `echo %LLM_API_KEY%` 就能带走（而读环境还被判成"只读、幂等、可并行"）。
            int dropped = ProcessEnv.sanitize(pb);
            pb.environment().put("PYTHONUTF8", "1");
            pb.environment().put("PYTHONIOENCODING", "UTF-8");
            log.info("命令子进程环境已收敛: 丢弃 {} 个继承变量（需要放行用 agent.tools.env-passthrough）", dropped);
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            // 关键修复：异步读流 + 主线程限时等待。
            // 旧实现先 readAllBytes() 再 waitFor()，而 readAllBytes() 会阻塞到进程关闭 stdout
            // （即进程退出）为止——对常驻/输出不止的进程永远读不到 EOF，超时形同虚设。
            // 现在用独立线程 drain 输出，主线程 waitFor(timeout)，超时即 destroyForcibly，超时真正生效。
            //
            // 这个预算必须【小于】外层闸门（ExecCommandParams.outerGateSeconds）：外层先触发会被
            // 判成「终态未知」并中止整轮，而这里能给出可控终态（强杀进程 + 部分输出）。
            // 改造前内外都是 30s，外层几乎总是先到，于是这套精细处理基本是死代码。
            final java.nio.charset.Charset cs = isWindows
                    ? java.nio.charset.Charset.forName("GBK")
                    : java.nio.charset.StandardCharsets.UTF_8;
            // 有界捕获：旧实现把子进程输出**无上限**读进堆（截断发生在读完之后），
            // 于是 `yes` 或一个刷屏构建就能 OutOfMemoryError —— 拖垮的是整个应用进程，
            // 而不是只让这次调用失败。这里按上限截断：超过 MAX_CAPTURE 后继续读（必须继续读，
            // 否则子进程会因管道写满而阻塞），但不再累积；超过 MAX_HARD_CAPTURE 直接判为
            // 失控输出并强杀。
            final BoundedOutputCapture capture = new BoundedOutputCapture(
                    MAX_EXEC_CAPTURE_BYTES, MAX_EXEC_HARD_CAPTURE_BYTES);
            Thread drainer = new Thread(() -> {
                try (var in = proc.getInputStream()) {
                    byte[] chunk = new byte[8192];
                    int n;
                    while ((n = in.read(chunk)) != -1) {
                        capture.write(chunk, n);
                    }
                } catch (Exception ignored) {
                    // 进程被强杀时读流会抛异常，属预期，忽略
                }
            }, "exec-cmd-drainer");
            drainer.setDaemon(true);
            drainer.start();

            boolean finished = proc.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                killProcessTree(proc);
                proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                drainer.interrupt();
                String partial = capture.asString(cs);
                String persisted = persistLongOutput(command, partial);
                String shown = truncateTail(partial, MAX_TIMEOUT_PARTIAL_CHARS);
                return "{\"error\":\"命令执行超时（" + timeoutSeconds + "s），已强制终止整棵进程树，未产生后续副作用。"
                        + "若是启动服务器/常驻进程，请改为交付文件由用户自行运行；"
                        + "若是构建/测试类慢命令，请调大 timeout 参数（上限 "
                        + ExecCommandParams.MAX_TIMEOUT_SECONDS + "）。\","
                        + "\"exit_code\":-1,"
                        + (persisted.isEmpty() ? "" : "\"partial_output_path\":" + jsonString(persisted) + ",")
                        + "\"partial_output\":" + jsonString(shown) + "}";
            }
            // 输出失控（超过硬上限）时同样强杀整棵树：这种进程几乎必然是 `yes` 之类的刷屏命令，
            // 让它跑完只会把磁盘/内存/管道拖满。
            if (capture.overflowed()) {
                killProcessTree(proc);
                log.warn("命令输出超过硬上限 {} 字节，已强杀整棵进程树: {}",
                        MAX_EXEC_HARD_CAPTURE_BYTES, redactSensitive(truncate(command, 120)));
            }
            drainer.join(2000); // 等读流线程把剩余输出读完
            int exitCode;
            try {
                exitCode = proc.exitValue();
            } catch (IllegalThreadStateException stillRunning) {
                killProcessTree(proc);
                proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                exitCode = -1;
            }
            String out = capture.asString(cs);
            String formatted = formatExecResult(command, exitCode, out);
            if (capture.truncated()) {
                formatted += "\n[输出捕获上限] 子进程输出超过 " + MAX_EXEC_CAPTURE_BYTES
                        + " 字节，只保留了前 " + MAX_EXEC_CAPTURE_BYTES + " 字节（共 "
                        + capture.totalBytes() + " 字节）。请缩小命令的输出范围（如 grep/head）。";
            }
            return formatted;
        } catch (Exception e) {
            return "{\"error\":\"命令执行失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 强杀整棵进程树。
     *
     * <p>{@code Process.destroyForcibly()} 只杀直接子进程：Windows 上那是 {@code cmd.exe}，
     * 孙进程（{@code mvnw.cmd → java.exe}、{@code npm.cmd → node.exe}）会继续跑、继续写盘、
     * 继续占端口 —— 而模型已经被告知"命令已超时终止"，于是重试时产生重复副作用。
     * 这里先按进程树自底向上杀，再兜底杀直接子进程。</p>
     */
    static void killProcessTree(Process proc) {
        if (Objects.isNull(proc)) {
            return;
        }
        try {
            proc.descendants().forEach(handle -> {
                try {
                    handle.destroyForcibly();
                } catch (Exception ignored) {
                    // 单个句柄杀不掉不影响其余
                }
            });
        } catch (Exception ignored) {
            // descendants() 在进程已退出时可能抛错
        }
        try {
            proc.destroyForcibly();
        } catch (Exception ignored) {
            // 已退出
        }
    }

    /**
     * 组装给模型看的命令结果。
     *
     * <p>三条约定（改造前只有第一条）：</p>
     * <ol>
     *   <li>{@code exit_code=N} 必须在首行 —— 规划器的 {@code command_success} 判据靠这个前缀取退出码。</li>
     *   <li>非错误退出码（grep/findstr 的 1、find/diff 的 1）补一行 {@code [exit-note]}：
     *       既让 {@code ToolResult.fromLegacy} 不把它读成失败，也直接告诉模型别重试同一条命令。</li>
     *   <li>超长输出落盘并回显<b>末尾</b>：构建/测试的报错都在尾部。落盘后模型可以用 read_file 取全量，
     *       不必重跑命令。</li>
     * </ol>
     */
    private String formatExecResult(String command, int exitCode, String output) {
        StringBuilder sb = new StringBuilder();
        sb.append("exit_code=").append(exitCode).append('\n');
        if (exitCode != 0 && !CommandSemantics.isFailure(command, exitCode)) {
            sb.append(CommandSemantics.toleratedNote(command, exitCode)).append('\n');
        }
        if (output.length() > MAX_INLINE_OUTPUT_CHARS) {
            String persisted = persistLongOutput(command, output);
            if (!persisted.isEmpty()) {
                sb.append("[输出过长已落盘] ").append(persisted)
                        .append("（共 ").append(output.length()).append(" 字符；下面只显示末尾 ")
                        .append(MAX_INLINE_OUTPUT_CHARS)
                        .append(" 字符，需要完整内容请用 read_file 读该文件，不要重跑命令）\n");
            } else {
                sb.append("（输出过长已截断：共 ").append(output.length()).append(" 字符，只显示末尾 ")
                        .append(MAX_INLINE_OUTPUT_CHARS).append(" 字符）\n");
            }
            sb.append(truncateTail(output, MAX_INLINE_OUTPUT_CHARS));
        } else {
            sb.append(output);
        }
        return sb.toString();
    }

    /** 取末尾 N 个字符；截断说明由调用方在头部给出，这里不加噪声。 */
    private static String truncateTail(String text, int maxChars) {
        if (Objects.isNull(text)) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(text.length() - maxChars);
    }

    /**
     * 把完整输出写进 {@code workspace/_tool-output/}，返回绝对路径；失败返回空串。
     *
     * <p>落盘失败不让工具失败：命令本身跑成了，拿不到完整输出只是降级。
     * 路径放在 workspace 内，是为了让 {@code read_file} 的路径解析能直接命中它。</p>
     */
    private String persistLongOutput(String command, String output) {
        if (Objects.isNull(output) || output.isEmpty()) {
            return "";
        }
        try {
            Path dir = effectiveWorkspaceRoot().resolve(TOOL_OUTPUT_DIR);
            Files.createDirectories(dir);
            String name = OUTPUT_STAMP.format(LocalDateTime.now())
                    + "-" + Integer.toHexString(Objects.requireNonNullElse(command, "").hashCode()) + ".log";
            Path file = dir.resolve(name);
            Files.writeString(file, "# command: " + command + System.lineSeparator() + output,
                    StandardCharsets.UTF_8);
            return file.toAbsolutePath().toString();
        } catch (Exception e) {
            log.warn("命令输出落盘失败，退回纯截断: {}", e.getMessage());
            return "";
        }
    }

    /** 把字符串安全编码为 JSON 字符串字面量（含引号），用于拼装错误返回。 */
    private static String jsonString(String s) {
        if (Objects.isNull(s)) {
            return "\"\"";
        }
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
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
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
            if (trimmed.matches(".*" + bad + ".*")) {
                return true;
            }
        }

        // 2. 允许正常命令操作：管道 |、链接 && ||、分号 ; 都是正常语法
        //    只拦截重定向到项目外目录
        if (trimmed.contains(">") && !trimmed.contains("> workspace/")
                && !trimmed.contains("> ./")
                && !hasOnlyNulOutputRedirects(trimmed)) {
            return true;
        }
        return false;
    }

    /**
     * cmd.exe accepts the NUL device without a space after the redirection operator
     * (for example {@code 2>nul}). Allow only commands whose every output redirection
     * targets that exact device; keep file redirections subject to the workspace rule.
     */
    private static boolean hasOnlyNulOutputRedirects(String command) {
        boolean sawRedirect = false;
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                continue;
            }
            if (c != '>') continue;

            sawRedirect = true;
            int targetStart = i + 1;
            if (targetStart < command.length() && command.charAt(targetStart) == '>') targetStart++;
            while (targetStart < command.length() && Character.isWhitespace(command.charAt(targetStart))) {
                targetStart++;
            }
            if (targetStart >= command.length()) return false;

            String target;
            int targetEnd;
            char first = command.charAt(targetStart);
            if (first == '"' || first == '\'') {
                int closingQuote = command.indexOf(first, targetStart + 1);
                if (closingQuote < 0) return false;
                target = command.substring(targetStart + 1, closingQuote);
                targetEnd = closingQuote + 1;
                if (targetEnd < command.length()
                        && !Character.isWhitespace(command.charAt(targetEnd))
                        && "&|;<>".indexOf(command.charAt(targetEnd)) < 0) {
                    return false;
                }
            } else {
                targetEnd = targetStart;
                while (targetEnd < command.length()
                        && !Character.isWhitespace(command.charAt(targetEnd))
                        && "&|;<>".indexOf(command.charAt(targetEnd)) < 0) {
                    targetEnd++;
                }
                target = command.substring(targetStart, targetEnd);
            }
            if (!"nul".equalsIgnoreCase(target)) return false;
            i = targetEnd - 1;
        }
        return sawRedirect;
    }

    // ==================== JSON 解析 ====================

    @SuppressWarnings("unchecked")
    /** 工具参数 JSON 解析失败时的标记键。值为 true 表示传入的 arguments 不是合法 JSON
     *  （最常见原因：模型单轮输出被长度上限截断，tool_call 的 arguments 是半截 JSON）。
     *  各工具 handler 可据此回报"参数被截断"而不是把 null 当正常值写出去。 */
    static final String PARSE_ERROR_KEY = "__parse_error__";

    private Map<String, Object> parseJson(String json) {
        if (StringUtils.isBlank(json)) {
            return new HashMap<>();
        }
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
        return com.miniagent.common.StringUtils.truncate(s, max);
    }

    static String strArg(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return Objects.isNull(v) ? "" : String.valueOf(v).trim();
    }

    static int intArg(Map<String, Object> p, String key, int defaultValue) {
        Object v = p.get(key);
        if (Objects.isNull(v)) {
            return defaultValue;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 工具失败结果。**带上 errorCode，上游就不必猜**。
     *
     * <p>{@code ToolResult.fromLegacy} 见到 {@code errorCode} 就直接采信，见不到才拿文案刮关键字
     * （timeout / 429 / 503）。像「安全策略拒绝」「文件不存在」这类文案里没有关键词的失败，
     * 刮不出来就一律降级成 {@code EXECUTION_FAILED}，而上游 {@code RecoveryEngine} 据此选恢复档 ——
     * 结果是「该换方案的被当成可重试」「该重试的被当成做不了」。</p>
     *
     * <p>顺带修掉一个隐患：旧写法把 message 直接拼进 JSON，
     * 路径或命令输出里出现引号就会产出坏 JSON
     * （{@code {"error":"路径不存在: C:\a"b.txt"}}）。</p>
     */
    static String errorJson(ToolErrorCode code, String message) {
        String codeName = (code == null ? ToolErrorCode.EXECUTION_FAILED : code).name();
        return "{\"errorCode\":\"" + codeName + "\",\"error\":\"" + jsonEscape(message) + "\"}";
    }

    /** JSON 字符串字面量转义：路径与命令输出里出现引号是常态。 */
    static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
