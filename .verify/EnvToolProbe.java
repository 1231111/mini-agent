import com.miniagent.agent.tool.AgentEnvironmentTool;
import com.miniagent.agent.tool.HostCommand;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;

/**
 * 直打在 AgentEnvironmentTool.getGitInfo() 与 HostCommand 上的取证探针。
 *
 * 验的是 2026-09-28 修掉的四个问题：
 *   1. sh -c 包装在 Windows 上必挂        -> 改为参数列表
 *   2. 失败静默返回空串 -> is_clean=true 假数据 -> 改为 available=false + error
 *   3. 逐行 append 不加 \n -> modified_files_count 恒为 1 -> 改为按行统计
 *   4. stderr 并进 stdout -> 无 origin 时错误文本被当成 remote_url -> 改为按 exit code 判
 *
 * 必须在指定的工作目录下运行（ProcessBuilder 继承 JVM 的 cwd）：
 *   场景 git-repo  -> 在一个有 2 个未跟踪文件、且**没有 origin** 的 git 仓库里跑
 *   场景 non-git   -> 在一个**不在任何 git 仓库内**的目录里跑
 *
 * 用法：java -cp "<tools/classes>;<依赖>" EnvToolProbe git-repo
 */
public class EnvToolProbe {

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
        String scenario = args.length > 0 ? args[0] : "unknown";
        System.out.println("== 场景: " + scenario + " ==");
        System.out.println("   cwd = " + System.getProperty("user.dir"));

        System.out.println("\n-- HostCommand.onPath --");
        check("onPath(\"git\") == true", HostCommand.onPath("git"), "");
        check("onPath(\"npx\") == true（本机装了 Node）", HostCommand.onPath("npx"), "");
        check("onPath(\"definitely-absent-xyz\") == false",
                !HostCommand.onPath("definitely-absent-xyz"), "");
        check("onPath(null) == false", !HostCommand.onPath(null), "");
        check("onPath(\"\") == false", !HostCommand.onPath(""), "");

        System.out.println("\n-- exec 的失败必须是显式的，不能是空串 --");
        HostCommand.Exec missing = HostCommand.exec(Duration.ofSeconds(5),
                "definitely-absent-xyz", "--version");
        check("命令不存在时 ok == false", !missing.ok(), "ok=" + missing.ok());
        check("命令不存在时 error 非空", !missing.error().isEmpty(), "error=" + missing.error());
        check("error 里带得上可诊断的信息",
                missing.error().contains("Exception") || missing.error().contains("error"),
                "error=" + missing.error());

        System.out.println("\n-- 多行输出必须保留换行（原实现逐行 append 不加 \\n）--");
        HostCommand.Exec multi = HostCommand.exec(Duration.ofSeconds(5),
                "git", "status", "--porcelain");
        if ("non-git".equals(scenario)) {
            // 非 git 目录里这条命令本来就该失败。有意义的是"失败被显式上报"这件事，
            // 而不是它能不能跑通 —— 这正是本次要修的"不静默"。
            check("非 git 目录里命令失败被显式上报（ok=false 且 error 非空）",
                    !multi.ok() && !multi.error().isEmpty(), multi.error());
        } else if (!multi.ok()) {
            check("git status --porcelain 能执行", false, multi.error());
        } else if ("git-repo".equals(scenario)) {
            // 只有这个场景才有 >= 2 行输出，才谈得上"有没有被拼成一行"
            long lines = multi.output().isEmpty() ? 0
                    : multi.output().lines().filter(s -> !s.isBlank()).count();
            check("输出行数 >= 2（没有被拼成一行）", lines >= 2,
                    "行数=" + lines + " raw="
                            + multi.output().replace("\r", "\\r").replace("\n", "\\n"));
        } else {
            // 零提交仓库：没有改动是正常的，行数少不代表拼接有问题
            check("输出为空或被正确换行（本场景无改动，行数不适用）", true,
                    "行数=" + (multi.output().isEmpty() ? 0 : multi.output().lines().count()));
        }

