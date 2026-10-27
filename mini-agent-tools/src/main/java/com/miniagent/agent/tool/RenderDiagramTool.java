package com.miniagent.agent.tool;

import com.miniagent.agent.tool.impl.RenderDiagramParams;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * PNG 默认放大倍数（对应 mermaid-cli 的 {@code -s}）。
     *
     * <p>mermaid-cli 的 {@code -s} 默认就是 1，也就是「SVG 固有尺寸是多少 CSS px、PNG 就出多少像素」。
     * 2026-10-27 实测：一张 40 节点 / 6 个 subgraph 的架构图，固有尺寸只有 520×3001，
     * 每个中文字符落到 3-4px —— 在对话区里点开放大全是插值糊块，这就是「图不清晰」的全部原因。
     * 3 倍渲染出 1560×9003，耗时 10s、454KB，仍在 90s 外层闸门内。</p>
     *
     * <p><b>不要用 {@code --size} 提升清晰度。</b>那个参数是「让最长边等于给定值」，
     * 对又高又窄的架构图是反向效果：{@code --size 2048} 把 520×3001 变成 354×2046，
     * 比不传还糊。等比放大的唯一正确参数是 {@code -s}。</p>
     */
    static final int DEFAULT_PNG_SCALE = 3;
    static final int MIN_PNG_SCALE = 1;
    static final int MAX_PNG_SCALE = 4;

    private final ToolRegistry toolRegistry;

    public RenderDiagramTool(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @PostConstruct
    public void register() {
        toolRegistry.register(
                "render_diagram",
                "把 Mermaid 源码渲染成图片。出架构图/流程图时优先产出 .svg —— "
                        + "矢量图放大任意倍数都清晰、文件也更小；确需位图（插入文档、粘贴到聊天）才用 .png，"
                        + "PNG 默认按 3 倍分辨率渲染。"
                        + "source 填 Mermaid 正文或已有文件路径；path 填输出文件（推荐 .svg，或 .png）。",
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
                pinSvgFile(out);
                return ok(out);
            }
            if (looksLikeSvg(body)) {
                return "{\"success\":false,\"error\":\"SVG 请把 path 写成 .svg；PNG 请先提供 Mermaid\"}";
            }
            Path mmd = out.resolveSibling(
                    stripExt(out.getFileName().toString()) + ".mmd");
            Files.writeString(mmd, body, StandardCharsets.UTF_8);
            String err = runMmdc(mmd, out, resolveScale(params.getScale()));
            if (err != null) {
                return "{\"success\":false,\"error\":\""
                        + err.replace("\\", "\\\\").replace("\"", "'") + "\"}";
            }
            if (!Files.exists(out) || Files.size(out) == 0) {
                return "{\"success\":false,\"error\":\"渲染结束但未生成文件: " + out + "\"}";
            }
            if (!isRaster(out)) {
                pinSvgFile(out);
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

    /** 位图输出：只有这类文件受 {@code -s} 影响。 */
    static boolean isRaster(Path output) {
        String name = output.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg");
    }

    private static final Pattern SVG_ROOT_TAG =
            Pattern.compile("<svg\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern VIEW_BOX_ATTR = Pattern.compile(
            "viewBox\\s*=\\s*\"\\s*([-\\d.]+)[\\s,]+([-\\d.]+)[\\s,]+([-\\d.]+)[\\s,]+([-\\d.]+)\\s*\"");
    private static final Pattern WIDTH_ATTR = Pattern.compile("\\swidth\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern HEIGHT_ATTR = Pattern.compile("\\sheight\\s*=\\s*\"([^\"]*)\"");

    /**
     * 给「只有 viewBox、宽度是百分比」的 SVG 根元素补上固宽固高。
     *
     * <p>mermaid-cli 写出来的是 {@code width="100%" viewBox="4 4 519.4375 3001"}。
     * 这种 SVG 被 {@code <img>} 加载时<b>没有固有尺寸</b>，Chrome 会回落到默认替换尺寸
     * —— 2026-10-27 实测 naturalWidth 是 <b>26×150</b>。后果是对话区里缩成一条竖线，
     * 预览层按 26px 算适配比例，越放越离谱。把 viewBox 的宽高写进 width/height 之后，
     * naturalWidth 才是真实的 519×3001。</p>
     *
     * <p>已经是固定像素宽高的不动 —— 那是调用方有意指定的显示尺寸，覆盖它属于越界。</p>
     */
    static String pinSvgSize(String svg) {
        if (svg == null) {
            return null;
        }
        Matcher root = SVG_ROOT_TAG.matcher(svg);
        if (!root.find()) {
            return svg;
        }
        String tag = root.group();
        Matcher width = WIDTH_ATTR.matcher(tag);
        if (width.find() && !width.group(1).trim().endsWith("%")) {
            return svg;
        }
        Matcher viewBox = VIEW_BOX_ATTR.matcher(tag);
        if (!viewBox.find()) {
            return svg;
        }
        String w = viewBox.group(3);
        String h = viewBox.group(4);
        if (!positive(w) || !positive(h)) {
            return svg;
        }
        String cleaned = HEIGHT_ATTR.matcher(WIDTH_ATTR.matcher(tag).replaceFirst("")).replaceFirst("");
        String pinned = cleaned.replaceFirst("<svg",
                "<svg width=\"" + w + "\" height=\"" + h + "\"");
        return svg.substring(0, root.start()) + pinned + svg.substring(root.end());
    }

    private static boolean positive(String number) {
        try {
            return Double.parseDouble(number) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 修正失败不影响出图：图能用，只是前端会按 26×150 显示，日志留一条线索。 */
    private void pinSvgFile(Path svg) {
        try {
            String text = Files.readString(svg, StandardCharsets.UTF_8);
            String pinned = pinSvgSize(text);
            if (!pinned.equals(text)) {
                Files.writeString(svg, pinned, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("SVG 尺寸修正失败: {}", e.getMessage());
        }
    }

    /** 把调用方给的 scale 夹到 [1,4]；空值用默认 3。 */
    static int resolveScale(Integer raw) {
        if (raw == null) {
            return DEFAULT_PNG_SCALE;
        }
        return Math.max(MIN_PNG_SCALE, Math.min(MAX_PNG_SCALE, raw));
    }

    private static String ok(Path out) throws IOException {
        // url 只在「文件直接落在工作区根、且是图片」时给：对话区靠 /api/workspace-media 回显，
        // 没有这个字段模型就会自己编路径，图必然裂（2026-10-22 实证）。
        String url = BuiltinTools.workspaceMediaUrl(out);
        return "{\"success\":true,\"path\":\""
                + out.toAbsolutePath().toString().replace("\\", "/")
                + "\",\"size\":" + Files.size(out)
                + pixelFields(out)
                + (url == null ? "" : ",\"url\":\"" + url + "\"")
                + "}";
    }

    /**
     * 位图产物的原始像素尺寸。
     *
     * <p>以前不回这个，糊了也没有任何可判据 —— 模型只能凭「文件已生成」宣布成功。
     * 现在 520×3001 这种一眼就偏小的结果会出现在工具返回值里。</p>
     *
     * <p>只读文件头，不解码像素：14M 像素的 PNG 全解一遍要几百 MB 堆。</p>
     */
    static String pixelFields(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".png") && !name.endsWith(".jpg") && !name.endsWith(".jpeg")) {
            return "";
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) {
                return "";
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return "";
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                return ",\"width\":" + reader.getWidth(0) + ",\"height\":" + reader.getHeight(0);
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * mermaid-cli 命令行。
     *
     * <p>抽成静态方法的理由不是「为了测试」—— 是把「命令怎么拼」和「进程怎么起」分开：
     * 前者是纯函数可以钉住，后者要碰 {@code ProcessBuilder} 和 PATH。
     * 「位图必须带 {@code -s}」「矢量不能带 {@code -s}」是清晰度修复的核心，
     * 光靠人工读代码守不住。</p>
     */
    static List<String> mmdcCommand(String npx, Path input, Path output, int scale) {
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
        // 只有位图需要 -s。SVG 是矢量，放大倍数不改变文件内容，
        // 传了反而会让 mermaid-cli 多走一遍截图链路。
        if (isRaster(output)) {
            cmd.add("-s");
            cmd.add(String.valueOf(scale));
        }
        return cmd;
    }

    private String runMmdc(Path input, Path output, int scale) throws IOException, InterruptedException {
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
        List<String> cmd = mmdcCommand(npx, input, output, scale);
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
            return "mermaid-cli 超时（" + RENDER_TIMEOUT_SECONDS + "s，scale=" + scale + "）。"
                    + "scale 越大位图越清晰但越慢：可把 scale 降到 1-2 重试；"
                    + "或改出 .svg（矢量，不需要截图，通常几秒完成且放大不糊）。"
                    + "另：npx -y 首次运行需要联网下载 @mermaid-js/mermaid-cli，离线或网络不通时必然超时。";
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
