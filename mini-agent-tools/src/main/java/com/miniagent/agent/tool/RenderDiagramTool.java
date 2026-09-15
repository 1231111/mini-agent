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
        return BuiltinTools.resolveWritePath(
                BuiltinTools.effectiveWorkspaceRoot(),
                BuiltinTools.writeTaskDir(), path, false);
    }

    private String loadSource(String source) throws IOException {
        String trimmed = source.trim();
        if (looksLikeMermaid(trimmed) || looksLikeSvg(trimmed)) {
            return trimmed;
        }
        Path file = BuiltinTools.resolveWritePath(
                BuiltinTools.effectiveWorkspaceRoot(),
                BuiltinTools.writeTaskDir(), trimmed, false);
        if (Files.isRegularFile(file)) {
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        Path fromRoot = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().resolve(trimmed).normalize();
        if (Files.isRegularFile(fromRoot)) {
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
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
                .contains("win");
        List<String> cmd = new ArrayList<>();
        if (win) {
            cmd.add("npx.cmd");
        } else {
            cmd.add("npx");
        }
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
            return "mermaid-cli 超时（" + RENDER_TIMEOUT_SECONDS + "s）";
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
