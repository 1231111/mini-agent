package com.miniagent.config.security;

import com.miniagent.common.ErrorCode;
import com.miniagent.common.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Single source for authenticated user and tenant identity. */
@Component
public class CurrentUser {
    public AuthenticatedUser require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new BusinessException(ErrorCode.AUTH_NOT_AUTHENTICATED);
        }
        return user;
    }

    public Long userId() { return require().userId(); }
    public Long tenantId() { return require().tenantId(); }
    public String tenantKey() { return require().tenantKey(); }
}
