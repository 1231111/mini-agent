package com.miniagent.agent.execution;

/**
 * 共享资源锁没等到 —— 与「工具执行超时」（{@code TIMEOUT}）语义完全不同。
 *
 * <p>这里工具<b>根本没开始跑</b>，没有任何副作用，稍后重试即可
 * （错误码 {@code RESOURCE_BUSY}，可重试）。</p>
 *
 * <h2>为什么必须有这个异常</h2>
 *
 * <p>改造前锁等待是 {@code Semaphore.acquire()}，<b>无限阻塞</b>。于是长耗时工具
 * （{@code comfyui_img2video} 跑 620 秒）持锁期间，别的会话里任何一个写类工具都只能
 * 一路阻塞到撞自己的外层闸门 —— 而写类工具超时会被判成「终态未知」（{@code OUTCOME_UNKNOWN}）
 * 并<b>中止整轮任务</b>。一次慢生成就能把毫不相干的多步任务成批打死。</p>
 *
 * <p>现在锁等待有界（秒数由 {@code ToolExecutionProfile.lockWaitSeconds} 声明），
 * 到点就退让并抛这个异常。<b>绝不让「没轮到」升级成「整轮中止」。</b></p>
 */
public class ToolLockTimeoutException extends Exception {

    private final String toolName;
    private final String resourceDescription;
    private final long lockWaitSeconds;

    public ToolLockTimeoutException(String toolName, String resourceDescription, long lockWaitSeconds) {
        super("工具 " + toolName + " 等待" + resourceDescription + " 超过 "
                + lockWaitSeconds + " 秒仍未拿到；本次未执行、无副作用，稍后可安全重试");
        this.toolName = toolName;
        this.resourceDescription = resourceDescription;
        this.lockWaitSeconds = lockWaitSeconds;
    }

    public String toolName() {
        return toolName;
    }

    public String resourceDescription() {
        return resourceDescription;
    }

    public long lockWaitSeconds() {
        return lockWaitSeconds;
    }
}
