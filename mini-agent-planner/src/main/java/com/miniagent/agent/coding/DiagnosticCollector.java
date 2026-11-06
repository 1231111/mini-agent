package com.miniagent.agent.coding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 代码诊断反馈回路：写文件时登记，下一轮 LLM 请求前检查并注入。
 *
 * <h2>这条回路补的是什么</h2>
 *
 * <p>Claude Code 靠 {@code src/services/lsp/passiveFeedback.ts} 把
 * {@code textDocument/publishDiagnostics} 转成 attachment 注入下一轮 ——
 * 模型改完代码，语言服务自动报错，错误在下一轮就在上下文里，不需要模型主动去查。</p>
 *
 * <p>没有这条回路时，MiniAgent 的模型改完 {@code .java} 只能靠自己想起来调 {@code exec_command}
 * 跑构建。实测里它经常想不起来，用户看到的是「改完了」但根本编译不过。
 * <b>这是编程智能体和「会写文件的通用 agent」的分界线。</b></p>
 *
 * <h2>为什么不在工具内部同步检查</h2>
 *
 * <p>每次 {@code edit_file} 都当场起一次 {@code javac}，会把单次编辑从毫秒级拖到秒级，
 * 而多轮连续编辑时大部分中间状态本来就是编译不过的 —— 检查了也是噪声。
 * 正确的时机是<b>一轮结束、下次要问模型之前</b>：那时文件处于这一轮的稳定状态。</p>
 *
 * <h2>为什么注入点在压缩之后</h2>
 *
 * <p>诊断是针对「当前代码」的，不是历史对话。放在压缩前，它可能被当作旧消息折叠进摘要；
 * 放在压缩后、{@code callLlm} 之前，才能保证模型这一轮一定看到。</p>
 *
 * <h2>为什么空结果不注入</h2>
 *
 * <p>「没有发现错误」这句话本身要占 token，而且每轮都出现会让模型逐渐忽略这类系统消息。
 * 没有内容就什么都不加 —— 沉默即代表没问题。</p>
 */
@Slf4j
@Component
public class DiagnosticCollector {

    private final CodingDiagnosticsProperties properties;
    private final Map<String, DiagnosticProvider> providersById;

    /**
     * 待检查队列。
     *
     * <p>用 Set 而不是 List：同一轮里对同一个文件连续编辑三五次是常态，
     * 只需在轮末检查它的<b>最终状态</b>一次。</p>
     */
    private final Set<Path> pending = ConcurrentHashMap.newKeySet();

    public DiagnosticCollector(CodingDiagnosticsProperties properties,
                               List<DiagnosticProvider> providers) {
        this.properties = properties;
        this.providersById = providers.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(DiagnosticProvider::id, Function.identity(),
                        (a, b) -> a, java.util.LinkedHashMap::new));
    }

    /**
     * 登记一个「被改过的源文件」。非源码扩展名会在 {@link #consumeReminder} 里被自然跳过，
     * 这里不预判 —— 预判要多维护一份扩展名表。
     */
    public void registerChanged(Path file) {
        if (!properties.isEnabled() || file == null) {
            return;
        }
        pending.add(file.toAbsolutePath().normalize());
    }

    /** 当前有多少文件等着检查。用于日志与测试，不影响行为。 */
    public int pendingCount() {
        return pending.size();
    }

    /** 清空待检查队列（新用户消息开始时调用，与 {@code clearToolCache} 同生命周期）。 */
    public void clear() {
        pending.clear();
    }

    /**
     * 取走并检查所有待检查文件，返回可直接作为一条系统消息注入的文本。
     *
     * @return {@code null} 表示没有可注入的内容（未开启 / 没有待检查文件 / 检查全过）
     */
    public String consumeReminder() {
        if (!properties.isEnabled() || pending.isEmpty()) {
            return null;
        }
        List<Path> batch = new ArrayList<>(pending);
        pending.clear();
        if (batch.size() > properties.getMaxFilesPerTurn()) {
            batch = batch.subList(0, properties.getMaxFilesPerTurn());
        }

        List<Diagnostic> found = new ArrayList<>();
        for (Path file : batch) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            DiagnosticProvider provider = providerFor(file);
            if (provider == null) {
                continue;
            }
            try {
                found.addAll(provider.check(file,
                        Duration.ofSeconds(Math.max(1, properties.getPerFileTimeoutSeconds()))));
            } catch (Exception e) {
                // 单个 provider 出错不能连累整轮：诊断是增益，不是前置条件。
                log.debug("诊断 provider 执行异常: {}（file={}）", provider.id(), file, e);
            }
        }
        if (found.isEmpty()) {
            return null;
        }
        return format(found);
    }

    /** 找到第一个「声明的 provider 列表里有、且支持该文件、且当前可用」的检查器。 */
    private DiagnosticProvider providerFor(Path file) {
        for (String id : properties.getProviders()) {
            DiagnosticProvider provider = providersById.get(id);
            if (provider == null || !provider.supports(file) || !provider.available()) {
                continue;
            }
            return provider;
        }
        return null;
    }

    /**
     * 拼成注入文本。
     *
     * <p>必须写明「只做了语法级检查」：模型会默认这是完整类型检查，
     * 然后因为「没报错」而以为代码正确。这个限定词不是免责声明，是防止错误推断。</p>
     */
    private String format(List<Diagnostic> diagnostics) {
        StringBuilder sb = new StringBuilder();
        sb.append("【代码检查】刚改过的源码自动跑了一遍语法检查（只查语法，不含类型与依赖），发现 ")
                .append(diagnostics.size()).append(" 处问题：\n");

        int budget = Math.max(200, properties.getMaxBudgetChars());
        int used = sb.length();
        int shown = 0;
        for (Diagnostic d : diagnostics) {
            String line = "- " + d.oneLine() + "\n";
            if (used + line.length() > budget) {
                break;
            }
            sb.append(line);
            used += line.length();
            shown++;
        }
        if (shown < diagnostics.size()) {
            sb.append("（还有 ").append(diagnostics.size() - shown)
                    .append(" 处未展示，需要完整输出请自己跑一次构建）\n");
        }
        sb.append("修掉这些再做别的；如果判断某条是误报，说明理由后继续，"
                + "不要为了消除报错而改坏本来正确的逻辑。");
        return sb.toString();
    }
}
