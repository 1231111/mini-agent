package com.miniagent.memoryservice.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.common.ApiResponse;
import com.miniagent.common.ErrorCode;
import com.miniagent.memory.contract.MemoryAccessToken;
import com.miniagent.memory.contract.MemoryApi;
import com.miniagent.memory.contract.ServiceToken;
import com.miniagent.memoryservice.config.AccountIntrospectionClient;
import com.miniagent.memoryservice.config.MemoryServiceProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.PublicKey;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ServiceTokenFilter extends OncePerRequestFilter {
    public static final String PRINCIPAL_ATTR = "com.miniagent.memoryservice.principal";
    private static final String INTERNAL_PREFIX = "/internal/";
    private static final Map<String, String> ROLE_BY_PREFIX = Map.of(
            MemoryApi.MEMORY + "/", ServiceToken.ROLE_APP,
            MemoryApi.V2 + "/eval/", ServiceToken.ROLE_EVAL,
            MemoryApi.ADMIN + "/", ServiceToken.ROLE_ADMIN);

    private final Map<String, List<PublicKey>> keys = new HashMap<>();
    private final Map<String, List<String>> roles = new HashMap<>();
    private final ObjectMapper objectMapper;
    private final AccountIntrospectionClient account;
    private final MemoryServiceProperties properties;
    private final Clock clock;

    public ServiceTokenFilter(MemoryServiceProperties properties, ObjectMapper objectMapper,
                              AccountIntrospectionClient account, Clock clock) {
        this.objectMapper = objectMapper;
        this.account = account;
        this.properties = properties;
        this.clock = clock;
        properties.issuers().forEach((issuer, config) -> {
            keys.put(issuer, config.publicKeys().stream().map(ServiceToken::publicKey).toList());
            roles.put(issuer, config.roles());
        });
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(INTERNAL_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(ServiceToken.HEADER);
        if (header == null || !header.startsWith(ServiceToken.BEARER)) {
            reject(response, HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_NOT_AUTHENTICATED);
            return;
        }
        String token = header.substring(ServiceToken.BEARER.length()).trim();
        String required = requiredRole(request.getRequestURI());
        if (required == null) {
            reject(response, HttpStatus.FORBIDDEN, ErrorCode.AUTH_FORBIDDEN);
            return;
        }

        ServiceToken.Principal principal;
        if (ServiceToken.ROLE_APP.equals(required) && token.startsWith("ma1.")) {
            MemoryAccessToken.Claims claims;
            try {
                claims = account.introspect(token);
            } catch (AccountIntrospectionClient.InvalidTokenException e) {
                reject(response, HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_NOT_AUTHENTICATED);
                return;
            } catch (AccountIntrospectionClient.AccountUnavailableException e) {
                try {
                    claims = MemoryAccessToken.decrypt(objectMapper,
                            MemoryAccessToken.decodeKey(properties.memoryTokenKey()), token, clock.instant());
                } catch (IllegalArgumentException | IllegalStateException invalid) {
                    reject(response, HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_NOT_AUTHENTICATED);
                    return;
                }
            } catch (RuntimeException e) {
                reject(response, HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.AUTH_CLOUD_UNREACHABLE);
                return;
            }
            if (!MemoryAccessToken.AUDIENCE.equals(claims.audience())
                    || !MemoryAccessToken.PURPOSE.equals(claims.purpose())) {
                reject(response, HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_NOT_AUTHENTICATED);
                return;
            }
            principal = new ServiceToken.Principal("account", ServiceToken.ROLE_APP,
                    Long.toString(claims.tenantId()), claims.userId(), null);
        } else {
            try {
                principal = ServiceToken.verify(token, keys);
            } catch (IllegalArgumentException e) {
                reject(response, HttpStatus.UNAUTHORIZED, ErrorCode.AUTH_NOT_AUTHENTICATED);
                return;
            }
            boolean issuerMayGrant = roles.getOrDefault(principal.issuer(), List.of())
                    .contains(principal.role());
            if (!required.equals(principal.role()) || !issuerMayGrant) {
                reject(response, HttpStatus.FORBIDDEN, ErrorCode.AUTH_FORBIDDEN);
                return;
            }
        }
        request.setAttribute(PRINCIPAL_ATTR, principal);
        chain.doFilter(request, response);
    }

    private static String requiredRole(String uri) {
        return ROLE_BY_PREFIX.entrySet().stream()
                .filter(e -> uri.startsWith(e.getKey()))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private void reject(HttpServletResponse response, HttpStatus status, ErrorCode code)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.fail(code));
    }
}
