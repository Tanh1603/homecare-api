package com.homecare.common.web.servlet;

import java.io.IOException;

import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import com.homecare.common.web.CorrelationIdSupport;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class ServletCorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = CorrelationIdSupport.HEADER_NAME;
    public static final String REQUEST_ATTRIBUTE = CorrelationIdSupport.REQUEST_ATTRIBUTE;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String suppliedId = request.getHeader(HEADER_NAME);
        String correlationId = CorrelationIdSupport.resolveOrGenerate(suppliedId);

        request.setAttribute(REQUEST_ATTRIBUTE, correlationId);
        response.setHeader(HEADER_NAME, correlationId);
        MDC.put(HEADER_NAME, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(HEADER_NAME);
        }
    }
}
