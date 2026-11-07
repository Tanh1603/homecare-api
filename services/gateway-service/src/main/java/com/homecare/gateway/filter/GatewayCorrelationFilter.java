package com.homecare.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.homecare.common.web.CorrelationIdSupport;
import com.homecare.gateway.config.GatewayErrorHandlers;

import reactor.core.publisher.Mono;

@Component
public class GatewayCorrelationFilter implements GlobalFilter, WebFilter, Ordered {

    public static final String CORRELATION_ID_HEADER = CorrelationIdSupport.HEADER_NAME;

    private final GatewayErrorHandlers errorHandlers;

    public GatewayCorrelationFilter(GatewayErrorHandlers errorHandlers) {
        this.errorHandlers = errorHandlers;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerWebExchange mutated = ensureCorrelationId(exchange);
        return chain.filter(mutated);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerWebExchange mutated = ensureCorrelationId(exchange);
        String rawPath = mutated.getRequest().getURI().getRawPath();
        if (rawPath != null && (rawPath.equals("/internal") || rawPath.startsWith("/internal/"))) {
            return errorHandlers.handle(mutated, new AccessDeniedException("Internal endpoints are not exposed through Gateway"));
        }
        return chain.filter(mutated);
    }

    public static ServerWebExchange ensureCorrelationId(ServerWebExchange exchange) {
        String suppliedId = exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER);
        String resolvedId = CorrelationIdSupport.resolveOrGenerate(suppliedId);
        exchange.getResponse().getHeaders().set(CORRELATION_ID_HEADER, resolvedId);
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .headers(headers -> headers.set(CORRELATION_ID_HEADER, resolvedId))
                .build();
        return exchange.mutate().request(mutatedRequest).build();
    }
}
