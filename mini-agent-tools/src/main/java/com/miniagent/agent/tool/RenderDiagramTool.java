package com.miniagent.agent.tool;

import com.miniagent.agent.tool.impl.RenderDiagramParams;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 把 Mermaid / SVG 渲染成 PNG（或原样写出 SVG）。
 *
 * <p>出图任务之前只交 .mmd 源码就停，因为工具面没有渲染器。
 * 这里走本机 {@code npx @mermaid-js/mermaid-cli}，不经过 exec_command 闸门。
 *
 * <p><b>注意 {@code agent.tools.exec-enabled} 管不到这里</b>：那个开关限制的是
 * "模型发起命令行"，挡不住本工具内部自己起进程。两者要分开看（2026-09-28 明确）。
 *
 * <p>所以本工具自己负责可用性判断：{@code npx} 不在 PATH 上时直接返回替代方案，
 * 见 {@link #runMmdc}。PNG 那条路在客户机上是不可用的（客户机没有 Node），
 * 但 SVG 直出那条路不需要任何外部程序，仍然可用 —— 因此不能整体不注册这个工具。
 */
@Slf4j
@Component
public class RenderDiagramTool {

    private static final int RENDER_TIMEOUT_SECONDS = 90;
    private static final int MAX_SOURCE_CHARS = 80_000;

    private final ToolRegistry toolRegistry;

    public RenderDiagramTool(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @PostConstruct
    public void register() {
        toolRegistry.register(
                "render_diagram",
                "把 Mermaid 源码或 .mmd/.svg 文件渲染成图片。"
                        + "出架构图/流程图时：先 write_file 写 .mmd，再调本工具产出 .png。"
                        + "source 填 Mermaid 正文或已有文件路径；path 填输出文件（.png 或 .svg）。",
                RenderDiagramParams.class,
                this::handle);
    }

    private String handle(RenderDiagramParams params) {
        try {
            String source = params.getSource();
            if (StringUtils.isBlank(source)) {
                return "{\"success\":false,\"error\":\"source 不能为空\"}";
            }
            String outName = StringUtils.isBlank(params.getPath())
                    ? "diagram.png" : params.getPath().trim();
            Path out = resolveOut(outName);
            Files.createDirectories(out.getParent());

            String body = loadSource(source);
            if (body.length() > MAX_SOURCE_CHARS) {
                return "{\"success\":false,\"error\":\"源码过长（>" + MAX_SOURCE_CHARS + "）\"}";
            }
            if (looksLikeSvg(body) && outName.toLowerCase(Locale.ROOT).endsWith(".svg")) {
                Files.writeString(out, body, StandardCharsets.UTF_8);
                return ok(out);
            }
            if (looksLikeSvg(body)) {
                return "{\"success\":false,\"error\":\"SVG 请把 path 写成 .svg；PNG 请先提供 Mermaid\"}";
            }
            Path mmd = out.resolveSibling(
                    stripExt(out.getFileName().toString()) + ".mmd");
            Files.writeString(mmd, body, StandardCharsets.UTF_8);
            String err = runMmdc(mmd, out);
            if (err != null) {
                return "{\"success\":false,\"error\":\""
                        + err.replace("\\", "\\\\").replace("\"", "'") + "\"}";
            }
            if (!Files.exists(out) || Files.size(out) == 0) {
                return "{\"success\":false,\"error\":\"渲染结束但未生成文件: " + out + "\"}";
            }
            return ok(out);
        } catch (Exception e) {
            log.warn("render_diagram 失败", e);
            return "{\"success\":false,\"error\":\"渲染失败: " + e.getMessage() + "\"}";
        }
    }

    private Path resolveOut(String path) {
        // 走 resolveOutputPath（它带 PathGuard 越界判定），不要直接调 resolveWritePath ——
        // 那是未加约束的内核，绕过它就等于 render_diagram 能往任意路径写文件。
        return BuiltinTools.resolveOutputPath(path);
    }

    private String loadSource(String source) throws IOException {
        String trimmed = source.trim();
        if (looksLikeMermaid(trimmed) || looksLikeSvg(trimmed)) {
            return trimmed;
        }
        Path file = BuiltinTools.resolveOutputPath(trimmed);
        if (Files.isRegularFile(file)) {
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        Path fromRoot = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().resolve(trimmed).normalize();
        if (Files.isRegularFile(fromRoot)) {
            // 项目根在允许范围内；仍走一次判定，避免以后有人改这里时静默放宽
            PathGuard.assertAllowed(fromRoot, "render_diagram");
            return Files.readString(fromRoot, StandardCharsets.UTF_8);
        }
        return trimmed;
    }

    static boolean looksLikeMermaid(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        return t.startsWith("flowchart") || t.startsWith("graph ")
                || t.startsWith("sequenceDiagram") || t.startsWith("classDiagram")
                || t.startsWith("erDiagram") || t.startsWith("stateDiagram")
                || t.startsWith("gantt") || t.startsWith("pie")
                || t.startsWith("mindmap") || t.startsWith("timeline")
                || t.contains("\n    ") && t.contains("-->");
    }

    static boolean looksLikeSvg(String s) {
        return s != null && s.trim().regionMatches(true, 0, "<svg", 0, 4);
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String ok(Path out) throws IOException {
        return "{\"success\":true,\"path\":\""
                + out.toAbsolutePath().toString().replace("\\", "/")
                + "\",\"size\":" + Files.size(out) + "}";
    }

    private String runMmdc(Path input, Path output) throws IOException, InterruptedException {
        String npx = HostCommand.isWindows() ? "npx.cmd" : "npx";
        // 先探有没有，再起进程。没有这一步的话，客户机上会抛
        //   IOException: Cannot run program "npx.cmd": CreateProcess error=2
        // 被 handle 的 catch 兜成 "渲染失败: ..."。会报错，但客户看不出该装什么、
        // 也不知道还有不需要 Node 的替代路径。
        if (!HostCommand.onPath("npx")) {
            return "本机没有 npx（Node.js）。render_diagram 出 PNG 走的是 "
                    + "npx -y @mermaid-js/mermaid-cli，既要有 Node 又要能联网下载该包。"
                    + "替代做法（二选一）："
                    + "1) 输出 SVG —— 把 source 写成 <svg>…</svg> 且 path 用 .svg，不需要任何外部程序；"
                    + "2) 只产出 .mmd 源码文件，交给有渲染环境的一方出图。";
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(npx);
        cmd.add("-y");
        cmd.add("@mermaid-js/mermaid-cli");
        cmd.add("-i");
        cmd.add(input.toAbsolutePath().toString());
        cmd.add("-o");
        cmd.add(output.toAbsolutePath().toString());
        cmd.add("-b");
        cmd.add("white");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.directory(input.getParent() == null
                ? BuiltinTools.effectiveWorkspaceRoot().toFile()
                : input.getParent().toFile());
        Process proc = pb.start();
        boolean finished = proc.waitFor(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            // 探测通过后仍超时，最常见的原因是 npx -y 正在联网下载包而网络慢/不通。
            // 提示里带上这一点，否则客户只会看到"超时"两个字。
            return "mermaid-cli 超时（" + RENDER_TIMEOUT_SECONDS
                    + "s）。注意 npx -y 首次运行需要联网下载 @mermaid-js/mermaid-cli，"
                    + "离线或网络不通时必然超时；此时可改用 <svg> 直出。";
        }
        String logText = new String(proc.getInputStream().readNBytes(800),
                StandardCharsets.UTF_8);
        if (proc.exitValue() != 0) {
            String brief = logText.length() > 400 ? logText.substring(0, 400) : logText;
            return "mermaid-cli 失败 exit=" + proc.exitValue() + " " + brief;
        }
        return null;
    }
}
