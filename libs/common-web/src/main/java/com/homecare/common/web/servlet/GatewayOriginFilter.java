package com.homecare.common.web.servlet;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class GatewayOriginFilter extends OncePerRequestFilter {

    public static final String GATEWAY_SECRET_HEADER = "X-Internal-Gateway-Secret";
    public static final String WORKLOAD_TOKEN_HEADER = "X-Internal-Workload-Token";

    private final boolean enforceGateway;
    private final String expectedGatewaySecret;
    private final String expectedWorkloadToken;

    public GatewayOriginFilter(
            boolean enforceGateway,
            String expectedGatewaySecret,
            String expectedWorkloadToken) {
        this.enforceGateway = enforceGateway;
        this.expectedGatewaySecret = expectedGatewaySecret != null ? expectedGatewaySecret.trim() : "";
        this.expectedWorkloadToken = expectedWorkloadToken != null ? expectedWorkloadToken.trim() : "";
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        if (!enforceGateway) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        // Allow health checks and internal actuator endpoints
        if (path.startsWith("/actuator/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String gatewaySecret = request.getHeader(GATEWAY_SECRET_HEADER);
        String workloadToken = request.getHeader(WORKLOAD_TOKEN_HEADER);

        boolean isGatewayValid = !expectedGatewaySecret.isBlank()
                && expectedGatewaySecret.equals(gatewaySecret != null ? gatewaySecret.trim() : "");
        boolean isWorkloadValid = !expectedWorkloadToken.isBlank()
                && expectedWorkloadToken.equals(workloadToken != null ? workloadToken.trim() : "");

        if (isGatewayValid || isWorkloadValid) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        String json = "{\"success\":false,\"data\":null,\"error\":{\"code\":\"DIRECT_ACCESS_FORBIDDEN\",\"message\":\"Direct access to downstream service is forbidden. Requests must go through API Gateway.\"},\"meta\":null}";
        response.getWriter().write(json);
    }
}
