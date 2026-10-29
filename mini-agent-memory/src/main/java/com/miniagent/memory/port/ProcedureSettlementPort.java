package com.miniagent.memory.port;

/**
 * Procedure 应用终态结算端口。
 *
 * <p>本地实现可以直接调用数据库；云端实现可以通过认证的内部 API 调用记忆服务。
 * 调用方只依赖该窄端口，不依赖记忆服务的 Entity、Repository 或 Spring 类型。</p>
 */
public interface ProcedureSettlementPort {
    ProcedureSettlementResult settle(ProcedureSettlementCommand command);
}
