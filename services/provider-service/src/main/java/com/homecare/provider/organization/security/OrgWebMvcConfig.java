package com.homecare.provider.organization.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import lombok.RequiredArgsConstructor;

@Configuration
@RequiredArgsConstructor
public class OrgWebMvcConfig implements WebMvcConfigurer {

    private final OrgContextInterceptor orgContextInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(orgContextInterceptor).addPathPatterns("/api/**");
    }
}
