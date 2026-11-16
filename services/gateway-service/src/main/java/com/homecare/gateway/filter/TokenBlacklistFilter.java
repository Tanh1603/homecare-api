package com.homecare.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.security.SupabaseJwtConverterSupport;
import com.homecare.gateway.config.GatewayErrorHandlers;

import reactor.core.publisher.Mono;

@Component
public class TokenBlacklistFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklistFilter.class);
    public static final String BLACKLIST_SESSION_KEY_PREFIX = "auth:blacklist:session:";

    private final ReactiveStringRedisTemplate redisTemplate;
    private final GatewayErrorHandlers errorHandlers;

    public TokenBlacklistFilter(ReactiveStringRedisTemplate redisTemplate, GatewayErrorHandlers errorHandlers) {
        this.redisTemplate = redisTemplate;
        this.errorHandlers = errorHandlers;
    }

    @Override
    public int getOrder() {
        return -100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .filter(securityContext -> securityContext.getAuthentication() instanceof JwtAuthenticationToken)
                .map(securityContext -> (JwtAuthenticationToken) securityContext.getAuthentication())
                .map(java.util.Optional::of)
                .defaultIfEmpty(java.util.Optional.empty())
                .flatMap(optionalAuth -> {
                    if (optionalAuth.isEmpty()) {
                        return chain.filter(exchange);
                    }
                    Jwt jwt = optionalAuth.get().getToken();
                    if (SupabaseJwtConverterSupport.isUserStatusBlocked(jwt)) {
                        log.warn("Blocked request for suspended/banned user sub={}", jwt.getSubject());
                        return errorHandlers.writeError(exchange, CommonErrorCode.ACCESS_DENIED);
                    }
                    String sid = jwt.getClaimAsString("session_id");

                    if (sid == null || sid.isBlank()) {
                        return chain.filter(exchange);
                    }

                    String key = BLACKLIST_SESSION_KEY_PREFIX + sid;

                    return redisTemplate.hasKey(key)
                            .onErrorResume(exception -> {
                                log.warn("Redis session blacklist lookup failed (fail-open): {}", exception.getMessage());
                                return Mono.just(false);
                            })
                            .flatMap(isBlacklisted -> {
                                if (Boolean.TRUE.equals(isBlacklisted)) {
                                    log.warn("Blocked request with blacklisted session key={}", key);
                                    return errorHandlers.writeError(exchange, CommonErrorCode.TOKEN_REVOKED);
                                }
                                return chain.filter(exchange);
                            });
                });
    }
}
