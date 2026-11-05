package com.homecare.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

class TokenBlacklistFilterTest {

    private ReactiveStringRedisTemplate redisTemplate;
    private ObjectMapper objectMapper;
    private GatewayFilterChain chain;
    private TokenBlacklistFilter filter;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        objectMapper = new ObjectMapper();
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        filter = new TokenBlacklistFilter(redisTemplate, objectMapper);
    }

    @Test
    void blocksRequestWhenSessionIsBlacklisted() {
        Jwt jwt = new Jwt(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("alg", "none"),
                Map.of("sub", "user-123", "sid", "blacklisted-session-456"));
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt, Collections.emptyList());
        SecurityContext securityContext = new SecurityContextImpl(auth);

        when(redisTemplate.hasKey(eq(TokenBlacklistFilter.BLACKLIST_SESSION_KEY_PREFIX + "blacklisted-session-456")))
                .thenReturn(Mono.just(true));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/users/me")
                .header("X-Correlation-Id", "test-trace-id")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(securityContext)))
                .block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String responseBody = exchange.getResponse().getBodyAsString().block();
        assertThat(responseBody).contains("TOKEN_REVOKED");
        assertThat(responseBody).contains("Token has been revoked");
        assertThat(responseBody).contains("test-trace-id");

        verify(chain, never()).filter(any());
    }

    @Test
    void allowsRequestWhenSessionIsNotBlacklisted() {
        Jwt jwt = new Jwt(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("alg", "none"),
                Map.of("sub", "user-123", "sid", "valid-session-789"));
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt, Collections.emptyList());
        SecurityContext securityContext = new SecurityContextImpl(auth);

        when(redisTemplate.hasKey(eq(TokenBlacklistFilter.BLACKLIST_SESSION_KEY_PREFIX + "valid-session-789")))
                .thenReturn(Mono.just(false));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/users/me").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(securityContext)))
                .block();

        verify(chain).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void allowsRequestWhenNoSecurityContextExists() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/auth/login").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
        verify(redisTemplate, never()).hasKey(any());
    }

    @Test
    void failsOpenWhenRedisThrowsException() {
        Jwt jwt = new Jwt(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("alg", "none"),
                Map.of("sub", "user-123", "sid", "some-session"));
        JwtAuthenticationToken auth = new JwtAuthenticationToken(jwt, Collections.emptyList());
        SecurityContext securityContext = new SecurityContextImpl(auth);

        when(redisTemplate.hasKey(eq(TokenBlacklistFilter.BLACKLIST_SESSION_KEY_PREFIX + "some-session")))
                .thenReturn(Mono.error(new RuntimeException("Redis connection refused")));

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/users/me").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain)
                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(securityContext)))
                .block();

        verify(chain).filter(exchange);
    }
}
