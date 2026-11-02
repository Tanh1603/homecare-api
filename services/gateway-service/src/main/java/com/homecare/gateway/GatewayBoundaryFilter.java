package com.homecare.gateway;

import com.homecare.gateway.security.FirebaseVerifier;
import com.homecare.gateway.security.SecurityFailure;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import static com.homecare.gateway.GatewayRoutePolicy.guest;
import static com.homecare.gateway.GatewayRoutePolicy.self;

@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayBoundaryFilter extends OncePerRequestFilter {
    public static final String CORRELATION = "X-Correlation-Id";
    private final FirebaseVerifier verifier;
    private final ObjectMapper json;
    private final int maxBody;
    private final Set<String> origins;
    public GatewayBoundaryFilter(FirebaseVerifier verifier, ObjectMapper json,
            @Value("${homecare.gateway.max-body-bytes}") int maxBody,
            @Value("${homecare.gateway.cors-origins}") Set<String> origins) {
        this.verifier = verifier; this.json = json; this.maxBody = maxBody; this.origins = origins;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var ids = Collections.list(request.getHeaders(CORRELATION));
        String id = ids.size() == 1 && ids.getFirst().matches("[A-Za-z0-9._-]{1,64}") ? ids.getFirst() : UUID.randomUUID().toString();
        response.setHeader(CORRELATION, id); request.setAttribute(CORRELATION, id); MDC.put("correlationId", id);
        try {
            String path = request.getRequestURI(), method = request.getMethod();
            String origin = request.getHeader("Origin");
            if (origin != null) {
                if (!origins.contains(origin)) throw new SecurityFailure(403, "ORIGIN_FORBIDDEN");
                response.setHeader("Access-Control-Allow-Origin", origin); response.addHeader("Vary", "Origin");
                response.setHeader("Access-Control-Expose-Headers", "ETag,Location,X-Correlation-Id,Retry-After");
            }
            if (method.equals("OPTIONS") && origin != null) {
                String requested = request.getHeader("Access-Control-Request-Method");
                if (!guest(requested == null ? "" : requested, path) && !self(requested == null ? "" : requested, path))
                    throw new SecurityFailure(403, "OPERATION_FORBIDDEN");
                response.setHeader("Access-Control-Allow-Methods", requested);
                response.setHeader("Access-Control-Allow-Headers", "Authorization,Content-Type,If-Match,Idempotency-Key,X-Correlation-Id");
                response.setStatus(204); return;
            }
            if (!guest(method, path) && !self(method, path)) throw new SecurityFailure(404, "ROUTE_NOT_FOUND");
            if (!guest(method, path) || request.getHeader("Authorization") != null) verifier.verify(request);
            if (request.getContentLengthLong() > maxBody) throw new SecurityFailure(413, "REQUEST_TOO_LARGE");
            byte[] body = request.getInputStream().readNBytes(maxBody + 1);
            if (body.length > maxBody) throw new SecurityFailure(413, "REQUEST_TOO_LARGE");
            chain.doFilter(new SanitizedRequest(request, id, body), response);
        } catch (SecurityFailure e) { error(response, id, e.status, e.code); }
        catch (Exception e) {
            if (response.isCommitted()) throw e;
            Throwable cause = e;
            boolean timeout = false, unavailable = false, transportFailure = false;
            while (cause != null) {
                timeout |= cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException;
                unavailable |= cause instanceof java.net.ConnectException || cause instanceof java.net.UnknownHostException;
                transportFailure |= cause instanceof IOException;
                cause = cause.getCause();
            }
            int status = timeout ? 504 : unavailable ? 503 : transportFailure ? 502 : 500;
            String code = timeout ? "DOWNSTREAM_TIMEOUT" : unavailable ? "IDENTITY_UNAVAILABLE"
                    : transportFailure ? "DOWNSTREAM_INVALID_RESPONSE" : "INTERNAL_ERROR";
            error(response, id, status, code);
        } finally { MDC.remove("correlationId"); }
    }
    private void error(HttpServletResponse response, String id, int status, String code) throws IOException {
        response.setStatus(status); response.setContentType("application/json");
        json.writeValue(response.getOutputStream(), Map.of("error", Map.of("code", code, "message", "Yeu cau khong the thuc hien.", "fieldErrors", List.of(), "traceId", id)));
    }
    static class SanitizedRequest extends HttpServletRequestWrapper {
        private final String correlation;
        private final byte[] body;
        SanitizedRequest(HttpServletRequest request, String correlation, byte[] body) { super(request); this.correlation = correlation; this.body = body; }
        private boolean forbidden(String name) {
            String key = name.toLowerCase(Locale.ROOT);
            return key.startsWith("x-user") || key.startsWith("x-role") || key.startsWith("x-caller")
                    || key.startsWith("x-forwarded") || key.equals("forwarded") || key.equals("cookie") || key.startsWith("x-ssl")
                    || key.equals("x-client-cert") || key.equals("x-http-method-override");
        }
        @Override public String getHeader(String name) { return name.equalsIgnoreCase(CORRELATION) ? correlation : forbidden(name) ? null : super.getHeader(name); }
        @Override public Enumeration<String> getHeaders(String name) {
            return name.equalsIgnoreCase(CORRELATION) ? Collections.enumeration(List.of(correlation))
                    : forbidden(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }
        @Override public Enumeration<String> getHeaderNames() {
            var names = new ArrayList<>(Collections.list(super.getHeaderNames()).stream().filter(n -> !forbidden(n) && !n.equalsIgnoreCase(CORRELATION)).toList());
            names.add(CORRELATION); return Collections.enumeration(names);
        }
        @Override public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                public int read() { return input.read(); }
                public boolean isFinished() { return input.available() == 0; }
                public boolean isReady() { return true; }
                public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous gateway body"); }
            };
        }
        @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8)); }
    }
}
