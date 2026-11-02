package com.homecare.identity.infrastructure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Correlation-Id";
    public static final String ATTRIBUTE = CorrelationFilter.class.getName();
    private static final Logger LOG = LoggerFactory.getLogger(CorrelationFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
        var headers = Collections.list(request.getHeaders(HEADER));
        String id = headers.size() == 1 && headers.getFirst().matches("[A-Za-z0-9._-]{1,64}")
                ? headers.getFirst() : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id);
        String previous = MDC.get("correlationId");
        MDC.put("correlationId", id);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            // Allowlisted metadata only: no URL/query, headers, body, principal or exception text.
            LOG.info("http_completed correlationId={} status={} durationMs={}",
                    id, response.getStatus(), (System.nanoTime() - start) / 1_000_000);
            if (previous == null) MDC.remove("correlationId");
            else MDC.put("correlationId", previous);
        }
    }
}
