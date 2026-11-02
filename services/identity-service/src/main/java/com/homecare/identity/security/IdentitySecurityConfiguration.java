package com.homecare.identity.security;

import com.homecare.identity.infrastructure.ApiErrors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter;
import org.springframework.http.HttpMethod;

/** Phase 3: verified Firebase actor over an allowlisted mTLS workload; all other routes denied. */
@Configuration(proxyBeanMethods = false)
public class IdentitySecurityConfiguration {
    @Bean
    UserDetailsService noLocalAccounts() {
        return username -> { throw new UsernameNotFoundException("Local authentication is disabled"); };
    }

    @Bean
    SecurityFilterChain identitySecurityFilterChain(HttpSecurity http, ObjectMapper json, FirebaseVerifier verifier,
            @Value("${homecare.security.allowed-workloads}") Set<String> callers) throws Exception {
        return http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(c -> c.disable())
                .addFilterBefore(new IdentityAuthenticationFilter(verifier, json, callers), AbstractPreAuthenticatedProcessingFilter.class)
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/api/v1/users/me").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/users/me").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/users/me/bootstrap", "/internal/v1/principals/resolve").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, failure) -> {
                            response.setStatus(401);
                            response.setContentType("application/json");
                            json.writeValue(response.getOutputStream(), ApiErrors.body("AUTHENTICATION_REQUIRED",
                                    "Can xac thuc.", List.of(), request));
                        })
                        .accessDeniedHandler((request, response, failure) -> {
                            response.setStatus(403);
                            response.setContentType("application/json");
                            json.writeValue(response.getOutputStream(), ApiErrors.body("OPERATION_FORBIDDEN",
                                    "Khong co quyen truy cap.", List.of(), request));
                        }))
                .build();
    }
}
