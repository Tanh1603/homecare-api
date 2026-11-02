package com.homecare.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.authorization.AuthorizationDecision;

@Configuration(proxyBeanMethods = false)
public class GatewaySecurityConfiguration {
    @Bean UserDetailsService noLocalAccounts() { return name -> { throw new UsernameNotFoundException("No local accounts"); }; }
    @Bean SecurityFilterChain gatewayChain(HttpSecurity http) throws Exception {
        // Boundary filter has already verified tokens and exact methods/paths before this chain.
        return http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().access((authentication, context) -> new AuthorizationDecision(
                        GatewayRoutePolicy.guest(context.getRequest().getMethod(), context.getRequest().getRequestURI())
                                || GatewayRoutePolicy.self(context.getRequest().getMethod(), context.getRequest().getRequestURI()))))
                .build();
    }
}
