package com.miniagent.memory.port;

/**
 * 结算前置的 SOP 投影端口。
 *
 * <p>实现可以是本机数据库，也可以是独立记忆服务。调用方只负责「把这一版交出去」，
 * 不关心它落在哪个库。投影必须幂等：同一个版本重复投递不得改变统计计数。</p>
 */
public interface ProcedureDefinitionPort {

    /**
     * 投影一条 SOP 的指定版本。不得修改该版本的结算计数。
     *
     * @throws RuntimeException 投影失败。调用方必须保留待重试的证据，不能静默丢弃——
     *                          投影缺失会让之后所有针对该版本的结算变成「过期版本」。
     */
    ProcedureDefinitionResult synchronize(ProcedureDefinitionCommand command);
}
