package com.homecare.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(
            ServerHttpSecurity http,
            GatewayErrorHandlers errorHandlers) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/internal/**").denyAll()
                        .pathMatchers(
                                HttpMethod.GET,
                                "/api/v1/services",
                                "/api/v1/services/**",
                                "/api/v1/providers",
                                "/api/v1/providers/**",
                                "/api/v1/technicians",
                                "/api/v1/technicians/**",
                                "/api/v1/products",
                                "/api/v1/products/**",
                                "/api/v1/payments/vnpay/ipn")
                        .permitAll()
                        .pathMatchers(
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                                "/webjars/**",
                                "/*-service/v3/api-docs/**",
                                "/household-service/v3/api-docs/**",
                                "/provider-service/v3/api-docs/**",
                                "/booking-service/v3/api-docs/**",
                                "/commerce-service/v3/api-docs/**",
                                "/review-service/v3/api-docs/**",
                                "/notification-service/v3/api-docs/**")
                        .permitAll()
                        .anyExchange().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(errorHandlers)
                        .accessDeniedHandler(errorHandlers))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(errorHandlers)
                        .accessDeniedHandler(errorHandlers)
                        .jwt(jwt -> {
                        }));

        return http.build();
    }
}