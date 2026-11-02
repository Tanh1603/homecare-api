package com.homecare.gateway;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.web.servlet.function.RequestPredicates.*;

@Configuration(proxyBeanMethods = false)
public class GatewayRoutesConfiguration {
    @Bean
    RouterFunction<ServerResponse> routes(@Value("${homecare.gateway.identity-url}") URI identity,
            @Value("${homecare.gateway.provider-url}") URI provider,
            @Value("${homecare.gateway.commerce-url}") URI commerce) {
        for (URI target : java.util.List.of(identity, provider, commerce)) {
            if (!"https".equals(target.getScheme()) || target.getHost() == null || target.getUserInfo() != null
                    || target.getQuery() != null || target.getFragment() != null
                    || !(target.getPath().isEmpty() || target.getPath().equals("/")))
                throw new IllegalArgumentException("Gateway targets must be HTTPS service origins");
        }
        return route("identity-self")
                .route(GET("/api/v1/users/me").or(PATCH("/api/v1/users/me")).or(POST("/api/v1/users/me/bootstrap")), http())
                .before(uri(identity)).build()
                .and(route("provider-discovery")
                        .route(GET("/api/v1/services").or(GET("/api/v1/providers")).or(GET("/api/v1/providers/{providerId}"))
                                .or(GET("/api/v1/technicians")), http()).before(uri(provider)).build())
                .and(route("product-discovery").route(GET("/api/v1/products").or(GET("/api/v1/products/{productId}")), http())
                        .before(uri(commerce)).build());
    }
}
