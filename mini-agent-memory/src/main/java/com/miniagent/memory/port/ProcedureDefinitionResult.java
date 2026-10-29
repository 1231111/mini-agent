package com.miniagent.memory.port;

/** 一次 SOP 投影的结果。调用方据此区分「已收敛」「可重试」「必须人工确认」。 */
public record ProcedureDefinitionResult(Status status, Integer currentVersion) {

    public enum Status {
        CREATED,
        UPDATED,
        UNCHANGED,
        /** 投递的版本比服务端旧：乱序或重试的正常结果，已收敛，不需要再投。 */
        STALE,
        /** 同版本不同内容，或该 ID 已被别的归属占用。不能自动覆盖，需要人工确认。 */
        CONFLICT
    }

    public boolean conflict() {
        return status == Status.CONFLICT;
    }
}