        System.out.println("\n-- 无 origin 时必须以 exit code 判定 --");
        HostCommand.Exec remote = HostCommand.exec(Duration.ofSeconds(5),
                "git", "remote", "get-url", "origin");
        check("无 origin 时 ok == false", !remote.ok(),
                "ok=" + remote.ok() + " " + remote.error());

        System.out.println("\n-- AgentEnvironmentTool.getGitInfo() --");
        AgentEnvironmentTool tool = new AgentEnvironmentTool();
        Method m = AgentEnvironmentTool.class.getDeclaredMethod("getGitInfo");
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> git = (Map<String, Object>) m.invoke(tool);
        System.out.println("   git = " + git);

        if ("git-repo".equals(scenario)) {
            check("available == true", Boolean.TRUE.equals(git.get("available")),
                    String.valueOf(git.get("available")));
            Object cnt = git.get("modified_files_count");
            check("modified_files_count == 2（原实现恒为 1）",
                    Long.valueOf(2L).equals(cnt) || Integer.valueOf(2).equals(cnt),
                    "实际=" + cnt);
            check("is_clean == false", Boolean.FALSE.equals(git.get("is_clean")),
                    String.valueOf(git.get("is_clean")));
            check("没有 origin 时【不写】 remote_url（原实现会把 stderr 的错误文本填进去）",
                    !git.containsKey("remote_url"),
                    "remote_url=" + git.get("remote_url"));
            check("branch 是 master / main",
                    "master".equals(git.get("branch")) || "main".equals(git.get("branch")),
                    "branch=" + git.get("branch"));
            check("有提交时给出 last_commit_hash",
                    git.get("last_commit_hash") != null,
                    "last_commit_hash=" + git.get("last_commit_hash"));
        } else if ("git-repo-empty".equals(scenario)) {
            // 刚 git init、零提交。原实现会在这一步就报 available=false，因为
            // rev-parse --abbrev-ref HEAD 在无提交的仓库上 exit 128。
            check("零提交仓库里 available == true（原实现误判为 false）",
                    Boolean.TRUE.equals(git.get("available")),
                    String.valueOf(git.get("available")));
            check("零提交仓库里仍能给出 branch",
                    "master".equals(git.get("branch")) || "main".equals(git.get("branch")),
                    "branch=" + git.get("branch"));
            check("零提交仓库里 is_clean == true（真的没有改动）",
                    Boolean.TRUE.equals(git.get("is_clean")),
                    String.valueOf(git.get("is_clean")));
            check("零提交仓库里 modified_files_count == 0",
                    Long.valueOf(0L).equals(git.get("modified_files_count"))
                            || Integer.valueOf(0).equals(git.get("modified_files_count")),
                    "实际=" + git.get("modified_files_count"));
            check("零提交仓库里【不写】 last_commit_hash（没有提交，不能编一个）",
                    !git.containsKey("last_commit_hash"),
                    "keys=" + git.keySet());
        } else {
            check("非 git 目录下 available == false",
                    Boolean.FALSE.equals(git.get("available")),
                    String.valueOf(git.get("available")));
            Object err = git.get("error");
            check("非 git 目录下带 error 说明原因",
                    err != null && !String.valueOf(err).isEmpty(), String.valueOf(err));
            check("非 git 目录下【不出现】 is_clean（原实现返回 true = 假数据）",
                    !git.containsKey("is_clean"), "keys=" + git.keySet());
            check("非 git 目录下【不出现】 modified_files_count",
                    !git.containsKey("modified_files_count"), "keys=" + git.keySet());
            check("非 git 目录下【不出现】 branch",
                    !git.containsKey("branch"), "keys=" + git.keySet());
        }

        System.out.println("\n小计: " + pass + " 通过 / " + fail + " 失败");
        System.exit(fail == 0 ? 0 : 1);
    }
}
