package com.homecare.identity.infrastructure;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class CorrelationTest {
    @Test void cleanupHappensEvenOnException() {
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationFilter.HEADER, "clean-1");
        assertThatThrownBy(() -> new CorrelationFilter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertThat(MDC.get("correlationId")).isEqualTo("clean-1");
            throw new jakarta.servlet.ServletException("test failure");
        })).isInstanceOf(jakarta.servlet.ServletException.class);
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test void outboundPropagatesOnlyCurrentCorrelation() {
        RestClient.Builder builder = new OutboundHttp().correlatedRestClientBuilder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://fixture.test/one")).andExpect(header(CorrelationFilter.HEADER, "forward-1")).andRespond(withSuccess());
        server.expect(requestTo("http://fixture.test/two")).andExpect(headerDoesNotExist(CorrelationFilter.HEADER)).andRespond(withSuccess());
        var client = builder.build();
        try {
            MDC.put("correlationId", "forward-1");
            client.get().uri("http://fixture.test/one").retrieve().toBodilessEntity();
        } finally { MDC.remove("correlationId"); }
        client.get().uri("http://fixture.test/two").header(CorrelationFilter.HEADER, "stale").retrieve().toBodilessEntity();
        server.verify();
    }
}
