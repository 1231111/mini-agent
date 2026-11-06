package com.miniagent.agent.coding;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 语法/类型检查器。
 *
 * <h2>为什么是「检查器」而不是「LSP 客户端」</h2>
 *
 * <p>Claude Code 的 {@code src/services/lsp/passiveFeedback.ts} 把
 * {@code textDocument/publishDiagnostics} 转成 attachment 注入下一轮，模型改完代码自动看到错误。
 * 那条反馈回路是编程智能体和「会写文件的通用 agent」的分界线。</p>
 *
 * <p>但 LSP server 要用户自己装（{@code jdtls} / {@code typescript-language-server} / {@code pylsp}），
 * 桌面端首启体验很差 —— Claude Code 自己也把它做成 plugin 才启用。
 * 所以这里先定义一个<b>与实现无关</b>的接口：零依赖的语法检查先顶上，
 * 之后接真 LSP 时只换实现，不动调用方。这是脚手架顺序，不是架构顺序。</p>
 *
 * <h2>契约</h2>
 *
 * <ul>
 *   <li>{@link #supports(Path)} 只按扩展名判断，不做内容嗅探 —— 嗅探会让每个文件多一次 IO，
 *       而且猜错的代价（把 Markdown 当代码检查）比漏检更烦人。</li>
 *   <li>{@link #check} 必须是<b>同步且有时限</b>的：调用点在每轮 LLM 请求之前，
 *       跑超时就把整轮拖死。超时必须返回已拿到的部分，不能抛。</li>
 *   <li>拿不到任何有意义的错误时返回空 list，<b>不要</b>返回「没有错误」这种占位文本 ——
 *       调用方靠「空」来决定不注入任何内容，占位文本会白烧 token。</li>
 * </ul>
 */
public interface DiagnosticProvider {

    /** 稳定的短标识，用于配置项与日志。 */
    String id();

    /** 这个检查器是否处理该文件。 */
    boolean supports(Path file);

    /**
     * 当前环境能否真的跑起来（编译器/解释器在不在）。
     *
     * <p>桌面端内置的是 jlink 出来的 JRE，{@code jdk.compiler} 和 {@code javac} 都可能不存在。
     * 实现方必须自己探一次并缓存结果，不要每次 check 都探。</p>
     */
    boolean available();

    /**
     * 同步检查一个文件。
     *
     * @param budget 本文件的时间上限，到点必须返回已拿到的部分
     */
    List<Diagnostic> check(Path file, Duration budget);
}
