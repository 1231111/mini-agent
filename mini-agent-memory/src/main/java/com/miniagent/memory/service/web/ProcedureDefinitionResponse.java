package com.miniagent.memory.service.web;

public record ProcedureDefinitionResponse(Status status, Integer currentVersion) {
    public enum Status {
        CREATED,
        UPDATED,
        UNCHANGED,
        /** 投递的版本比服务端旧：乱序或重试的正常结果，已收敛，调用方无需重试。 */
        STALE,
        /** 同版本不同内容，或该 ID 已被别的归属占用；需要人工确认，不能自动覆盖。 */
        CONFLICT
    }
}
