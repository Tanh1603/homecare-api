package com.homecare.provider.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.homecare.common.config.HomecareSecurityProperties;
import com.homecare.common.security.SupabaseJwtConverterSupport;
import com.homecare.common.web.servlet.BaseApiExceptionHandler;
import com.homecare.common.web.servlet.GatewayOriginFilter;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.common.web.servlet.ServletCorrelationIdFilter;
import com.homecare.grpc.interceptor.WorkloadAuthServerInterceptor;

import io.grpc.ServerInterceptor;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;

@Configuration
@EnableMethodSecurity
@EnableScheduling
public class ProviderSecurityAndGrpcConfig {

    @Bean
    public ServletCorrelationIdFilter servletCorrelationIdFilter() {
        return new ServletCorrelationIdFilter();
    }

    @Bean
    public ServletApiResponseFactory servletApiResponseFactory() {
        return new ServletApiResponseFactory();
    }

    @Bean
    public GatewayOriginFilter gatewayOriginFilter(HomecareSecurityProperties securityProperties) {
        return new GatewayOriginFilter(
                securityProperties.getSecurity().isEnforceGateway(),
                securityProperties.getSecurity().getInternalGatewaySecret(),
                securityProperties.getInternal().getWorkloadToken());
    }

    @Bean
    @GrpcGlobalServerInterceptor
    public ServerInterceptor internalWorkloadGrpcInterceptor(HomecareSecurityProperties securityProperties) {
        return new WorkloadAuthServerInterceptor(securityProperties.getInternal().getWorkloadToken());
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ServletCorrelationIdFilter correlationIdFilter,
            GatewayOriginFilter gatewayOriginFilter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/**",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/technicians/me",
                                "/api/v1/technicians/me/**",
                                "/api/v1/technicians/*/schedules")
                        .authenticated()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/services", "/api/v1/services/**",
                                "/api/v1/providers", "/api/v1/providers/**",
                                "/api/v1/technicians", "/api/v1/technicians/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(
                                SupabaseJwtConverterSupport.createJwtAuthenticationConverter())))
                .addFilterBefore(correlationIdFilter, SecurityContextHolderFilter.class)
                .addFilterBefore(gatewayOriginFilter, SecurityContextHolderFilter.class);
        return http.build();
    }

    @RestControllerAdvice
    public static class ProviderExceptionHandler extends BaseApiExceptionHandler {
        public ProviderExceptionHandler(ServletApiResponseFactory responseFactory) {
            super(responseFactory);
        }
    }
}
