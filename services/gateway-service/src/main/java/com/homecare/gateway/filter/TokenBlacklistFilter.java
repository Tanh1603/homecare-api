package com.homecare.gateway.filter;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.security.SupabaseJwtConverterSupport;
import com.homecare.gateway.config.GatewayAuthProperties;
import com.homecare.gateway.config.GatewayErrorHandlers;

import reactor.core.publisher.Mono;

@Component
public class TokenBlacklistFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklistFilter.class);
    public static final String BLACKLIST_SESSION_KEY_PREFIX = "auth:blacklist:session:";
    public static final String BLACKLIST_USER_KEY_PREFIX = "auth:blacklist:user:";

    private final ReactiveStringRedisTemplate redisTemplate;
    private final GatewayErrorHandlers errorHandlers;
    private final boolean blacklistFailClosed;

    @Autowired
    public TokenBlacklistFilter(
            ReactiveStringRedisTemplate redisTemplate,
            GatewayErrorHandlers errorHandlers,
            GatewayAuthProperties authProperties) {
        this(redisTemplate, errorHandlers, authProperties != null && authProperties.isBlacklistFailClosed());
    }

    public TokenBlacklistFilter(
            ReactiveStringRedisTemplate redisTemplate,
            GatewayErrorHandlers errorHandlers,
            boolean blacklistFailClosed) {
        this.redisTemplate = redisTemplate;
        this.errorHandlers = errorHandlers;
        this.blacklistFailClosed = blacklistFailClosed;
    }

    public TokenBlacklistFilter(ReactiveStringRedisTemplate redisTemplate, GatewayErrorHandlers errorHandlers) {
        this(redisTemplate, errorHandlers, false);
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
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(optionalAuth -> {
                    if (optionalAuth.isEmpty()) {
                        return chain.filter(exchange);
                    }
                    Jwt jwt = optionalAuth.get().getToken();
                    if (SupabaseJwtConverterSupport.isUserStatusBlocked(jwt)) {
                        log.warn("Blocked request for suspended/banned user sub={}", jwt.getSubject());
                        return errorHandlers.writeError(exchange, CommonErrorCode.ACCESS_DENIED);
                    }
                    String sub = jwt.getSubject();
                    boolean isMutation = exchange.getRequest().getMethod() != HttpMethod.GET
                            && exchange.getRequest().getMethod() != HttpMethod.HEAD;
                    Mono<Boolean> userBlacklisted = (sub == null || sub.isBlank())
                            ? Mono.just(false)
                            : hasBlacklistedKey(BLACKLIST_USER_KEY_PREFIX + sub, isMutation);

                    return userBlacklisted.flatMap(isUserBlocked -> {
                        if (Boolean.TRUE.equals(isUserBlocked)) {
                            log.warn("Blocked request for Redis-blacklisted user sub={}", sub);
                            return errorHandlers.writeError(exchange, CommonErrorCode.TOKEN_REVOKED);
                        }
                        String sid = jwt.getClaimAsString("session_id");
                        if (sid == null || sid.isBlank()) {
                            return chain.filter(exchange);
                        }
                        String key = BLACKLIST_SESSION_KEY_PREFIX + sid;
                        return hasBlacklistedKey(key, isMutation).flatMap(isBlacklisted -> {
                            if (Boolean.TRUE.equals(isBlacklisted)) {
                                log.warn("Blocked request with blacklisted session key={}", key);
                                return errorHandlers.writeError(exchange, CommonErrorCode.TOKEN_REVOKED);
                            }
                            return chain.filter(exchange);
                        });
                    });
                });
    }

    private Mono<Boolean> hasBlacklistedKey(String key, boolean isMutation) {
        Mono<Boolean> lookup = redisTemplate.hasKey(key);
        if (lookup == null) {
            return Mono.just(false);
        }
        return lookup
                .defaultIfEmpty(false)
                .timeout(Duration.ofMillis(500))
                .onErrorResume(exception -> {
                    if (blacklistFailClosed && isMutation) {
                        log.warn("Redis blacklist lookup failed (fail-closed) for key={}: {}", key, exception.getMessage());
                        return Mono.just(true);
                    }
                    log.warn("Redis blacklist lookup failed (fail-open) for key={}: {}", key, exception.getMessage());
                    return Mono.just(false);
                });
    }
}
