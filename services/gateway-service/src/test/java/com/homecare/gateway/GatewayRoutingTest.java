package com.homecare.gateway;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** Real Gateway HTTP forwarding; the upstream is a transport fixture, not Identity/Keycloak. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayRoutingTest {
    private static final AtomicInteger calls = new AtomicInteger();
    private static HttpServer upstream;
    @Value("${local.server.port}") private int port;

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry properties) throws Exception {
        properties.add("SERVER_PORT", () -> "0");
        properties.add("KEYCLOAK_JWK_SET_URI", () -> "http://127.0.0.1:1/unused-test-jwks");
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            calls.incrementAndGet();
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String result = exchange.getRequestMethod() + " " + exchange.getRequestURI() + " " + requestBody;
            byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-Upstream-Fixture", "identity");
            String corrId = exchange.getRequestHeaders().getFirst("X-Correlation-Id");
            if (corrId != null) {
                exchange.getResponseHeaders().add("X-Upstream-Correlation-Id", corrId);
            }
            exchange.sendResponseHeaders(202, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        upstream.start();
        String upstreamBase = "http://127.0.0.1:" + upstream.getAddress().getPort();
        properties.add("IDENTITY_URL", () -> upstreamBase);
        properties.add("HOUSEHOLD_URL", () -> upstreamBase);
        properties.add("PROVIDER_URL", () -> upstreamBase);
        properties.add("BOOKING_URL", () -> upstreamBase);
        properties.add("COMMERCE_URL", () -> upstreamBase);
        properties.add("REVIEW_URL", () -> upstreamBase);
        properties.add("NOTIFICATION_URL", () -> upstreamBase);
    }

    @AfterAll static void shutdown() {
        if (upstream != null) upstream.stop(0);
    }

    @Test void forwardsAuthPostWithoutChangingPathQueryOrBody() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login?test=1"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"probe\":true}")).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(202, response.statusCode());
        assertEquals("identity", response.headers().firstValue("X-Upstream-Fixture").orElseThrow());
        assertEquals("POST /api/v1/auth/login?test=1 {\"probe\":true}", response.body());
        assertTrue(response.headers().firstValue("X-Correlation-Id").isPresent());
        assertTrue(response.headers().firstValue("X-Upstream-Correlation-Id").isPresent());
    }

    @Test void missingBearerStillBlocksProtectedProfileBeforeUpstreamWithJsonEnvelope() throws Exception {
        int before = calls.get();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/users/me"))
            .header("X-Correlation-Id", "corr-401-test")
            .GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
        assertEquals(before, calls.get());
        assertTrue(response.body().contains("\"errorCode\":\"UNAUTHENTICATED\""));
        assertTrue(response.body().contains("\"traceId\":\"corr-401-test\""));
    }

    @Test void blocksInternalRoutesAndAllowsGuestGetAllowlist() throws Exception {
        int before = calls.get();
        var internalReq = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/v1/offerings/verify"))
            .header("X-Correlation-Id", "corr-internal-block")
            .GET().build();
        var internalResp = HttpClient.newHttpClient().send(internalReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, internalResp.statusCode());
        assertEquals(before, calls.get());
        assertTrue(internalResp.body().contains("corr-internal-block"));

        var guestServicesResp = get("/api/v1/services");
        assertEquals(202, guestServicesResp.statusCode());
        assertEquals("GET /api/v1/services ", guestServicesResp.body());

        var guestProductsResp = get("/api/v1/products");
        assertEquals(202, guestProductsResp.statusCode());
        assertEquals("GET /api/v1/products ", guestProductsResp.body());

        var vnpayIpnResp = get("/api/v1/payments/vnpay/ipn?vnp_TxnRef=ord1");
        assertEquals(202, vnpayIpnResp.statusCode());
    }

    @Test void forwardsServiceDocsWithoutServicePrefix() throws Exception {
        var response = get("/identity-service/v3/api-docs");
        assertEquals(202, response.statusCode());
        assertEquals("GET /v3/api-docs ", response.body());
        assertEquals("identity", response.headers().firstValue("X-Upstream-Fixture").orElseThrow());
    }

    @Test void swaggerConfigListsIdentityAndUiIsAccessible() throws Exception {
        var config = get("/v3/api-docs/swagger-config");
        assertEquals(200, config.statusCode());
        assertTrue(config.body().contains("/identity-service/v3/api-docs"));
        var redirect = get("/swagger-ui.html");
        assertTrue(redirect.statusCode() >= 300 && redirect.statusCode() < 400);
        var page = get(redirect.headers().firstValue("Location").orElseThrow());
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("Swagger UI"));
    }

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
