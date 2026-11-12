package com.homecare.common.web.servlet;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.ServletException;

class GatewayOriginFilterTest {

    @Test
    void allowsAllRequestsWhenEnforceGatewayIsFalse() throws ServletException, IOException {
        GatewayOriginFilter filter = new GatewayOriginFilter(false, "secret-123", "token-abc");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void allowsActuatorHealthEvenWhenEnforceGatewayIsTrue() throws ServletException, IOException {
        GatewayOriginFilter filter = new GatewayOriginFilter(true, "secret-123", "token-abc");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void allowsRequestWithValidGatewaySecretHeader() throws ServletException, IOException {
        GatewayOriginFilter filter = new GatewayOriginFilter(true, "secret-123", "token-abc");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test");
        request.addHeader(GatewayOriginFilter.GATEWAY_SECRET_HEADER, "secret-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void allowsRequestWithValidWorkloadTokenHeader() throws ServletException, IOException {
        GatewayOriginFilter filter = new GatewayOriginFilter(true, "secret-123", "token-abc");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/v1/test");
        request.addHeader(GatewayOriginFilter.WORKLOAD_TOKEN_HEADER, "token-abc");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void blocksDirectRequestWhenEnforceGatewayIsTrueAndHeadersMissingOrInvalid() throws ServletException, IOException {
        GatewayOriginFilter filter = new GatewayOriginFilter(true, "secret-123", "token-abc");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("DIRECT_ACCESS_FORBIDDEN");
        assertThat(chain.getRequest()).isNull();
    }
}
