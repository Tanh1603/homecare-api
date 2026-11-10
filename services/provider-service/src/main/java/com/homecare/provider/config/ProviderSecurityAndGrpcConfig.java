package com.homecare.provider.config;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.homecare.common.security.KeycloakJwtConverterSupport;
import com.homecare.common.web.servlet.BaseApiExceptionHandler;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.common.web.servlet.ServletCorrelationIdFilter;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;

@Configuration
@EnableMethodSecurity
public class ProviderSecurityAndGrpcConfig {

    private static final Metadata.Key<String> WORKLOAD_TOKEN_KEY =
            Metadata.Key.of("X-Internal-Workload-Token", Metadata.ASCII_STRING_MARSHALLER);

    @Value("${homecare.internal.workload-token}")
    private String expectedWorkloadToken;

    @Bean
    public ServletCorrelationIdFilter servletCorrelationIdFilter() {
        return new ServletCorrelationIdFilter();
    }

    @Bean
    public ServletApiResponseFactory servletApiResponseFactory() {
        return new ServletApiResponseFactory();
    }

    @Bean
    @GrpcGlobalServerInterceptor
    public ServerInterceptor internalWorkloadGrpcInterceptor() {
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call,
                    Metadata headers,
                    ServerCallHandler<ReqT, RespT> next) {
                String token = headers.get(WORKLOAD_TOKEN_KEY);
                if (token == null || !expectedWorkloadToken.equals(token)) {
                    call.close(Status.UNAUTHENTICATED.withDescription("Invalid internal workload token"), new Metadata());
                    return new ServerCall.Listener<>() {};
                }
                return next.startCall(call, headers);
            }
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ServletCorrelationIdFilter correlationIdFilter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/**",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/technicians/me/**").authenticated()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/services", "/api/v1/services/**",
                                "/api/v1/providers", "/api/v1/providers/**",
                                "/api/v1/technicians", "/api/v1/technicians/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(
                                KeycloakJwtConverterSupport.createJwtAuthenticationConverter())))
                .addFilterBefore(correlationIdFilter, SecurityContextHolderFilter.class);
        return http.build();
    }

    @RestControllerAdvice
    public static class ProviderExceptionHandler extends BaseApiExceptionHandler {
        public ProviderExceptionHandler(ServletApiResponseFactory responseFactory) {
            super(responseFactory);
        }
    }
}
