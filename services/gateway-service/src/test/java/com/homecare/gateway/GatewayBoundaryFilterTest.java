package com.homecare.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.Collections;
import static org.assertj.core.api.Assertions.*;

class GatewayBoundaryFilterTest {
    @Test void removesAllCallerAndForwardedHeadersRatherThanTrustingThem() {
        var source = new MockHttpServletRequest();
        for (String header : new String[]{"X-User-Id", "x-role", "X-Caller-Roles", "Forwarded", "X-Forwarded-For", "X-SSL-Cert", "Cookie"})
            source.addHeader(header, "synthetic-spoof");
        source.addHeader("Authorization", "Bearer synthetic"); source.addHeader("X-Correlation-Id", "untrusted");
        var sanitized = new GatewayBoundaryFilter.SanitizedRequest(source, "validated", new byte[0]);
        for (String header : new String[]{"X-User-Id", "x-role", "X-Caller-Roles", "Forwarded", "X-Forwarded-For", "X-SSL-Cert", "Cookie"}) {
            assertThat(sanitized.getHeader(header)).isNull();
            assertThat(Collections.list(sanitized.getHeaders(header))).isEmpty();
            assertThat(Collections.list(sanitized.getHeaderNames())).doesNotContain(header);
        }
        assertThat(sanitized.getHeader("Authorization")).isEqualTo("Bearer synthetic");
        assertThat(sanitized.getHeader("X-Correlation-Id")).isEqualTo("validated");
    }
    @Test void guestScopeIsExactMethodAndResource() {
        assertThat(GatewayRoutePolicy.guest("GET", "/api/v1/providers/abc")).isTrue();
        for (String path : new String[]{"/api/v1/providers/abc/schedule", "/api/v1/products/abc/reviews", "/api/v1/users/me", "/api/v1/providers/"})
            assertThat(GatewayRoutePolicy.guest("GET", path)).isFalse();
        assertThat(GatewayRoutePolicy.guest("POST", "/api/v1/products")).isFalse();
    }
}
