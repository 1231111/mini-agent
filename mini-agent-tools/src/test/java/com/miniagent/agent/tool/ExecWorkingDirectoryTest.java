package com.miniagent.agent.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code exec_command} 的工作目录必须跟着"生效工作区"走。
 *
 * <p>这里守的是两条真实缺陷：
 * <ol>
 *   <li>审计 CMD-05：子 Agent 的文件工具被隔离到 {@code workspace/_sub/<id>/}，
 *       但命令仍在父工作区执行 —— 构建产物写进父目录，"隔离边界"名存实亡；</li>
 *   <li>会话绑定项目目录后，文件写进项目、命令却跑在 {@code {dataDir}/workspace}，
 *       用户看到的是"agent 说在项目里改了，可项目里没有构建输出"。</li>
 * </ol>
 * 这条只能靠单元测试守：要真实跑一次 agent 才能从外部观察到。</p>
 */
class ExecWorkingDirectoryTest {

    @TempDir
    Path temp;

    @AfterEach
    void clear() {
        WorkspaceContext.clearAll();
    }

    @Test
    void followsOverriddenRoot() {
        Path project = temp.resolve("project");
        WorkspaceContext.setRootOverride(project);

        File dir = BuiltinTools.execWorkingDirectory();

        assertEquals(project.toAbsolutePath().normalize().toFile(), dir);
    }

    @Test
    void fallsBackToDefaultRootWithoutOverride() {
        WorkspaceContext.clearAll();

        assertEquals(BuiltinTools.effectiveWorkspaceRoot().toFile(), BuiltinTools.execWorkingDirectory());
    }

    @Test
    void createsDirectoryWhenMissing() {
        Path notYet = temp.resolve("will/be/created");
        WorkspaceContext.setRootOverride(notYet);

        File dir = BuiltinTools.execWorkingDirectory();

        assertTrue(dir.isDirectory(), "命令执行目录必须存在，否则 ProcessBuilder 直接抛 IOException");
        assertTrue(Files.isDirectory(notYet), notYet.toString());
    }
}
