package com.homecare.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.testcontainers.containers.wait.strategy.Wait;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.homecare.identity.IdentityServiceApplication;
import com.homecare.gateway.GatewayServiceApplication;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = IdentityServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.web-application-type=servlet",
                "spring.config.location=classpath:/identity-service-application.yml",
                "spring.cloud.gateway.enabled=false",
                "spring.autoconfigure.exclude="
                        + "org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration,"
                        + "org.springframework.cloud.gateway.config.GatewayAutoConfiguration,"
                        + "org.springframework.cloud.gateway.config.GatewayResilience4JCircuitBreakerAutoConfiguration,"
                        + "org.springframework.cloud.gateway.config.GatewayNoLoadBalancerClientAutoConfiguration,"
                        + "org.springframework.cloud.gateway.config.GatewayFunctionAutoConfiguration,"
                        + "org.springframework.cloud.gateway.config.GatewayMetricsAutoConfiguration,"
                        + "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration,"
                        + "org.springframework.cloud.gateway.config.GatewayStreamAutoConfiguration,"
                        + "org.springframework.cloud.gateway.discovery.GatewayDiscoveryClientAutoConfiguration,"
                        + "org.springframework.cloud.gateway.config.SimpleUrlHandlerMappingGlobalCorsAutoConfiguration,"
                        + "org.springframework.cloud.gateway.config.GatewayReactiveLoadBalancerClientAutoConfiguration,"
                        + "org.springframework.cloud.gateway.config.LocalResponseCacheAutoConfiguration,"
                        + "org.springframework.cloud.gateway.config.GatewayTracingAutoConfiguration"
        })
class IdentityServiceIntegrationTest {

    private static final String REALM = "homecare-test";
    private static final String CLIENT_ID = "homecare-client";
    private static final String CLIENT_SECRET = "test-client-secret";
    private static final String TEST_ADMIN = "test-admin";
    private static final String TEST_ADMIN_PASSWORD = "test-admin-password";

