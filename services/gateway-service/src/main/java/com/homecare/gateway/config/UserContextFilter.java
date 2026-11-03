package com.homecare.gateway.config;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class UserContextFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(ctx -> ctx.getAuthentication())
                .filter(a -> a instanceof JwtAuthenticationToken)
                .cast(JwtAuthenticationToken.class)
                .map(t -> exchange.mutate().request(r -> r.headers(h -> {
                    h.remove("X-User-Id");
                    h.set("X-User-Id", t.getToken().getSubject());
                    String u = t.getToken().getClaimAsString("preferred_username");
                    if (u != null) h.set("X-Username", u);
                })).build())
                .defaultIfEmpty(exchange.mutate().request(r -> r.headers(h -> {
                    h.remove("X-User-Id");
                    h.remove("X-Username");
                })).build())
                .flatMap(chain::filter);
    }

    @Override
    public int getOrder() { return -1; }
}
