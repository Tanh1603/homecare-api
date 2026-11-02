package com.homecare.notification.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/** Fail closed until Phase 3 installs the approved Firebase/mTLS boundary. */
@Configuration(proxyBeanMethods = false)
public class FoundationSecurity {
    @Bean
    UserDetailsService noLocalAccounts() {
        return username -> { throw new UsernameNotFoundException("Local authentication is disabled"); };
    }

    @Bean
    SecurityFilterChain foundationSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().denyAll())
                .build();
    }
}
