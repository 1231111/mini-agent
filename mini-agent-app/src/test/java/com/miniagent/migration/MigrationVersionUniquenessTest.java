package com.miniagent.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迁移版本号必须全局唯一（跨 SQL 与 Java 两条链）。
 *
 * <p><b>为什么这条要写成测试</b>：真实事故 —— 有人把
 * {@code V9__procedure_application_settlement.sql} 改写成 Java 的 V12，
 * 源码里的 SQL 删了，但 Maven <b>不会</b>删 {@code target/classes} 里已复制的旧资源。
 * 于是运行期同一条 location 链上出现两个 V9，Flyway 在 {@code validate} 阶段抛
 * {@code Found more than one migration with version 9}，客户端启动 code=1。
 *
 * <p>这个缺陷在**编译、单测、打包**三个环节全都能"通过"—— 只有真正启动才炸。
 * 所以这里从 <b>运行期 classpath</b>（而不是源码目录）读迁移，才能看见 target/classes 里的残留：
 * 测试跑在 test 阶段，classpath 里正好就是即将被打包的那份产物。
 */
class MigrationVersionUniquenessTest {

    private static final Pattern SQL_FILE = Pattern.compile("^V(\\d+(?:\\.\\d+)*)__(.+)\\.sql$");
    private static final Pattern CLASS_FILE = Pattern.compile("^V(\\d+(?:\\.\\d+)*)__(.+)\\.class$");

    @Test
    void versionsAreUniqueAcrossSqlAndJavaMigrations() throws Exception {
        Map<String, List<String>> byVersion = new TreeMap<>();

        // ① SQL 迁移：classpath:db/migration
        for (Path dir : classpathDirs("db/migration")) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(Files::isRegularFile).forEach(p -> {
                    Matcher m = SQL_FILE.matcher(p.getFileName().toString());
                    if (m.matches()) {
                        byVersion.computeIfAbsent(m.group(1), k -> new ArrayList<>())
                                .add("SQL  " + p.getFileName() + "  ← " + dir);
                    }
                });
            }
        }

        // ② Java 迁移：classpath:com/miniagent/migration
        for (Path dir : classpathDirs("com/miniagent/migration")) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(Files::isRegularFile).forEach(p -> {
                    String name = p.getFileName().toString();
                    // 内部类（V13__Xxx$Row.class）也匹配类名模式，但它们是同一个迁移的一部分，
                    // 不是独立迁移 —— 不排除就会给每个带内部类的迁移造出一个假"重复版本"。
                    if (name.contains("$")) {
                        return;
                    }
                    Matcher m = CLASS_FILE.matcher(name);
                    if (!m.matches()) {
                        return;
                    }
                    String desc = javaMigrationVersion(p, m.group(1));
                    byVersion.computeIfAbsent(desc, k -> new ArrayList<>())
                            .add("JAVA " + p.getFileName() + "  ← " + dir);
                });
            }
        }

        assertTrue(!byVersion.isEmpty(), "一个迁移都没扫到，说明 classpath 定位失效（测试本身失效）");

        Map<String, List<String>> dupes = new LinkedHashMap<>();
        byVersion.forEach((version, owners) -> {
            if (owners.size() > 1) {
                dupes.put(version, owners);
            }
        });

        if (!dupes.isEmpty()) {
            StringBuilder sb = new StringBuilder(
                    "同一版本号被多个迁移占用（Flyway 会拒绝启动）。\n"
                            + "最常见原因是构建残留：源码里删掉/改名过的迁移仍留在 target/classes "
                            + "或已打包的 jar 里 —— 先 `mvn clean` 再打包。\n");
            dupes.forEach((version, owners) -> {
                sb.append("  版本 ").append(version).append(":\n");
                owners.forEach(o -> sb.append("    - ").append(o).append('\n'));
            });
            throw new AssertionError(sb.toString());
        }
    }

    /**
     * 取 Java 迁移的真实版本号（Flyway 用类名解析，但允许子类覆写 getVersion()）。
     * 加载失败时回退到文件名里的版本 —— 宁可少报也不让测试自己崩掉。
     */
    private String javaMigrationVersion(Path classFile, String versionFromName) {
        String simpleName = classFile.getFileName().toString().replace(".class", "");
        try {
            Class<?> type = Class.forName("com.miniagent.migration." + simpleName);
            if (BaseJavaMigration.class.isAssignableFrom(type)) {
                BaseJavaMigration instance = (BaseJavaMigration) type.getDeclaredConstructor().newInstance();
                if (instance.getVersion() != null) {
                    return instance.getVersion().getVersion();
                }
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // 回退到文件名版本
        }
        return versionFromName;
    }

    /** 把一个 classpath 目录名解析成本地目录（测试期都是 file: URL）。 */
    private List<Path> classpathDirs(String resource) throws IOException {
        List<Path> dirs = new ArrayList<>();
        Enumeration<URL> urls = getClass().getClassLoader().getResources(resource);
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            if (!"file".equals(url.getProtocol())) {
                continue;   // 打进 jar 的场景（不会在测试期出现）
            }
            try {
                dirs.add(Path.of(url.toURI()));
            } catch (Exception ignored) {
                // 路径解析失败就跳过，不让环境问题伪装成断言失败
            }
        }
        return dirs;
    }
}