    @Container
    private static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    private static final GenericContainer<?> keycloak = new GenericContainer<>(
            DockerImageName.parse("quay.io/keycloak/keycloak:26.0.8"))
            .withExposedPorts(8080)
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", TEST_ADMIN)
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", TEST_ADMIN_PASSWORD)
            .withEnv("KC_HOSTNAME_STRICT", "false")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("homecare-test-realm.json"),
                    "/opt/keycloak/data/import/homecare-test-realm.json")
            .withCommand("start-dev", "--import-realm")
            .waitingFor(Wait.forHttp("/realms/" + REALM + "/.well-known/openid-configuration")
                    .forStatusCode(HttpStatus.OK.value()));

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry properties) {
        properties.add("DB_URL", postgres::getJdbcUrl);
        properties.add("DB_USER", postgres::getUsername);
        properties.add("DB_PASSWORD", postgres::getPassword);
        properties.add("KEYCLOAK_ISSUER_URI", () -> keycloakUrl() + "/realms/" + REALM);
        properties.add("KEYCLOAK_JWK_SET_URI",
                () -> keycloakUrl() + "/realms/" + REALM + "/protocol/openid-connect/certs");
        properties.add("KEYCLOAK_URL", IdentityServiceIntegrationTest::keycloakUrl);
        properties.add("KEYCLOAK_REALM", () -> REALM);
        properties.add("KEYCLOAK_CLIENT_ID", () -> CLIENT_ID);
        properties.add("KEYCLOAK_CLIENT_SECRET", () -> CLIENT_SECRET);
        properties.add("KEYCLOAK_ADMIN_USERNAME", () -> TEST_ADMIN);
        properties.add("KEYCLOAK_ADMIN_PASSWORD", () -> TEST_ADMIN_PASSWORD);
    }

    @Test
    void registersAuthenticatesProtectsProfileAndChangesPassword() throws Exception {
        String email = "identity-" + UUID.randomUUID() + "@example.test";
        String correlationId = "identity-test-" + UUID.randomUUID();
        Map<String, Object> registration = Map.of(
                "username", email,
                "email", email,
                "password", "initial-test-password",
                "firstName", "Test",
                "lastName", "User");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-Id", correlationId);

        ApiResponse registered = exchange("POST", "/api/v1/auth/register", registration, headers);

        assertThat(registered.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(registered.headers().getFirst("X-Correlation-Id")).isEqualTo(correlationId);
        assertThat(registered.body().path("success").asBoolean()).isTrue();
        assertThat(registered.body().path("code").asInt()).isEqualTo(HttpStatus.CREATED.value());
        assertThat(registered.body().path("message").asText()).isNotBlank();
        assertThat(registered.body().path("timestamp").asText()).isNotBlank();
        assertThat(registered.body().has("errorCode")).isFalse();
        assertThat(registered.body().path("data").path("email").asText()).isEqualTo(email);
        assertThat(registered.body().path("traceId").asText()).isEqualTo(correlationId);

        markEmailVerified(email);
        ApiResponse initialLogin = login(email, "initial-test-password");
        assertThat(initialLogin.status()).isEqualTo(HttpStatus.OK);
        String accessToken = initialLogin.body().path("data").path("tokens").path("accessToken").asText();
        assertThat(accessToken).isNotBlank();

        HttpHeaders authorizedHeaders = new HttpHeaders();
        authorizedHeaders.setBearerAuth(accessToken);
        ApiResponse profile = exchange("GET", "/api/v1/users/me", null, authorizedHeaders);
        assertThat(profile.status()).isEqualTo(HttpStatus.OK);
        assertThat(profile.body().path("data").path("email").asText()).isEqualTo(email);

        try (ConfigurableApplicationContext gateway = new SpringApplicationBuilder(GatewayServiceApplication.class)
                .run(
                        "--spring.main.web-application-type=reactive",
                        "--spring.config.location=classpath:/gateway-service-application.yml",
                        "--spring.autoconfigure.exclude="
                                + "org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration,"
                                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                                + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
                        "--SERVER_PORT=0",
                        "--IDENTITY_URL=http://localhost:" + port,
                        "--KEYCLOAK_JWK_SET_URI="
                                + keycloakUrl() + "/realms/" + REALM + "/protocol/openid-connect/certs")) {
            int gatewayPort = ((WebServerApplicationContext) gateway).getWebServer().getPort();
            HttpHeaders gatewayHeaders = new HttpHeaders();
            gatewayHeaders.setBearerAuth(accessToken);
            gatewayHeaders.set("X-Correlation-Id", correlationId);
            ApiResponse gatewayProfile = exchange(
                    "http://localhost:" + gatewayPort,
                    "GET",
                    "/api/v1/users/me",
                    null,
                    gatewayHeaders);
            assertThat(gatewayProfile.status()).isEqualTo(HttpStatus.OK);
            assertThat(gatewayProfile.body().path("data").path("email").asText()).isEqualTo(email);
            assertThat(gatewayProfile.headers().getFirst("X-Correlation-Id")).isEqualTo(correlationId);

            ApiResponse gatewayValidationError = exchange(
                    "http://localhost:" + gatewayPort,
                    "POST",
                    "/api/v1/auth/register",
                    Map.of(
                            "username", "invalid-email",
                            "email", "not-an-email",
                            "password", "initial-test-password",
                            "firstName", "Test",
                            "lastName", "User"),
                    headers);
            assertThat(gatewayValidationError.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(gatewayValidationError.body().path("success").asBoolean()).isFalse();
            assertThat(gatewayValidationError.body().path("code").asInt()).isEqualTo(HttpStatus.BAD_REQUEST.value());
            assertThat(gatewayValidationError.body().path("errorCode").asText()).isEqualTo("VALIDATION_FAILED");
            assertThat(gatewayValidationError.body().has("data")).isTrue();
            assertThat(gatewayValidationError.body().path("data").isNull()).isTrue();
            assertThat(gatewayValidationError.body().path("message").asText())
                    .isEqualTo("The request contains invalid data.");
            assertThat(gatewayValidationError.body().path("traceId").asText()).isEqualTo(correlationId);
            assertThat(gatewayValidationError.body().path("errors").get(0).path("field").asText())
                    .isEqualTo("email");
            assertThat(gatewayValidationError.body().path("errors").get(0).path("message").asText())
                    .isEqualTo("Enter a valid email address.");
        }

        ApiResponse badCurrentPassword = exchange(
                "POST",
                "/api/v1/users/me/change-password",
                Map.of(
                        "currentPassword", "incorrect-test-password",
                        "newPassword", "replacement-test-password"),
                authorizedHeaders);
        assertThat(badCurrentPassword.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(badCurrentPassword.body().path("errorCode").asText())
                .isEqualTo("INVALID_CREDENTIALS");

        ApiResponse changedPassword = exchange(
                "POST",
                "/api/v1/users/me/change-password",
                Map.of(
                        "currentPassword", "initial-test-password",
                        "newPassword", "replacement-test-password"),
                authorizedHeaders);
        assertThat(changedPassword.status()).isEqualTo(HttpStatus.OK);
        assertThat(login(email, "replacement-test-password").status()).isEqualTo(HttpStatus.OK);

        ApiResponse unauthorized = exchange("GET", "/api/v1/users/me", null, new HttpHeaders());
        assertThat(unauthorized.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unauthorized.body().path("errorCode").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(unauthorized.body().path("message").asText()).contains("Authentication is required");
        assertThat(unauthorized.body().path("data").isNull()).isTrue();

    }

    private ApiResponse login(String email, String password) throws Exception {
        return exchange("POST", "/api/v1/auth/login",
                Map.of("email", email, "password", password), new HttpHeaders());
    }

    private ApiResponse exchange(
            String method,
            String path,
            Object body,
            HttpHeaders headers) throws Exception {
        return exchange("http://localhost:" + port, method, path, body, headers);
    }

    private ApiResponse exchange(
            String baseUrl,
            String method,
            String path,
            Object body,
            HttpHeaders headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.forEach((name, values) -> values.forEach(value -> request.header(name, value)));
        if (body != null) {
            request.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request.build(), HttpResponse.BodyHandlers.ofString());
        HttpHeaders responseHeaders = new HttpHeaders();
        response.headers().map().forEach(responseHeaders::put);
        JsonNode responseBody = response.body().isBlank()
                ? objectMapper.createObjectNode()
                : objectMapper.readTree(response.body());
        return new ApiResponse(HttpStatus.valueOf(response.statusCode()), responseBody, responseHeaders);
    }

    private void markEmailVerified(String email) {
        try (Keycloak admin = KeycloakBuilder.builder()
                .serverUrl(keycloakUrl())
                .realm("master")
                .username(TEST_ADMIN)
                .password(TEST_ADMIN_PASSWORD)
                .clientId("admin-cli")
                .build()) {
            var users = admin.realm(REALM).users();
            List<UserRepresentation> matches = users.searchByEmail(email, true);
            assertThat(matches).hasSize(1);
            UserRepresentation user = matches.getFirst();
            user.setEmailVerified(true);
            user.setRequiredActions(List.of());
            users.get(user.getId()).update(user);
        }
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static String keycloakUrl() {
        return "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
    }

    private record ApiResponse(HttpStatus status, JsonNode body, HttpHeaders headers) {
    }
}
