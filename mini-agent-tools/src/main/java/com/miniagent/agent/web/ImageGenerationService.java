package com.miniagent.agent.web;

import com.miniagent.common.context.OwnerContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.security.GuardedHttpClient;
import com.miniagent.agent.security.GuardedMediaDownloader;
import com.miniagent.config.storage.MediaStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * 图像生成服务 — 对标 hermes-agent image_generate
 *
 * hermes-agent 实现：
 *   模型: FAL.ai FLUX 2 Pro (fal-ai/flux-2-pro)
 *   放大: FAL.ai Clarity Upscaler (fal-ai/clarity-upscaler) 2x
 *   认证: FAL_KEY 环境变量 → Authorization: Key <key>
 *   流程: 提交生成请求(POST /queue.fal.run/flux-2/pro) → 轮询结果 → 提交放大 → 轮询 → 返回URL
 *
 * 我们的实现：
 *   后端路由: FAL.ai (同hermes) / SiliconFlow (免费额度) / 智谱CogView (国产)
 *   输出格式: {"success": true, "image": "url"} (与hermes完全一致)
 *
 * 环境变量：
 *   FAL_KEY              — FAL.ai API 密钥（首选）
 *   CHATANYWHERE_API_KEY — ChatAnywhere 图片生成 API 密钥（OpenAI 兼容）
 *   SILICONFLOW_API_KEY  — SiliconFlow API 密钥（免费FLUX.1-schnell）
 *   ZHIPU_API_KEY        — 智谱AI API密钥（CogView-3）
 *
 * 免 Key 兜底：
 *   pollinations         — 公共免费服务，不需要任何 Key；只能用 image.gen.pollinations-enabled 关掉
 */
