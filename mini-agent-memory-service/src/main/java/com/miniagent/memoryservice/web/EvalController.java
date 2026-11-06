package com.miniagent.memoryservice.web;

import com.miniagent.common.ApiResponse;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.exception.BusinessException;
import com.miniagent.memory.contract.MemoryApi;
import com.miniagent.memory.contract.MemoryApi.EvalJob;
import com.miniagent.memory.contract.MemoryApi.EvalLeaseRequest;
import com.miniagent.memory.contract.MemoryApi.EvalResult;
import com.miniagent.memoryservice.evolution.EvalCoordinator;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 评估 worker（服务令牌 role=eval）领取与回交评估作业。没有可领作业时 data 为 null。 */
@RestController
public class EvalController {
    private final EvalCoordinator coordinator;

    public EvalController(EvalCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @PostMapping(MemoryApi.EVAL_LEASE)
    public ApiResponse<EvalJob> lease(@RequestBody EvalLeaseRequest request) {
        if (request == null || request.workerId() == null || request.workerId().isBlank()) {
            throw new BusinessException(ErrorCode.MEMORY_REQUEST_INVALID, "缺少 workerId");
        }
        return ApiResponse.ok(coordinator.lease(request.workerId()).orElse(null));
    }

    @PostMapping(MemoryApi.EVAL_JOBS + "/{id:\\d+}:complete")
    public ApiResponse<Void> complete(@PathVariable long id, @RequestBody EvalResult result) {
        if (result == null || result.leaseToken() == null || result.verdicts() == null) {
            throw new BusinessException(ErrorCode.MEMORY_REQUEST_INVALID, "缺少租约或判定");
        }
        coordinator.complete(id, result);
        return ApiResponse.ok();
    }
}
