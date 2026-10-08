import com.miniagent.agent.tool.HostCommand;
import com.miniagent.agent.tool.RenderDiagramTool;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 验 RenderDiagramTool.runMmdc() 在"本机没有 npx"时返回可操作的替代方案，
 * 而不是抛 IOException: Cannot run program "npx.cmd": CreateProcess error=2。
 *
 * 怎么模拟客户机：把 PATH 剥到只剩 C:\\Windows\\System32。
 *   - 这样 `where`（System32\\where.exe）仍然在，探测机制与客户机一致：
 *     真的跑了一次 where、拿到 exit=1，而不是"探测工具本身找不到"。
 *   - Node 装在 Program Files\\nodejs 或 nvm 目录下，被排除 → 等价于客户机没装 Node。
 *
 * 反证：同一份代码在**完整 PATH** 下必须是 onPath("npx")==true，
 * 否则说明这个探针测的不是"缺 npx"而是别的。所以在完整 PATH 下再跑一次并断言 true。
 *
 * 用法：
 *   # 期望 npx 缺失
 *   PATH="C:\\Windows\\System32" java -cp "<cp>" RenderGuardProbe npx-missing
 *   # 期望 npx 存在
 *   java -cp "<cp>" RenderGuardProbe npx-present
 */
public class RenderGuardProbe {

    static int pass = 0;
    static int fail = 0;

    static void check(String label, boolean ok, String detail) {
        if (ok) {
            pass++;
        } else {
            fail++;
        }
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + label
                + (detail == null || detail.isEmpty() ? "" : "  -> " + detail));
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "npx-missing";
        System.out.println("== RenderDiagramTool 守卫: " + mode + " ==");
        System.out.println("   PATH=" + System.getenv("PATH"));

        boolean present = HostCommand.onPath("npx");

        if ("npx-present".equals(mode)) {
            check("完整 PATH 下 onPath(\"npx\") == true（反证：探针确实在测 npx）",
                    present, "present=" + present);
            System.out.println("\n小计: " + pass + " 通过 / " + fail + " 失败");
            System.exit(fail == 0 ? 0 : 1);
        }

        check("剥掉 Node 后 onPath(\"npx\") == false", !present, "present=" + present);
        check("剥掉 Node 后 onPath(\"where\") 仍可为 true（说明是 where 报的 exit=1，"
                        + "不是探测工具自己丢了）",
                HostCommand.onPath("where") || HostCommand.onPath("where.exe"),
                "where=" + HostCommand.onPath("where"));

        // 直接证据：在 JVM 里跑一次 where npx，把它真实的 exit code 与原样输出打出来。
        // 光看 System.getenv("PATH") 不够 —— 实测它会打印改之前的完整 PATH，
        // 而子进程里 where 实际用的并不是那个值。这条才能证明"缺 npx"这件事是真的。
        HostCommand.Exec w = HostCommand.exec(java.time.Duration.ofSeconds(5), "where", "npx");
        System.out.println("   [证据] where npx -> ok=" + w.ok()
                + " output=" + (w.output().isEmpty() ? "(空)" : w.output())
                + " error=" + (w.error().isEmpty() ? "(空)" : w.error()));
        check("where 真的跑起来了（失败原因是 exit=N，不是 IOException = 探测工具自己丢了）",
                w.ok() || w.error().contains("exit="), "error=" + w.error());
        // 注意：where 找不到时把提示写在 stdout（"信息: 用提供的模式无法找到文件。"），
        // 而 redirectErrorStream(true) 会把它并进 output。所以【不能】用 output 是否为空
        // 判断找没找到，必须用 exit code。
        check("where npx 以非 0 退出 = 真的没找到", !w.ok(), "ok=" + w.ok());
        check("where npx 的输出里不含任何路径（不是找到了但没解析出来）",
                !w.output().contains("\\") && !w.output().toLowerCase().contains("npx.cmd"),
                "output=" + w.output());

        Path dir = Files.createTempDirectory("render-guard-probe");
        Path input = dir.resolve("in.mmd");
        Path output = dir.resolve("out.png");
        Files.writeString(input, "flowchart LR\n  A-->B\n");

        RenderDiagramTool tool = new RenderDiagramTool(null);
        Method m = RenderDiagramTool.class.getDeclaredMethod("runMmdc", Path.class, Path.class);
        m.setAccessible(true);

        String result;
        Throwable thrown = null;
        try {
            result = (String) m.invoke(tool, input, output);
        } catch (Throwable t) {
            thrown = t;
            result = null;
        }

        check("没有抛异常（原实现会抛 CreateProcess error=2）", thrown == null,
                thrown == null ? "" : String.valueOf(thrown.getCause()));
        check("返回了非空提示而非 null", result != null && !result.isEmpty(),
                "result=" + result);
        check("提示里点明缺 npx", result != null && result.contains("npx"),
                result == null ? "" : result);
        check("提示里给出可操作的替代方案（提到 svg）",
                result != null && result.toLowerCase().contains("svg"),
                result == null ? "" : result);
        check("提示里【不出现】 CreateProcess error=2（那是对客户无用的原始异常文本）",
                result != null && !result.contains("CreateProcess"),
                result == null ? "" : result);
        check("没有产出文件（守卫生效，压根没起进程）", !Files.exists(output),
                "exists=" + Files.exists(output));

        System.out.println("\n小计: " + pass + " 通过 / " + fail + " 失败");
        System.exit(fail == 0 ? 0 : 1);
    }
}
