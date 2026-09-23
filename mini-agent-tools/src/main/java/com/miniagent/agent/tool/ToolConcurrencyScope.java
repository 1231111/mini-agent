package com.miniagent.agent.tool;

/**
 * 工具共享资源的串行化范围。
 *
 * <p>改造前只有 {@link #GLOBAL} 一种「写类工具共用一把锁」，于是本地文件系统、
 * 本地 GPU、远程 API 配额这三类<b>互不相干</b>的资源被挤到同一个 {@code "global"} 键上：
 * {@code comfyui_img2video} 跑 620 秒会把 {@code write_file} 一起堵死。
 * 现在按「实际抢的是什么资源」分开。</p>
 */
public enum ToolConcurrencyScope {

    /** 完全不抢共享资源，不需要锁。只受全局并发信号量限制。 */
    NONE,

    /**
     * 所有未分键的写类工具共用一把锁。
     *
     * <p>语义是「本地文件系统 + 本机进程」的互斥：{@code exec_command} 写命令、
     * {@code delete_path}、{@code move_file} 这类会改同一份本地状态，串行是正确的。</p>
     */
    GLOBAL,

    /** 同一会话内互斥（浏览器那一个页面、用户那一条提问）。 */
    SESSION,

    /** 按「工具名 + 某个参数值」分锁：同一条参数串行，不同参数并行。 */
    ARGUMENT,

    /**
     * 按命名的外部共享资源分锁，资源名写在 {@code ToolExecutionProfile.sharedResourceKey}。
     *
     * <p>适用：抢同一块 GPU 的 {@code comfyui_*}（键 {@code comfyui-gpu}）、
     * 有配额的远程生成 API。与 {@link #GLOBAL} 的区别在于<b>不碰本地文件系统</b>，
     * 所以不该跟写文件共用一把锁。</p>
     */
    SHARED_RESOURCE
}
