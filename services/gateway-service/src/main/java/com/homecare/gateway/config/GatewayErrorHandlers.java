package com.homecare.gateway.config;

import java.nio.charset.StandardCharsets;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.homecare.common.error.ApiErrorCodeContract;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.CorrelationIdSupport;
import com.homecare.gateway.filter.GatewayCorrelationFilter;

import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

@Component
public class GatewayErrorHandlers implements ServerAuthenticationEntryPoint, ServerAccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public GatewayErrorHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        return writeError(exchange, CommonErrorCode.UNAUTHENTICATED);
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        return writeError(exchange, CommonErrorCode.ACCESS_DENIED);
    }

    public Mono<Void> writeError(ServerWebExchange exchange, ApiErrorCodeContract errorCode) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(errorCode.status());
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String correlationId = exchange.getRequest().getHeaders()
                .getFirst(GatewayCorrelationFilter.CORRELATION_ID_HEADER);
        if (!CorrelationIdSupport.isValid(correlationId)) {
            correlationId = response.getHeaders().getFirst(GatewayCorrelationFilter.CORRELATION_ID_HEADER);
        }
        correlationId = CorrelationIdSupport.resolveOrGenerate(correlationId);
        response.getHeaders().set(GatewayCorrelationFilter.CORRELATION_ID_HEADER, correlationId);

        ApiResponse<Void> body = ApiResponse.error(
                errorCode.status().value(),
                errorCode.name(),
                errorCode.defaultMessage(),
                correlationId);

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(body);
        } catch (Exception e) {
            String fallback = String.format(
                    "{\"success\":false,\"code\":%d,\"errorCode\":\"%s\",\"message\":\"%s\",\"data\":null,\"traceId\":\"%s\"}",
                    errorCode.status().value(), errorCode.name(), errorCode.defaultMessage(), correlationId);
            bytes = fallback.getBytes(StandardCharsets.UTF_8);
        }

        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
