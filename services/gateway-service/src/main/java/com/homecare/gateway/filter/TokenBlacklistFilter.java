package com.homecare.gateway.filter;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.CorrelationIdSupport;

import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

@Component
public class TokenBlacklistFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklistFilter.class);
    public static final String BLACKLIST_SESSION_KEY_PREFIX = "auth:blacklist:session:";

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public TokenBlacklistFilter(ReactiveStringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
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
                    String sid = jwt.getClaimAsString("sid");
                    if (sid == null || sid.isBlank()) {
                        sid = jwt.getClaimAsString("session_state");
                    }

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
                                    return onTokenRevoked(exchange);
                                }
                                return chain.filter(exchange);
                            });
                });
    }

    private Mono<Void> onTokenRevoked(ServerWebExchange exchange) {
        CommonErrorCode errorCode = CommonErrorCode.TOKEN_REVOKED;
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(errorCode.status());
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String suppliedId = exchange.getRequest().getHeaders().getFirst(CorrelationIdSupport.HEADER_NAME);
        String correlationId = CorrelationIdSupport.resolveOrGenerate(suppliedId);
        response.getHeaders().set(CorrelationIdSupport.HEADER_NAME, correlationId);

        ApiResponse<Void> errorResponse = ApiResponse.error(
                errorCode.status().value(),
                errorCode.name(),
                errorCode.defaultMessage(),
                correlationId);

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(errorResponse);
        } catch (Exception e) {
            bytes = "{\"success\":false,\"code\":401,\"errorCode\":\"TOKEN_REVOKED\",\"message\":\"Token has been revoked\",\"data\":null}".getBytes(StandardCharsets.UTF_8);
        }

        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
