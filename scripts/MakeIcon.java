import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 生成 mini-agent-desktop/assets/icon.png（1024×1024）。
 *
 * <p>为什么用 Java 而不是 PIL / sharp 之类：这台机器上没有 PIL，numpy 也没有，
 * 而 JDK 的 {@code javax.imageio} + {@code java.awt} 本来就在（构建桌面端本来就需要 JDK）。
 * 少一个工具链依赖，CI 上也少一次安装。
 *
 * <p>图形是三节点 DAG —— 对应 MiniAgent 的任务分解：两个上游节点汇到一个下游节点。
 *
 * <p>用法（JDK 11+ 单文件源码运行，不需要先编译）：
 * <pre>
 *   java scripts/MakeIcon.java mini-agent-desktop/assets/icon.png
 * </pre>
 *
 * <p>electron-builder 会拿这张 png 自行生成 Windows 的 .ico 与 macOS 的 .icns，
 * 所以三平台只需要这一份源文件 —— 尺寸必须 ≥ 512×512，macOS 的 icns 才够用。
 */
public final class MakeIcon {

    /** 1024 对 512 是 2 倍余量；macOS 的 icns 需要 512@2x = 1024。 */
    private static final int SIZE = 1024;

    private static final Color BG_TOP = new Color(0x7C, 0x8F, 0xF0);
    private static final Color BG_BOTTOM = new Color(0x53, 0x66, 0xC8);

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "mini-agent-desktop/assets/icon.png");

        BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // 背景：圆角方形 + 斜向渐变。圆角外的区域保持透明，这样在深色 / 浅色任务栏上都干净。
        g.setPaint(new GradientPaint(0, 0, BG_TOP, SIZE, SIZE, BG_BOTTOM));
        g.fill(new RoundRectangle2D.Float(0, 0, SIZE, SIZE, 232, 232));

        // 两个上游节点汇入一个下游节点
        final int ax = 336, ay = 356;
        final int bx = 688, by = 356;
        final int cx = 512, cy = 700;

        // 连线先画，随后被节点圆覆盖，省去手工算切线端点
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(40f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Float(ax, ay, cx, cy));
        g.draw(new Line2D.Float(bx, by, cx, cy));

        final int r = 116;
        g.fill(new Ellipse2D.Float(ax - r, ay - r, r * 2f, r * 2f));
        g.fill(new Ellipse2D.Float(bx - r, by - r, r * 2f, r * 2f));
        g.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2f, r * 2f));

        g.dispose();

        File parent = out.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("无法创建目录：" + parent);
        }
        if (!ImageIO.write(img, "png", out)) {
            throw new IllegalStateException("当前 JDK 没有 PNG writer");
        }
        System.out.println("已生成 " + out.getAbsolutePath() + "（" + SIZE + "×" + SIZE + "，" + out.length() + " 字节）");
    }
}
