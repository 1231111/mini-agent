package com.miniagent.web;

import com.miniagent.common.ApiResponse;
import com.miniagent.config.security.CurrentUser;
import com.miniagent.config.service.UserTokenQuotaService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Current user's authoritative daily token quota; identity always comes from the signed session. */
@RestController
@RequestMapping(value = "/api/me", produces = MediaType.APPLICATION_JSON_VALUE)
public class UserTokenQuotaController {
    @Autowired private CurrentUser currentUser;
    @Autowired private UserTokenQuotaService quota;

    @GetMapping("/token-quota")
    public ApiResponse<?> tokenQuota() {
        return ApiResponse.ok(quota.current(currentUser.userId(), currentUser.tenantId()));
    }
}
