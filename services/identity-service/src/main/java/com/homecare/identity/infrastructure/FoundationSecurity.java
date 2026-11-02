package com.homecare.identity.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import tools.jackson.databind.ObjectMapper;
import java.util.List;

/** Fail closed until Phase 3 installs the approved Firebase/mTLS boundary. */
@Configuration(proxyBeanMethods = false)
public class FoundationSecurity {
    @Bean
    UserDetailsService noLocalAccounts() {
        return username -> { throw new UsernameNotFoundException("Local authentication is disabled"); };
    }

    @Bean
    SecurityFilterChain foundationSecurityFilterChain(HttpSecurity http, ObjectMapper json) throws Exception {
        return http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().denyAll())
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
