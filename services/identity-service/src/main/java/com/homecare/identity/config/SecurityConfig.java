package com.homecare.identity.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

import com.homecare.common.security.KeycloakJwtConverterSupport;
import com.homecare.identity.web.ApiAccessDeniedHandler;
import com.homecare.identity.web.ApiAuthenticationEntryPoint;
import com.homecare.identity.web.CorrelationIdFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            ApiAuthenticationEntryPoint authenticationEntryPoint,
            ApiAccessDeniedHandler accessDeniedHandler,
            CorrelationIdFilter correlationIdFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/v3/api-docs/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/api/v1/auth/**")
                        .permitAll().anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(o -> o
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(j -> j.jwtAuthenticationConverter(jwtConverter())))
                .addFilterBefore(correlationIdFilter, SecurityContextHolderFilter.class);
        return http.build();
    }

    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration(
            CorrelationIdFilter correlationIdFilter) {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(correlationIdFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    JwtAuthenticationConverter jwtConverter() {
        return KeycloakJwtConverterSupport.createJwtAuthenticationConverter();
    }
}