@Slf4j
@Service
public class ImageGenerationService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // Provider redirects are not a safe way to move an authenticated request to
            // another host.  Media URLs are validated separately after the provider call.
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    /** Bounded executor for one provider attempt.  Attempts are deliberately sequential. */
    private static final ExecutorService BACKEND_EXECUTOR = new ThreadPoolExecutor(0, 8, 60,
            TimeUnit.SECONDS, new SynchronousQueue<>(), Thread.ofVirtual().name("image-provider-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());

    /** 各后端默认外层等待（秒）；chatanywhere 慢模型（gpt-image-2-ca）走配置项覆盖 */
    private static final Map<String, Long> BACKEND_TIMEOUT_SECONDS = Map.of(
            "chatanywhere", 500L,
            "mimo", 90L,
            "xiaomi", 90L,
            "fal", 120L,
            "siliconflow", 45L,
            "zhipu", 45L,
            // 公共免费服务，排队时明显更慢；90s 与 mimo 同档
            "pollinations", 90L
    );

    /** 熔断：5 分钟内失败达到阈值则跳过该后端 */
    private static final int CIRCUIT_FAILURE_THRESHOLD = 3;
    private static final long CIRCUIT_WINDOW_MS = 5 * 60 * 1000L;
    private final ConcurrentHashMap<String, CircuitState> circuitStates = new ConcurrentHashMap<>();

    private static final class CircuitState {
        final AtomicInteger failures = new AtomicInteger(0);
        final AtomicLong windowStartMs = new AtomicLong(System.currentTimeMillis());
    }

    @Autowired
    private MediaStorage mediaStorage;
    @Autowired
    private GuardedMediaDownloader mediaDownloader;

    // ========== 环境变量配置 ==========

    @Value("${image.gen.fal-key:#{null}}")
    private String falKey;

    @Value("${image.gen.chatanywhere-key:#{null}}")
    private String chatAnywhereKey;

    @Value("${image.gen.chatanywhere-base-url:https://api.chatanywhere.tech/v1}")
    private String chatAnywhereBaseUrl;

    @Value("${image.gen.chatanywhere-model:dall-e-3}")
    private String chatAnywhereModel;

    /** gpt-image-2-ca 常需 3–8 分钟；默认 500s，勿再压到 120s */
    @Value("${image.gen.chatanywhere-timeout-seconds:500}")
    private long chatAnywhereTimeoutSeconds;

    /** 单次 image_generate 调用的内层预算默认值（秒），覆盖顺序后端尝试。 */
    public static final long DEFAULT_TOTAL_BUDGET_SECONDS = 540;

    /** 剩余预算低于此值不再开新一轮后端尝试（开了也必然被砍，还多花一次钱）。 */
    private static final long MIN_ATTEMPT_REMAINING_SECONDS = 10;

    /** 0 = 未配置，回退 {@link #DEFAULT_TOTAL_BUDGET_SECONDS}。 */
    @Value("${image.gen.total-budget-seconds:0}")
    private long configuredTotalBudgetSeconds;

    /**
     * 内层总预算（秒）：到点中断本地请求，服务端结果无法确认时返回 OUTCOME_UNKNOWN。
     *
     * <p>注册期（{@code BuiltinTools.registerImageGenerateTool}）把它写进执行契约：
     * 外层闸门 = 预算 + {@code ToolConcurrencyPolicy} REMOTE_GENERATION 档的 30s 余量。</p>
     */
    public long totalBudgetSeconds() {
        return configuredTotalBudgetSeconds > 0 ? configuredTotalBudgetSeconds : DEFAULT_TOTAL_BUDGET_SECONDS;
    }

    /**
     * 每个后端的单次等待上限（秒）。
     *
     * <p>每次顺序尝试均被此上限和剩余总预算共同约束。</p>
     */
    long backendWaitSeconds(String backend) {
        return "chatanywhere".equals(backend)
                ? chatAnywhereWaitSeconds()
                : BACKEND_TIMEOUT_SECONDS.getOrDefault(backend, 120L);
    }

    /** 距内层总预算截止还剩多少整秒（永不为负）。 */
    private static long remainingSeconds(long deadlineMs) {
        return Math.max(0, (deadlineMs - System.currentTimeMillis()) / 1000L);
    }

    @Value("${image.gen.siliconflow-api-key:#{null}}")
    private String siliconflowKey;

    @Value("${image.gen.zhipu-api-key:#{null}}")
    private String zhipuKey;

    @Value("${image.gen.backend:}")
    private String configuredBackend;

    /**
     * Pollinations 免 Key 公共免费服务：没有任何生图 Key 时的兜底。
     *
     * <p>默认开启的理由是「新机器装完就能生图」，但它没有 SLA ——
     * 正式交付若不允许依赖公共免费服务，把它设成 {@code false}，
     * 后端会退回「没 Key 就报错」的老行为。</p>
     */
    @Value("${image.gen.pollinations-enabled:true}")
    private boolean pollinationsEnabled;

    @Value("${image.gen.pollinations-base-url:https://image.pollinations.ai}")
    private String pollinationsBaseUrl;

    // 实测 2026-11-22：GET /models 只返回 ["sana"]，传 flux/turbo 会被静默忽略并回同一张图，
    // 所以默认写实际存在的那个；服务端换模型池时用 IMAGE_POLLINATIONS_MODEL 覆盖即可。
    @Value("${image.gen.pollinations-model:sana}")
    private String pollinationsModel;

    @Value("${image.gen.mimo-api-key:${langchain4j.open-ai.chat-model.api-key:#{null}}}")
    private String mimoKey;

    @Value("${image.gen.mimo-base-url:${langchain4j.open-ai.chat-model.base-url:https://token-plan-cn.xiaomimimo.com/v1}}")
    private String mimoBaseUrl;

    @Value("${image.gen.mimo-model:mimo-v2-image}")
    private String mimoImageModel;

    /**
     * 生成图片 — 对标 hermes-agent image_generate
     *
     * @param prompt      图片描述（英文效果最好）
     * @param aspectRatio 比例: landscape / square / portrait
     * @return 标准化 JSON: {"success": true, "image": "url"}
     */
    public String generate(String prompt, String aspectRatio) {
        if (StringUtils.isBlank(prompt)) {
            return errorJson(ToolErrorCode.INVALID_ARGUMENT, "prompt 不能为空");
        }
        if (StringUtils.isBlank(aspectRatio)) {
            aspectRatio = "landscape";
        }

        String primaryBackend = resolveBackend();
        String imageSize = mapAspectRatio(aspectRatio);
        // 落盘要用 owner 目录（media/generated/<owner>/），这里先抓住调用方的身份：
        // 后端请求在线程中绑定同一 owner，落盘仍使用调用方的身份快照。
        OwnerContext ownerCtx = OwnerContext.capture();
        log.info("图像生成: backend={} aspect={} prompt='{}'", primaryBackend, aspectRatio,
                prompt.substring(0, Math.min(80, prompt.length())));

        // 按优先级构建 fallback 链（跳过未配置 key / 熔断中的 provider）
        List<String> chain = buildFallbackChain(primaryBackend).stream()
                .filter(b -> !isCircuitOpen(b))
                .toList();
        if (chain.isEmpty()) {
            chain = buildFallbackChain(primaryBackend); // 全部熔断时仍尝试原链
        }

        String lastError = "无可用后端";
        // 内层总预算截止点：所有降级尝试都必须在它之内结束，到点工具自己收尾（可控终态）。
        long deadlineMs = System.currentTimeMillis() + totalBudgetSeconds() * 1000L;

        // Provider generation is a paid, non-idempotent operation.  Never race two
        // providers: cancelling a CompletableFuture cannot cancel a request already
        // accepted by the remote service and used to create duplicate images/costs.
        for (int i = 0; i < chain.size(); i++) {
            String backend = chain.get(i);
            if (remainingSeconds(deadlineMs) < MIN_ATTEMPT_REMAINING_SECONDS) {
                lastError += "；预算（" + totalBudgetSeconds() + "s）耗尽，未再试: "
                        + chain.subList(i, chain.size());
                break;
            }
            BackendAttempt attempt = tryBackend(backend, prompt, imageSize, deadlineMs);
            if (attempt.isOk()) {
                return toMarkdownImage(attempt.url(), ownerCtx);
            }
            lastError += "；" + attempt.describe(backend);
            // A timeout/interruption leaves the provider outcome unknown.  Retrying with
            // another provider would be a second non-idempotent submission, so stop and
            // surface an explicit reconciliation state to the caller.
            if (attempt.uncertain()) {
                return errorJson(ToolErrorCode.OUTCOME_UNKNOWN,
                        "图像生成结果未知：后端 " + backend + " 已提交或连接中断，未自动向其他后端重复提交。"
                                + "请先确认该后端任务/账单状态，再决定是否重试。详情：" + attempt.error());
            }
        }

        log.error("所有图像生成后端均失败: {}", lastError);
        // 所有后端都打不通属于依赖侧不可用 —— 显式声明可重试，
        // 否则上游会把它当成「这任务做不了」而放弃改 prompt / 换方案。
        //
        // 文案里直接写降级方向：AgentLoop 判到 DEPENDENCY_UNAVAILABLE 会把本工具从下一轮
        // 工具表里移除，模型只看到"工具没了"不知道该换谁，就退化成写脚本画图或用 ASCII 凑数。
        // 指路写进错误本身，比只写进系统提示更可靠（系统提示可能被裁剪，工具结果一定在上下文里）。
        return errorJson(ToolErrorCode.DEPENDENCY_UNAVAILABLE,
                "所有后端均失败: " + lastError
                        + "。文生图在本次运行内不可用（下一轮会从工具表移除）："
                        + "架构图/流程图/时序图/ER 图改用 render_diagram 出 .svg；"
                        + "插画/封面类降不了级，如实告知用户，不要用 ASCII 图冒充交付物。");
    }

    /** ChatAnywhere 实际等待秒数（2CA 慢模型用加长超时） */
    private long chatAnywhereWaitSeconds() {
        long configured = chatAnywhereTimeoutSeconds > 0 ? chatAnywhereTimeoutSeconds : 500L;
        if (Objects.nonNull(chatAnywhereModel) && chatAnywhereModel.toLowerCase().contains("gpt-image")) {
            return Math.max(configured, 500L);
        }
        return Math.max(configured, 120L);
    }

    /**
     * 单后端尝试结果。**失败必须带上可读原因** —— 它要一路带到最终错误里给模型看。
     *
     * <p>此前 {@code tryBackend} 失败只 {@code return null}，原因写进日志就没了，
     * 于是聚合出来的错误长成「所有后端均失败: 后端 siliconflow 失败」——
     * 模型既不知道是超时、限流、鉴权还是响应格式不对，也就无从据此改方案
     * （用户看到的正是这句，2026-10-15）。</p>
     */
    private record BackendAttempt(String url, String error, boolean uncertain) {
        static BackendAttempt ok(String url) {
            return new BackendAttempt(url, "", false);
        }

        static BackendAttempt fail(String error) {
            return new BackendAttempt("", StringUtils.isBlank(error) ? "未知原因" : error, false);
        }

        static BackendAttempt unknown(String error) {
            return new BackendAttempt("", StringUtils.isBlank(error) ? "请求结果未知" : error, true);
        }

        boolean isOk() {
            return StringUtils.isNotBlank(url);
        }

        /** 聚合用的一行描述：{@code 后端名：原因}。 */
        String describe(String backend) {
            return error.isEmpty() ? backend : backend + "：" + error;
        }
    }

    /** 尝试单个后端；失败记录熔断并把原因带出去 */
    private BackendAttempt tryBackend(String backend, String prompt, String imageSize, long deadlineMs) {
        Future<String> future = null;
        try {
            log.info("尝试后端: {}", backend);
            // 单次等待 = 后端自身上限与内层总预算剩余的较小值 —— 绝不越过预算截止点。
            long timeoutSec = Math.min(backendWaitSeconds(backend), remainingSeconds(deadlineMs));
            if (timeoutSec <= 0) {
                return BackendAttempt.fail("内层预算已耗尽，未发起请求");
            }
            OwnerContext owner = OwnerContext.capture();
            future = BACKEND_EXECUTOR.submit(() -> {
                try (var ignored = OwnerContext.bind(owner)) {
                    return invokeBackend(backend, prompt, imageSize);
                }
            });
            String imageUrl = future.get(timeoutSec, TimeUnit.SECONDS);
            if (StringUtils.isNotBlank(imageUrl)) {
                recordSuccess(backend);
                return BackendAttempt.ok(imageUrl);
            }
            log.warn("后端 {} 未返回图片，暂停提交并要求核对结果", backend);
            recordFailure(backend);
            return BackendAttempt.unknown("服务端已响应但未提供可交付的图片，需先核对生成结果");
        } catch (java.util.concurrent.TimeoutException e) {
            if (future != null) future.cancel(true);
            recordFailure(backend);
            return BackendAttempt.unknown("后端等待超过 " + backendWaitSeconds(backend)
                    + " 秒；请求可能已被服务端接受，未自动重复提交");
        } catch (java.util.concurrent.CancellationException e) {
            return BackendAttempt.unknown("请求被取消，服务端是否已接受未知");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (future != null) future.cancel(true);
            return BackendAttempt.unknown("调用线程被中断，服务端是否已接受未知");
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            return BackendAttempt.fail("本地图片请求并发已满，未提交到服务端");
        } catch (Exception e) {
            Throwable root = e;
            while ((root instanceof java.util.concurrent.ExecutionException || root instanceof java.util.concurrent.CompletionException)
                    && root.getCause() != null) {
                root = root.getCause();
            }
            // 限速有确定的恢复窗口：计熔断会把「等 45 秒就能用」变成「5 分钟全废」，
            // 而且这期间用户重试只会得到「熔断中，跳过」，看不出真实原因。
            if (root instanceof BackendRateLimitedException rateLimited) {
                log.warn("后端 {} 限速（{}s 后可重试），不计入熔断: {}",
                        backend, rateLimited.retryAfterSeconds(), rateLimited.getMessage());
                return BackendAttempt.fail(rateLimited.getMessage());
            }
            String detail = Optional.ofNullable(root.getMessage()).orElse(e.getMessage());
            log.warn("后端 {} 异常: {}", backend, detail);
            recordFailure(backend);
            return root instanceof BackendRejectedException
                    ? BackendAttempt.fail(detail) : BackendAttempt.unknown(detail);
        }
    }
    /**
     * 安全解析生图 API 响应：先校验 HTTP/Content-Type/正文形态，再解析 JSON。
     * 对方返回 HTML 错误页时给出可读错误，而不是 Jackson JsonParseException。
     */
    private static JsonNode parseApiJson(String backend, HttpResponse<String> response) {
        int status = response.statusCode();
        String body = Objects.isNull(response.body()) ? "" : response.body();
        String contentType = response.headers().firstValue("content-type").orElse("");
        String preview = bodyPreview(body, 180);

        if (status < 200 || status >= 300) {
            String message = backend + " HTTP " + status
                    + (StringUtils.isBlank(contentType) ? "" : " content-type=" + contentType)
                    + " body=" + preview;
            if (status >= 400 && status < 500 && status != 408 && status != 409)
                throw new BackendRejectedException(message);
            throw new RuntimeException(message);
        }
        String trimmed = body.stripLeading();
        boolean looksJson = trimmed.startsWith("{") || trimmed.startsWith("[");
        boolean claimsJson = contentType.toLowerCase().contains("json");
        if (!looksJson) {
            throw new RuntimeException(backend + " 返回非 JSON（HTTP " + status
                    + (StringUtils.isBlank(contentType) ? "" : ", content-type=" + contentType)
                    + "）。常见原因：鉴权失败/网关错误页/端点不存在。body=" + preview);
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException(backend + " JSON 解析失败"
                    + (claimsJson ? "" : "（Content-Type 也非 json）")
                    + ": " + e.getMessage() + "; body=" + preview, e);
        }
    }

    private static String bodyPreview(String body, int max) {
        if (StringUtils.isBlank(body)) {
            return "(empty)";
        }
        String oneLine = body.replace('\r', ' ').replace('\n', ' ').strip();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }

    protected String invokeBackend(String backend, String prompt, String imageSize) throws Exception {
        return switch (backend) {
            case "chatanywhere" -> generateViaChatAnywhere(prompt, imageSize);
            case "mimo", "xiaomi" -> generateViaMimo(prompt, imageSize);
            case "fal" -> generateViaFal(prompt, imageSize);
            case "siliconflow" -> generateViaSiliconFlow(prompt, imageSize);
            case "zhipu" -> generateViaCogView(prompt, imageSize);
            case "pollinations" -> generateViaPollinations(prompt, imageSize);
            default -> null;
        };
    }

    /** 单张生成图的字节上限：再大就不是正常出图结果，别把内存和磁盘交给对端。 */
    private static final long MAX_IMAGE_BYTES = 32L * 1024 * 1024;
    private static final int MAX_JSON_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_API_RESPONSE_BYTES = (int) ((MAX_IMAGE_BYTES + 2) / 3 * 4 + 64 * 1024);

    /** Explicit pre-execution provider rejection; only these may safely fall through. */
    static class BackendRejectedException extends RuntimeException {
        BackendRejectedException(String message) { super(message); }
    }

    /** Pollinations 把 prompt 放在 URL 路径里，超长会被网关拒；按字符截断而不是整条丢弃。 */
    private static final int MAX_POLLINATIONS_PROMPT_CHARS = 800;

    /**
     * 后端明确告知「额度/限速，N 秒后可再试」。
     *
     * <p>必须和普通失败分开处理。它说明后端是活的，恢复时间也是确定的；
     * 若按普通失败计入熔断，连续几次限速就会让该后端被跳过整整 5 分钟，
     * 而实际几十秒后就能出图 —— 用户看到的现象是「刚才还能用，突然全废了」。
     * 这是 2026-11-22 实测 Pollinations 每 IP 每 45 秒 1 张时暴露的问题。</p>
     */
    static final class BackendRateLimitedException extends RuntimeException {
        private final long retryAfterSeconds;

        BackendRateLimitedException(String message, long retryAfterSeconds) {
            super(message);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    /** 从正文里兜底提取「Retry after 33s」这类提示；响应头优先。 */
    private static final Pattern RETRY_AFTER_TEXT =
            Pattern.compile("(?i)retry\\s+after\\s+(\\d+)\\s*s");

    /**
     * 解析「多少秒后可重试」。
     *
     * <p>优先信任 {@code Retry-After} 响应头；Pollinations 的 402 正文里也带
     * 「Retry after 33s」，头缺失时用它兜底。解析不出来返回 0，
     * 让调用方只说「请稍后重试」，不编造一个看起来精确的秒数。</p>
     */
    private static long parseRetryAfterSeconds(HttpResponse<?> response, String body) {
        Long fromHeader = parsePositiveLong(
                response.headers().firstValue("retry-after").orElse(null));
        if (Objects.nonNull(fromHeader)) {
            return fromHeader;
        }
        if (StringUtils.isNotBlank(body)) {
            Matcher matcher = RETRY_AFTER_TEXT.matcher(body);
            if (matcher.find()) {
                Long fromBody = parsePositiveLong(matcher.group(1));
                if (Objects.nonNull(fromBody)) {
                    return fromBody;
                }
            }
        }
        return 0L;
    }

    private static Long parsePositiveLong(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 后端结果 → 对话区可用的 markdown，**先把图片落到本机**。
     *
     * <h2>为什么必须落盘（2026-10-22 实测）</h2>
     *
     * <p>硅基流动返回的是临时对象存储链接：
     * {@code s3.siliconflow.cn/temporary/…&X-Amz-Expires=3600}（1 小时到期）；
     * 智谱返回的 ufileos 链接带 {@code Expires}（实测约 7 天）。
     * 原样拼进 markdown 的后果是：当场能看，过一会儿历史记录里的图就裂，断网打不开，
     * 换个客户端同样看不到 —— 用户以为图丢了，其实是链接过期。
     *
     * <p>落盘后走 {@code /api/generated-media/<owner>/<file>}（带登录鉴权、Range、
     * nosniff 与 CSP sandbox，见 {@code MediaResponses}），图片才是本机资产。
     *
     * <p>下载或落盘失败必须显式报告；远端 URL 未经完整读取验证时不能作为成功图片返回。
     */
    private String toMarkdownImage(String imageUrl, OwnerContext ownerCtx) {
        if (StringUtils.isBlank(imageUrl)) {
            return imageUrl;
        }
        // chatanywhere 的 2ca 分支已经落盘并拼好了 markdown
        String persisted = persistImage(imageUrl, ownerCtx);
        if (StringUtils.isBlank(persisted)) {
            return errorJson(ToolErrorCode.EXECUTION_FAILED,
                    "图像下载或落盘未通过验证，未返回未经验证的远端链接");
        }
        return "![生成的图片](" + persisted + ")";
    }

    /** 包可见以便用本地假服务器测「下载 → 判类型 → 落盘」全流程（不依赖外网）。 */
    String persistImage(String imageUrl, OwnerContext ownerCtx) {
        try {
            byte[] bytes;
            String contentType;
            if (imageUrl.startsWith("data:")) {
                int comma = imageUrl.indexOf(',');
                if (comma < 0) {
                    return null;
                }
                contentType = imageUrl.substring(5, comma).split(";")[0];
                if (imageUrl.length() - comma - 1 > ((MAX_IMAGE_BYTES + 2) / 3) * 4) {
                    log.warn("拒绝解码超过图片预算的 data URI");
                    return null;
                }
                bytes = Base64.getDecoder().decode(imageUrl.substring(comma + 1));
            } else if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) {
                GuardedMediaDownloader.Download response = mediaDownloader.download(
                        imageUrl, Duration.ofSeconds(60), (int) MAX_IMAGE_BYTES);
                if (response.statusCode() != 200) {
                    log.warn("生成图下载失败 HTTP {}: {}",
                            response.statusCode(), abbreviateUrl(imageUrl));
                    return null;
                }
                contentType = response.contentType();
                bytes = response.body();
                if (bytes.length > MAX_IMAGE_BYTES) {
                    log.warn("生成图超过 {} 字节上限: {}", MAX_IMAGE_BYTES, abbreviateUrl(imageUrl));
                    return null;
                }
            } else {
                // Only known server-generated media routes may already be persisted.
                return imageUrl.startsWith("/api/generated-media/") ? imageUrl : null;
            }
            if (bytes.length == 0) {
                return null;
            }
            String ext = imageExtension(bytes, contentType);
            if (ext == null) {
                log.warn("下载内容不是可识别的图片（content-type={}）: {}",
                        contentType, abbreviateUrl(imageUrl));
                return null;
            }
            String fileName = String.format("img_%d_%d.%s",
                    System.currentTimeMillis(), ThreadLocalRandom.current().nextInt(10000), ext);
            String storedUrl;
            // saveGenerated 读取当前 owner；工具线程上使用已捕获的身份快照。
            try (var ignored = OwnerContext.bind(ownerCtx)) {
                storedUrl = mediaStorage.saveGenerated(bytes, fileName);
            }
            log.info("生成图已落盘: {} ({} 字节, 来源 {})", storedUrl, bytes.length, abbreviateUrl(imageUrl));
            return storedUrl;
        } catch (GuardedHttpClient.NetworkRejectedException rejected) {
            log.warn("拒绝下载不安全的生图地址: {} ({})", abbreviateUrl(imageUrl), rejected.getMessage());
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("生成图落盘失败: {} ({})", abbreviateUrl(imageUrl), e.toString());
            return null;
        }
    }

    /**
     * 按<b>文件头魔数</b>判图片类型，不只看 content-type：对象存储常把图片标成
     * {@code application/octet-stream}，而扩展名决定了 {@code /api/generated-media}
     * 会不会放行（`approvedInlineMediaType` 按扩展名查白名单）。
     * 判定不了返回 null；不能仅凭服务端 MIME 声明把 HTML/JSON 错误页当成图片。
     */
    static String imageExtension(byte[] bytes, String contentType) {
        if (Objects.nonNull(bytes)) {
            if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50
                    && bytes[2] == 0x4E && bytes[3] == 0x47) {
                return "png";
            }
            if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8
                    && (bytes[2] & 0xFF) == 0xFF) {
                return "jpg";
            }
            if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
                return "gif";
            }
            if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F'
                    && bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E'
                    && bytes[10] == 'B' && bytes[11] == 'P') {
                return "webp";
            }
        }
        return null;
    }

    /** 日志里不打完整的签名 URL（带 token，且长度能顶掉半屏）。 */
    private static String abbreviateUrl(String url) {
        if (StringUtils.isBlank(url)) {
            return "";
        }
        String head = url.length() <= 48 ? url : url.substring(0, 48) + "…";
        int q = head.indexOf('?');
        return q > 0 ? head.substring(0, q) + "?…" : head;
    }

    private boolean isCircuitOpen(String backend) {
        CircuitState state = circuitStates.get(backend);
        if (Objects.isNull(state)) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - state.windowStartMs.get() > CIRCUIT_WINDOW_MS) {
            state.failures.set(0);
            state.windowStartMs.set(now);
            return false;
        }
        if (state.failures.get() >= CIRCUIT_FAILURE_THRESHOLD) {
            log.info("后端 {} 熔断中（{} 次失败/5min），跳过", backend, state.failures.get());
            return true;
        }
        return false;
    }

    private void recordFailure(String backend) {
        CircuitState state = circuitStates.computeIfAbsent(backend, k -> new CircuitState());
        long now = System.currentTimeMillis();
        if (now - state.windowStartMs.get() > CIRCUIT_WINDOW_MS) {
            state.failures.set(1);
            state.windowStartMs.set(now);
        } else {
            state.failures.incrementAndGet();
        }
    }

    private void recordSuccess(String backend) {
        CircuitState state = circuitStates.get(backend);
        if (Objects.nonNull(state)) {
            state.failures.set(0);
            state.windowStartMs.set(System.currentTimeMillis());
        }
    }

    // ========== ChatAnywhere 图片后端（OpenAI Images 兼容） ==========

    /**
     * ChatAnywhere 图片生成接口：
     * POST /v1/images/generations
     * 文档：https://chatanywhere.apifox.cn/api-92222078
     */
    private String generateViaChatAnywhere(String prompt, String imageSize) throws Exception {
        String key = resolveKey(chatAnywhereKey, "CHATANYWHERE_API_KEY");

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("prompt", prompt);
        payload.put("n", 1);
        payload.put("model", chatAnywhereModel);
        payload.put("size", mapToOpenAiImageSize(imageSize));
        if ("dall-e-3".equalsIgnoreCase(chatAnywhereModel)) {
            payload.put("quality", "hd");
        }

        if ("gpt-image-2-ca".equalsIgnoreCase(chatAnywhereModel)) {
           return generateImageBy2ca(prompt, imageSize);
        }

        String base = (StringUtils.isBlank(chatAnywhereBaseUrl))
                ? "https://api.chatanywhere.tech/v1"
                : chatAnywhereBaseUrl.replaceAll("/+$", "");

        long httpTimeout = chatAnywhereWaitSeconds();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + "/images/generations"))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(httpTimeout))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = ProviderHttp.send(HTTP, request, ProviderHttp.utf8(MAX_JSON_RESPONSE_BYTES));
        JsonNode root = parseApiJson("ChatAnywhere", response);
        JsonNode data = root.path("data");
        if (data.isArray() && !data.isEmpty()) {
            String url = data.get(0).path("url").asText("");
            if (!StringUtils.isBlank(url)) {
                return url;
            }
        }

        String msg = root.path("error").path("message")
                .asText(root.path("message").asText(bodyPreview(response.body(), 200)));
        log.error("ChatAnywhere 图像生成响应异常: HTTP {} {}", response.statusCode(), msg);
        throw new RuntimeException("ChatAnywhere: " + msg);
    }
    private String generateImageBy2ca(String prompt, String imageSize) throws Exception {
        String key = resolveKey(chatAnywhereKey, "CHATANYWHERE_API_KEY");
        String base = (StringUtils.isBlank(chatAnywhereBaseUrl))
                ? "https://api.chatanywhere.tech/v1"
                : chatAnywhereBaseUrl.replaceAll("/+$", "");

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("prompt", prompt);
        payload.put("n", 1);
        payload.put("model", chatAnywhereModel);
        payload.put("size", mapToOpenAiImageSize(imageSize));

        try {
            long httpTimeout = chatAnywhereWaitSeconds();
            log.info("ChatAnywhere 2CA 开始生成: model={} timeout={}s size={}",
                    chatAnywhereModel, httpTimeout, mapToOpenAiImageSize(imageSize));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/images/generations"))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(httpTimeout))
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = ProviderHttp.send(HTTP, request, ProviderHttp.utf8(MAX_API_RESPONSE_BYTES));

            JsonNode root = parseApiJson("ChatAnywhere-2CA", response);

            if (root.has("error")) {
                throw new IOException("ChatAnywhere API 返回错误: "
                        + root.path("error").path("message").asText(bodyPreview(response.body(), 200)));
            }

            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() == 0) {
                throw new IOException("ChatAnywhere API 返回数据为空");
            }

            String b64Json = data.get(0).path("b64_json").asText();
            if (StringUtils.isBlank(b64Json)) {
                throw new IOException("ChatAnywhere API 响应缺少 b64_json");
            }
            if (b64Json.length() > ((MAX_IMAGE_BYTES + 2) / 3) * 4) {
                throw new IOException("ChatAnywhere 图片超过安全大小上限");
            }

            // 解码 Base64
            byte[] imageBytes = Base64.getDecoder().decode(b64Json);
            String extension = imageExtension(imageBytes, "");
            if (imageBytes.length == 0 || imageBytes.length > MAX_IMAGE_BYTES || extension == null) {
                throw new IOException("ChatAnywhere 返回的图片内容无效或超过安全大小上限");
            }

            // Provider workers only fetch/validate.  The caller owns the single commit
            // through persistImage, so a cancelled worker cannot write a late artifact.
            return "data:image/" + extension + ";base64," + b64Json;

        } catch (java.net.http.HttpTimeoutException e) {
            log.error("ChatAnywhere 2CA 超时（{}s）: {}", chatAnywhereWaitSeconds(), e.getMessage());
            throw new IOException("ChatAnywhere 图片生成请求超时；远端结果未确认，未复用其它请求的图片", e);
        } catch (Exception e) {
            log.error("ChatAnywhere 2CA 图像生成失败: {}", e.getMessage(), e);
            throw e;
        }
    }

    // ========== 小米 MiMo / OpenAI-compatible images 后端 ==========

    /**
     * 小米 MiMo 网关按 OpenAI 兼容图片接口调用。
     * 如果当前账号/模型不支持 /images/generations，会把服务端错误透传给上层，
     * 避免 Agent 继续无限重试。
     */
    private String generateViaMimo(String prompt, String imageSize) throws Exception {
        String key = resolveKey(mimoKey, "MIMO_API_KEY");
        Map<String, Integer> size = parseImageSize(imageSize);

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("model", mimoImageModel);
        payload.put("prompt", prompt);
        payload.put("n", 1);
        payload.put("size", size.get("width") + "x" + size.get("height"));
        payload.put("response_format", "url");

        String base = (StringUtils.isBlank(mimoBaseUrl))
                ? "https://token-plan-cn.xiaomimimo.com/v1"
                : mimoBaseUrl.replaceAll("/+$", "");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + "/images/generations"))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(90))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = ProviderHttp.send(HTTP, request, ProviderHttp.utf8(MAX_JSON_RESPONSE_BYTES));
        JsonNode root = parseApiJson("MiMo", response);
        JsonNode data = root.path("data");
        if (data.isArray() && !data.isEmpty()) {
            JsonNode first = data.get(0);
            String url = first.path("url").asText("");
            if (!StringUtils.isBlank(url)) {
                return url;
            }
            String b64 = first.path("b64_json").asText("");
            if (!StringUtils.isBlank(b64)) {
                return "data:image/png;base64," + b64;
            }
        }

        String msg = root.path("error").path("message")
                .asText(root.path("message").asText(bodyPreview(response.body(), 200)));
        log.error("MiMo 图像生成业务响应异常: HTTP {} {}", response.statusCode(), msg);
        throw new RuntimeException("MiMo: " + msg);
    }

    // ========== FAL.ai 后端（同 hermes-agent） ==========

    /**
     * FAL.ai FLUX 2 Pro — hermes-agent 完全一致的模型
     *
     * REST API 流程：
     *   1. POST https://queue.fal.run/fal-ai/flux-2/pro → 得到 request_id
     *   2. GET  https://queue.fal.run/fal-ai/flux-2/pro/requests/{id} → 轮询直到完成
     *   3. 返回 images[0].url
     *
     * 可选：用 Clarity Upscaler 放大（hermes-agent 会自动做）
     */
    private String generateViaFal(String prompt, String imageSize) throws Exception {
        String key = resolveKey(falKey, "FAL_KEY");

        // 1. 提交生成请求
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("prompt", prompt);
        payload.put("image_size", imageSize);
        payload.put("num_inference_steps", 50);
        payload.put("guidance_scale", 4.5);
        payload.put("num_images", 1);
        payload.put("output_format", "png");
        payload.put("sync_mode", true); // 同步模式，直接返回结果

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://queue.fal.run/fal-ai/flux-2/pro"))
                .header("Authorization", "Key " + key)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                .build();

        HttpResponse<String> response = ProviderHttp.send(HTTP, request, ProviderHttp.utf8(MAX_JSON_RESPONSE_BYTES));
        JsonNode root = parseApiJson("FAL", response);

        // 同步模式直接返回 images
        JsonNode images = root.path("images");
        if (images.isArray() && !images.isEmpty()) {
            return images.get(0).path("url").asText(null);
        }

        // 异步模式：需要轮询
        String requestId = root.path("request_id").asText("");
        if (!StringUtils.isBlank(requestId)) {
            return pollFalResult(key, requestId);
        }

        log.error("FAL.ai 响应异常: {}", bodyPreview(response.body(), 300));
        return null;
    }

    /**
     * 轮询 FAL.ai 异步结果
     */
    private String pollFalResult(String key, String requestId) throws Exception {
        for (int i = 0; i < 30; i++) { // 最多轮询30次，每次2秒
            Thread.sleep(2000);

            HttpRequest pollReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://queue.fal.run/fal-ai/flux-2/pro/requests/" + requestId))
                    .header("Authorization", "Key " + key)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> pollResp = ProviderHttp.send(HTTP, pollReq, ProviderHttp.utf8(MAX_JSON_RESPONSE_BYTES));
            JsonNode pollRoot;
            try {
                pollRoot = parseApiJson("FAL-poll", pollResp);
            } catch (RuntimeException bad) {
                log.warn("FAL 轮询非 JSON: {}", bad.getMessage());
                continue;
            }
            String status = pollRoot.path("status").asText("");

            if ("COMPLETED".equals(status)) {
                JsonNode images = pollRoot.path("images");
                if (images.isArray() && !images.isEmpty()) {
                    return images.get(0).path("url").asText(null);
                }
            } else if ("FAILED".equals(status)) {
                log.error("FAL.ai 生成失败: {}", pollResp.body());
                return null;
            }
            log.info("FAL.ai 轮询: status={} ({}/30)", status, i + 1);
        }
        log.error("FAL.ai 轮询超时");
        return null;
    }

    // ========== SiliconFlow 后端（免费额度） ==========

    /**
     * SiliconFlow — 提供免费的 FLUX.1-schnell 模型
     * 文档: https://docs.siliconflow.cn/api-reference/images/images-generations
     */
    private String generateViaSiliconFlow(String prompt, String imageSize) throws Exception {
        String key = resolveKey(siliconflowKey, "SILICONFLOW_API_KEY");

        // SiliconFlow 要求 "WxH" 格式，如 "1024x1024"
        // 使用官方推荐的 Kolors 模型尺寸
        String sizeStr = toSiliconFlowSize(imageSize);

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("model", "Kwai-Kolors/Kolors");
        payload.put("prompt", prompt);
        payload.put("image_size", sizeStr);
        payload.put("batch_size", 1);
        payload.put("num_inference_steps", 20);  // 官方默认 20
        payload.put("guidance_scale", 7.5);       // 官方默认 7.5

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.siliconflow.cn/v1/images/generations"))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(45))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                .build();

        HttpResponse<String> response = ProviderHttp.send(HTTP, request, ProviderHttp.utf8(MAX_JSON_RESPONSE_BYTES));
        JsonNode root = parseApiJson("SiliconFlow", response);
        JsonNode images = root.path("images");

        if (images.isArray() && !images.isEmpty()) {
            return images.get(0).path("url").asText(null);
        }

        log.error("SiliconFlow 业务响应异常: {}", bodyPreview(response.body(), 300));
        String apiMsg = root.path("message").asText(root.path("error").asText("未知错误"));
        throw new RuntimeException("SiliconFlow: " + apiMsg);
    }

    // ========== 智谱CogView后端（国产备选） ==========

    /**
     * 智谱 CogView-3 — 国产模型，中文prompt支持好
     */
    private String generateViaCogView(String prompt, String imageSize) throws Exception {
        String key = resolveKey(zhipuKey, "ZHIPU_API_KEY");

        // CogView API 格式
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("model", "cogview-3-flash");
        payload.put("prompt", prompt);
        payload.put("size", mapToCogViewSize(imageSize));
        payload.put("n", 1);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://open.bigmodel.cn/api/paas/v4/images/generations"))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(45))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                .build();

        HttpResponse<String> response = ProviderHttp.send(HTTP, request, ProviderHttp.utf8(MAX_JSON_RESPONSE_BYTES));
        JsonNode root = parseApiJson("CogView", response);
        JsonNode data = root.path("data");

        if (data.isArray() && !data.isEmpty()) {
            return data.get(0).path("url").asText(null);
        }

        log.error("CogView 业务响应异常: {}", bodyPreview(response.body(), 300));
        String apiMsg = root.path("error").path("message").asText(root.path("message").asText("未知错误"));
        throw new RuntimeException("CogView: " + apiMsg);
    }

    // ========== Pollinations 后端（免 Key 公共免费服务） ==========

    /**
     * Pollinations 文生图 —— 免 API Key 的公共免费服务。
     *
     * <p>它和前面几个后端形态不同：不是 {@code POST /v1/images/generations} 换 JSON，
     * 而是 {@code GET /prompt/<urlencoded>} 直接回图片字节。所以这里自己把字节读出来，
     * 再以 {@code data:} URI 交给 {@link #toMarkdownImage} 链路（{@link #persistImage}
     * 本来就支持 data URI），由后者统一做魔数校验并落盘。</p>
     *
     * <h2>为什么不直接返回那个 GET URL 了事</h2>
     *
     * <p>URL 是确定可构造的，直接返回它 {@code tryBackend} 必然判「成功」，
     * 本轮就不会再降级；万一服务端限流或回错误页，用户拿到的是裂图，
     * 错误反倒被推迟到落盘阶段吞掉。在这里读一次字节，
     * 失败才能走正常异常路径记下原因并降级到下一个后端。</p>
     *
     * <p>同一 prompt 固定 seed，重试与缓存命中时出图一致，不会「试一张、给另一张」。
     * 公共免费服务无 SLA，失败 3 次后熔断 5 分钟，不会把每次生图都拖在它身上。</p>
     */
    private String generateViaPollinations(String prompt, String imageSize) throws Exception {
        Map<String, Integer> dim = parseImageSize(imageSize);
        String trimmed = prompt.length() > MAX_POLLINATIONS_PROMPT_CHARS
                ? prompt.substring(0, MAX_POLLINATIONS_PROMPT_CHARS)
                : prompt;

        long seed = Math.abs((long) trimmed.hashCode() * 31L) % 1_000_000L;
        String url = pollinationsBaseUrl.replaceAll("/+$", "")
                + "/prompt/" + URLEncoder.encode(trimmed, StandardCharsets.UTF_8)
                + "?width=" + dim.get("width")
                + "&height=" + dim.get("height")
                + "&seed=" + seed
                + "&nologo=true"
                + "&model=" + URLEncoder.encode(pollinationsModel, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(90))
                .GET()
                .build();
        HttpResponse<byte[]> response =
                ProviderHttp.send(HTTP, request, ProviderHttp.bytes((int) MAX_IMAGE_BYTES));

        int status = response.statusCode();
        String contentType = response.headers().firstValue("content-type").orElse("");
        if (status != 200) {
            String errBody = new String(response.body(), 0, Math.min(600, response.body().length), StandardCharsets.UTF_8);
            // 402/429 是「限速」，不是「后端坏了」：抛专用异常，让上层不计熔断
            if (status == 402 || status == 429) {
                long retryAfter = parseRetryAfterSeconds(response, errBody);
                throw new BackendRateLimitedException(
                        "Pollinations HTTP " + status
                                + "：公共免费生图限速（实测每 IP 每 45 秒 1 张）"
                                + (retryAfter > 0
                                        ? "，约 " + retryAfter + " 秒后可重试"
                                        : "，请稍后重试")
                                + "；需要连续出图请配置 SILICONFLOW_API_KEY 等有额度的后端",
                        retryAfter);
            }
            String error = "Pollinations HTTP " + status
                    + (StringUtils.isBlank(contentType) ? "" : " content-type=" + contentType)
                    + " body=" + bodyPreview(errBody, 180);
            if (status >= 400 && status < 500 && status != 408 && status != 409)
                throw new BackendRejectedException(error);
            throw new RuntimeException(error);
        }
        byte[] bytes = response.body();
        if (bytes.length == 0) {
            throw new RuntimeException("Pollinations 返回空响应");
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new RuntimeException("Pollinations 响应超过 " + MAX_IMAGE_BYTES + " 字节上限");
        }
        if (imageExtension(bytes, contentType) == null) {
            throw new RuntimeException("Pollinations 返回非图片（content-type=" + contentType
                    + "，body=" + bodyPreview(new String(bytes, StandardCharsets.UTF_8), 180) + "）");
        }
        String mime = StringUtils.isBlank(contentType) ? "image/jpeg" : contentType.split(";")[0].trim();
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    // ========== 后端选择 ==========

    private String resolveBackend() {
        if (StringUtils.isNotBlank(configuredBackend)) {
            return configuredBackend.toLowerCase().trim();
        }
        // 自动检测优先级: ChatAnywhere > MiMo > FAL > SiliconFlow > 智谱
        if (StringUtils.isNotBlank(chatAnywhereKey)) {
            return "chatanywhere";
        }
        if (StringUtils.isNotBlank(mimoKey)) {
            return "mimo";
        }
        if (StringUtils.isNotBlank(falKey)) {
            return "fal";
        }
        if (StringUtils.isNotBlank(siliconflowKey)) {
            return "siliconflow";
        }
        if (StringUtils.isNotBlank(zhipuKey)) {
            return "zhipu";
        }
        // 一个 Key 都没有时优先走免 Key 兜底，别落到必然 404 的聊天网关
        if (pollinationsEnabled) {
            return "pollinations";
        }
        return "mimo"; // 默认使用项目主模型网关
    }

    /**
     * 构建 fallback 链：只包含已配置了有效 key 的后端，按优先级排列。
     * primaryBackend 排首位，其余有效后端依次追加。
     */
    private List<String> buildFallbackChain(String primaryBackend) {
        List<String> chain = new ArrayList<>();
        // 哪些后端有 key 就加入
        Map<String, String> keyMap = Map.of(
                "chatanywhere", Optional.ofNullable(chatAnywhereKey).orElse(""),
                "mimo",        Objects.nonNull(mimoKey) ? mimoKey : "",
                "fal",         Optional.ofNullable(falKey).orElse(""),
                "siliconflow", Objects.nonNull(siliconflowKey) ? siliconflowKey : "",
                "zhipu",       Optional.ofNullable(zhipuKey).orElse(""
        ));
        List<String> allBackends = List.of("chatanywhere", "mimo", "fal", "siliconflow", "zhipu");

        // 先放 primary
        if (StringUtils.isBlank(keyMap.getOrDefault(primaryBackend, ""))) {
            // primary 没 key，尝试从环境变量检测
            String envKey = switch (primaryBackend) {
                case "chatanywhere" -> System.getenv("CHATANYWHERE_API_KEY");
                case "mimo", "xiaomi" -> System.getenv("MIMO_API_KEY");
                case "fal"         -> System.getenv("FAL_KEY");
                case "siliconflow" -> System.getenv("SILICONFLOW_API_KEY");
                case "zhipu"       -> System.getenv("ZHIPU_API_KEY");
                default -> null;
            };
            if (StringUtils.isNotBlank(envKey)) {
                chain.add(primaryBackend);
            }
        } else {
            chain.add(primaryBackend);
        }

        // 再追加其他有 key 的
        for (String b : allBackends) {
            if (!b.equals(primaryBackend)) {
                String k = keyMap.getOrDefault(b, "");
                if (!StringUtils.isBlank(k)) {
                    chain.add(b);
                }
                else {
                    String envKey = switch (b) {
                        case "chatanywhere" -> System.getenv("CHATANYWHERE_API_KEY");
                        case "mimo", "xiaomi" -> System.getenv("MIMO_API_KEY");
                        case "fal"         -> System.getenv("FAL_KEY");
                        case "siliconflow" -> System.getenv("SILICONFLOW_API_KEY");
                        case "zhipu"       -> System.getenv("ZHIPU_API_KEY");
                        default -> null;
                    };
                    if (StringUtils.isNotBlank(envKey)) {
                        chain.add(b);
                    }
                }
            }
        }

        // 免 Key 兜底永远排最后：有 Key 的后端先上，它只在全都没成时兜一次。
        // 放在 chain.isEmpty() 判断之前，否则「无 Key 且开启兜底」会被误判成空链。
        if (pollinationsEnabled && !chain.contains("pollinations")) {
            chain.add("pollinations");
        }

        if (chain.isEmpty()) {
            // 没有任何有效 key，仍然返回 primary，让它报错给上层
            chain.add(primaryBackend);
        }
        return chain;
    }

    private String resolveKey(String value, String envName) {
        if (StringUtils.isNotBlank(value)) {
            return value;
        }
        String envVal = System.getenv(envName);
        if (StringUtils.isNotBlank(envVal)) {
            return envVal;
        }
        throw new IllegalStateException("未配置 " + envName + "，无法使用该图像生成后端");
    }

    // ========== 工具方法 ==========

    /**
     * 将简单比例参数映射为 FAL.ai 的 image_size
     * 与 hermes-agent ASPECT_RATIO_MAP 完全一致
     */
    private static String mapAspectRatio(String aspectRatio) {
        return switch (aspectRatio.toLowerCase().trim()) {
            case "landscape" -> "landscape_16_9";
            case "square" -> "square_hd";
            case "portrait" -> "portrait_16_9";
            default -> "landscape_16_9";
        };
    }

    /** 将 image_size 解析为 width/height */
    private static Map<String, Integer> parseImageSize(String imageSize) {
        return switch (imageSize) {
            case "landscape_16_9" -> Map.of("width", 1344, "height", 768);
            case "landscape_4_3" -> Map.of("width", 1024, "height", 768);
            case "square_hd" -> Map.of("width", 1024, "height", 1024);
            case "square" -> Map.of("width", 512, "height", 512);
            case "portrait_4_3" -> Map.of("width", 768, "height", 1024);
            case "portrait_16_9" -> Map.of("width", 768, "height", 1344);
            default -> Map.of("width", 1024, "height", 768);
        };
    }

    /** 转换为 SiliconFlow Kolors 推荐的 "WxH" 格式 */
    private static String toSiliconFlowSize(String imageSize) {
        // Kolors 官方推荐尺寸：
        //   "1024x1024" (1:1)  "960x1280" (3:4)  "768x1024" (3:4)
        //   "720x1440" (1:2)   "720x1280" (9:16)
        // 注意：Kolors 不支持真正的横版（如16:9 landscape），landscape 映射为方形
        return switch (imageSize) {
            case "landscape_16_9" -> "1024x1024"; // 无横版，用方形代替
            case "landscape_4_3"  -> "1024x1024"; // 无横版，用方形代替
            case "square_hd"      -> "1024x1024"; // 1:1
            case "square"         -> "1024x1024"; // 1:1
            case "portrait_4_3"   -> "960x1280";  // 3:4 竖版
            case "portrait_16_9"  -> "720x1280";  // 9:16 竖版
            default               -> "1024x1024"; // 默认方形
        };
    }

    /** 映射为 CogView 的 size 格式 */
    private static String mapToCogViewSize(String imageSize) {
        Map<String, Integer> size = parseImageSize(imageSize);
        return size.get("width") + "x" + size.get("height");
    }

    /** 映射为 OpenAI/ChatAnywhere 图片接口支持的 size。 */
    private static String mapToOpenAiImageSize(String imageSize) {
        return switch (imageSize) {
            case "portrait_16_9", "portrait_4_3" -> "1024x1792";
            case "landscape_16_9", "landscape_4_3" -> "1792x1024";
            case "square_4K" -> "3840x2160";
            default -> "1024x1024";
        };
    }

    static String errorJson(String message) {
        return errorJson(ToolErrorCode.EXECUTION_FAILED, message);
    }

    /**
     * 失败结果，**带 errorCode**。
     *
     * <p>{@code ToolResult.fromLegacy} 见到 {@code errorCode} 就直接采信，见不到才拿文案刮关键字。
     * 产错方既然知道原因类型，就该说清楚 —— 上游 {@code RecoveryEngine} 据此选恢复档。</p>
     */
    static String errorJson(ToolErrorCode code, String message) {
        String codeName = (code == null ? ToolErrorCode.EXECUTION_FAILED : code).name();
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("success", false);
            root.put("errorCode", codeName);
            root.put("error", message);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"success\":false,\"errorCode\":\"" + codeName
                    + "\",\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
        }
    }
}
