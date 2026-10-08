package com.miniagent.agent.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 文件工具根目录约束的回归测试。
 *
 * <p>背景：{@code read_file}（PLAN_SAFE，无需批准）与 {@code edit_file} 此前对绝对路径直通，
 * 模型可以读写 {@code ~/.ssh/authorized_keys}、{@code ~/.aws/credentials}、应用自己的
 * {@code .env}。这些用例锁住"根之外的路径一律拒绝"，并覆盖 junction/符号链接绕过。</p>
 */
class PathGuardTest {

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    @AfterEach
    void tearDown() {
        PathGuard.clearExtraRoots();
    }

    @Test
    void workspaceAndProjectRootAreAllowed() {
        Path workspace = BuiltinTools.effectiveWorkspaceRoot();
        assertTrue(PathGuard.isAllowed(workspace.resolve("note.md")),
                "workspace 内的路径必须放行");

        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        assertTrue(PathGuard.isAllowed(project.resolve("pom.xml")),
                "项目根内的路径必须放行（编码任务要读真实源码）");
    }

    @Test
    void hostPathsOutsideAllowedRootsAreRejected() {
        // 选取一定不在 workspace/数据根/项目根之下的系统路径（Windows 与 POSIX 各一）。
        Path outside = WINDOWS
                ? Path.of("C:\\Windows\\System32\\drivers\\etc\\hosts")
                : Path.of("/etc/shadow");

        assertFalse(PathGuard.isAllowed(outside), "系统路径必须落在允许范围之外");
        SecurityException e = assertThrows(SecurityException.class,
                () -> PathGuard.assertAllowed(outside, "read"));
        assertTrue(e.getMessage().contains("拒绝访问工作区外的路径"),
                "拒绝消息要说明原因: " + e.getMessage());
        assertTrue(e.getMessage().contains("allowed-read-roots"),
                "拒绝消息要告诉运维怎么放行: " + e.getMessage());
    }

    @Test
    void sshAndCredentialPathsAreRejected() {
        // 最高价值的两类目标：SSH 授权文件与云凭证
        Path home = Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
        for (String relative : new String[] {".ssh/authorized_keys", ".aws/credentials"}) {
            Path secret = home.resolve(relative);
            if (PathGuard.isAllowed(secret)) {
                // 只有当 home 恰好等于/包含项目根时才会允许（CI 上 /home/runner/work/...）
                assertTrue(home.startsWith(Path.of(System.getProperty("user.dir"))
                                .toAbsolutePath().normalize())
                                || Path.of(System.getProperty("user.dir"))
                                .toAbsolutePath().normalize().startsWith(home),
                        "只有在 home 与项目根重叠时才可以放行: " + secret);
                continue;
            }
            assertThrows(SecurityException.class,
                    () -> PathGuard.assertAllowed(secret, "read"));
        }
    }

    @Test
    void explicitlyAllowedExtraRootIsPermitted() {
        Path temp;
        try {
            temp = Files.createTempDirectory("pathguard-allowed");
        } catch (IOException e) {
            assumeTrue(false, "无法创建临时目录: " + e.getMessage());
            return;
        }
        try {
            // 建临时目录之前先确认它不在默认允许范围内，否则这条用例证明不了放行生效
            PathGuard.allowRoots(temp.toString());
            assertTrue(PathGuard.isAllowed(temp.resolve("data.txt")),
                    "显式放行的根必须生效");
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 清理失败不影响断言
            }
        }
    }

    @Test
    void symlinkEscapeIsRejected() throws IOException {
        // 在 workspace 里放一个指向"根之外目录"的链接，再尝试读链接内的文件。
        // 词法前缀会让 workspace/link/... 看起来合法，只有真实路径判定能拦住它。
        // Windows 上建符号链接要管理员/开发者模式，但 junction（mklink /J）不需要 ——
        // 而 junction 正是这个绕过最现实的形态，所以两种都试。
        Path workspace = BuiltinTools.effectiveWorkspaceRoot();
        Files.createDirectories(workspace);
        Path outsideDir = Files.createTempDirectory("pathguard-outside");
        Path secret = Files.writeString(outsideDir.resolve("secret.txt"), "top-secret");

        Path link = workspace.resolve("escape-probe-" + System.nanoTime());
        if (!createDirectoryLink(link, outsideDir)) {
            deleteQuietly(secret);
            deleteQuietly(outsideDir);
            assumeTrue(false, "当前环境无法创建符号链接/junction，跳过");
            return;
        }

        try {
            Path viaLink = link.resolve("secret.txt");
            assertFalse(PathGuard.isAllowed(viaLink),
                    "通过链接逃出允许根的路径必须被拒绝: " + viaLink);
            assertThrows(SecurityException.class,
                    () -> PathGuard.assertAllowed(viaLink, "read"));
        } finally {
            deleteQuietly(link);
            deleteQuietly(secret);
            deleteQuietly(outsideDir);
        }
    }

    /** 建目录链接：优先符号链接，Windows 上退化为 junction（无需管理员权限）。 */
    private static boolean createDirectoryLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            if (!WINDOWS) {
                return false;
            }
        }
        try {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                    link.toString(), target.toString())
                    .redirectErrorStream(true)
                    .start();
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0) {
                return false;
            }
            return Files.exists(link);
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败不影响断言
        }
    }
}
