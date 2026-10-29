package com.miniagent.web.dto.resp;

/**
 * POST /v1/memory/procedures 的写入结果：落库后的真实状态。
 *
 * <p>以前这个接口返回 {@code Void}，调用方写完就不知道这条 SOP 是第几版、也没有 id 可以去引用它。
 * 而 SOP 的版本与版本内计数正是自我进化闭环的观测面，所以写接口必须把这三样一起交回。</p>
 *
 * @param id           落库后的 Procedure id（升版时保持不变）
 * @param version      当前版本号。内容有变化才 +1；相同内容重复写不会升版
 * @param attemptCount 本版本被节点结算过的次数
 * @param successCount 其中验收通过的次数
 * @param successRate  由上面两个计数重算出的比率，0~1
 */
public record ProcedureWriteResultDTO(Long id, Integer version, long attemptCount,
                                      long successCount, double successRate) {
}
