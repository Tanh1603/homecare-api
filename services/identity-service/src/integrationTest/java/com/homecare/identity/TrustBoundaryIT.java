package com.homecare.identity;

import com.homecare.fixture.FirebaseFixture;
import com.homecare.fixture.TestPki;
import com.homecare.gateway.GatewayServiceApplication;
import com.google.firebase.auth.FirebaseAuth;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TrustBoundaryIT {
    static FirebaseFixture firebase;
    TestPki pki;
    ConfigurableApplicationContext identity, gateway;
    HttpClient client;
    String edge, internal;
    final tools.jackson.databind.ObjectMapper json = JsonMapper.builder().build();
    final String run = UUID.randomUUID().toString();

    @TestConfiguration(proxyBeanMethods = false)
    static class LocalFirebase {
        @Bean FirebaseAuth firebaseAuth() throws Exception { return firebase.auth("verified-fixture"); }
    }

    @BeforeAll void start() throws Exception {
        pki = new TestPki(Files.createTempDirectory(Files.createDirectories(Path.of("..", "..", "build", "security-fixtures")), "phase3-pki-"));
        firebase = new FirebaseFixture(pki);
        client = HttpClient.newBuilder().sslContext(pki.ssl(null)).connectTimeout(Duration.ofSeconds(3)).build();
        var identitySettings = tls("identity");
        identitySettings.put("spring.cloud.gateway.server.webmvc.enabled", false);
        // Gateway exists on this test-only combined classpath, not on Identity's production classpath.
        identitySettings.put("spring.autoconfigure.exclude", String.join(",",
                "org.springframework.cloud.gateway.server.mvc.GatewayServerMvcAutoConfiguration",
                "org.springframework.cloud.gateway.server.mvc.GatewayMvcClassPathWarningAutoConfiguration",
                "org.springframework.cloud.gateway.server.mvc.filter.FilterAutoConfiguration",
                "org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctionAutoConfiguration",
                "org.springframework.cloud.gateway.server.mvc.handler.GatewayMultipartAutoConfiguration",
                "org.springframework.cloud.gateway.server.mvc.predicate.PredicateAutoConfiguration"));
        identitySettings.put("spring.datasource.url", DatabaseFoundationIT.url("identity"));
        identitySettings.put("spring.datasource.username", DatabaseFoundationIT.prefix() + "_identity_app");
        identitySettings.put("spring.datasource.password", DatabaseFoundationIT.env("IDENTITY_DB_PASSWORD"));
        identity = startApp(IdentityServiceApplication.class, identitySettings);
        internal = "https://localhost:" + ((WebServerApplicationContext) identity).getWebServer().getPort();
        var gatewaySettings = tls("gateway");
        gatewaySettings.put("spring.autoconfigure.exclude", "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration");
        gatewaySettings.put("homecare.gateway.identity-url", internal);
        gatewaySettings.put("homecare.gateway.provider-url", internal);
        gatewaySettings.put("homecare.gateway.commerce-url", internal);
        gatewaySettings.put("homecare.gateway.connect-timeout", "2s");
        gatewaySettings.put("homecare.gateway.response-timeout", "5s");
        gatewaySettings.put("homecare.gateway.max-body-bytes", 1048576);
        gatewaySettings.put("homecare.gateway.cors-origins", "https://fixture.invalid");
        gatewaySettings.put("spring.cloud.gateway.server.webmvc.forwarded-request-headers-filter.enabled", false);
        gatewaySettings.put("spring.cloud.gateway.server.webmvc.x-forwarded-request-headers-filter.enabled", false);
        gatewaySettings.put("server.ssl.client-auth", "none");
        gateway = startApp(GatewayServiceApplication.class, gatewaySettings);
        edge = "https://localhost:" + ((WebServerApplicationContext) gateway).getWebServer().getPort();
        assertThat(identity.getBean(org.springframework.boot.health.actuate.endpoint.HealthEndpoint.class)
                .healthForPath("readiness").getStatus().getCode()).isEqualTo("UP");
    }
    Map<String, Object> tls(String service) {
        var settings = new HashMap<String, Object>();
        settings.put("server.port", 0); settings.put("server.address", "127.0.0.1");
        settings.put("server.ssl.enabled", true); settings.put("server.ssl.bundle", "workload");
        settings.put("server.ssl.client-auth", "need");
        settings.put("spring.ssl.bundle.jks.workload.keystore.location", Path.of(pki.file(service + ".p12")).toUri().toString());
        settings.put("spring.ssl.bundle.jks.workload.keystore.password", pki.password);
        settings.put("spring.ssl.bundle.jks.workload.keystore.type", "PKCS12");
        settings.put("spring.ssl.bundle.jks.workload.truststore.location", Path.of(pki.file("trust.p12")).toUri().toString());
        settings.put("spring.ssl.bundle.jks.workload.truststore.password", pki.password);
        settings.put("spring.ssl.bundle.jks.workload.truststore.type", "PKCS12");
        settings.put("homecare.firebase.project-id", FirebaseFixture.PROJECT);
        settings.put("homecare.firebase.connect-timeout-ms", 1000);
        settings.put("homecare.firebase.read-timeout-ms", 1000);
        settings.put("management.endpoints.web.exposure.include", "health");
        settings.put("management.endpoint.health.show-details", "never");
        return settings;
    }
    ConfigurableApplicationContext startApp(Class<?> appClass, Map<String, Object> settings) {
        var app = new SpringApplication(LocalFirebase.class, appClass);
        Path config = appClass == IdentityServiceApplication.class ? Path.of("src/main/resources/application.yml")
                : Path.of("../gateway-service/src/main/resources/application.yml");
        app.setDefaultProperties(Map.of("spring.config.location", config.toAbsolutePath().toUri().toString()));
        app.addInitializers(ctx -> ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture-settings", settings)));
        return app.run();
    }
    @AfterAll void stop() {
        if (gateway != null) gateway.close(); if (identity != null) identity.close();
        if (firebase != null) firebase.close();
    }
    HttpResponse<String> call(String method, String path, String token, String body, String... headers) throws Exception {
        return call(client, edge, method, path, token, body, headers);
    }
    HttpResponse<String> call(HttpClient http, String origin, String method, String path, String token, String body, String... headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        for (int i=0; i<headers.length; i+=2) builder.header(headers[i], headers[i+1]);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> bootstrap(String token, String key) throws Exception {
        return call("POST", "/api/v1/users/me/bootstrap", token, "{}", "Idempotency-Key", key);
    }
    String code(HttpResponse<String> response) { return json.readTree(response.body()).at("/error/code").asText(); }

    @Test void tokenVerificationRejectsMissingTamperedExpiredWrongAudienceAndRevocation() throws Exception {
        assertThat(call("GET", "/api/v1/users/me", null, "").statusCode()).isEqualTo(401);
        for (String token : List.of("not.a.token", firebase.token("bad", -3600, FirebaseFixture.PROJECT, false),
                firebase.token("bad", 3600, "another-project", false), firebase.token("bad", 3600, FirebaseFixture.PROJECT, true))) {
            var result = call("GET", "/api/v1/users/me", token, "");
            assertThat(result.statusCode()).isEqualTo(401); assertThat(code(result)).isEqualTo("AUTHENTICATION_INVALID");
        }
        for (String status : List.of("REVOKED", "DISABLED")) {
            String uid = run + status; firebase.state.put(uid, status);
            assertThat(code(call("GET", "/api/v1/users/me", firebase.token(uid), ""))).isEqualTo("AUTHENTICATION_INVALID");
        }
    }

    @Test void bootstrapPatchReplayAndSelfIsolationThroughRealGateway() throws Exception {
        String token = firebase.token(run + "profile");
        assertThat(call("GET", "/api/v1/users/me", token, "").statusCode()).isEqualTo(404);
        String key = UUID.randomUUID().toString();
        var created = bootstrap(token, key);
        assertThat(created.statusCode()).isEqualTo(201);
        IdentityOpenApi.profile(json.readTree(created.body()));
        assertThat(json.readTree(created.body()).get("role").asText()).isEqualTo("HOUSEHOLD_USER");
        assertThat(bootstrap(token, key).body()).isEqualTo(created.body());
        assertThat(bootstrap(token, UUID.randomUUID().toString()).statusCode()).isEqualTo(200);
        assertThat(call("POST", "/api/v1/users/me/bootstrap", token, "{\"role\":\"SYSTEM_ADMIN\"}", "Idempotency-Key", UUID.randomUUID().toString()).statusCode()).isEqualTo(400);
        var changed = call("PATCH", "/api/v1/users/me", token, "{\"displayName\":\"Synthetic name\",\"phone\":\"+84 000\"}", "If-Match", "\"v0\"");
        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(call("PATCH", "/api/v1/users/me", token, "{\"displayName\":null}", "If-Match", "\"v1\"").statusCode()).isEqualTo(200);
        var reread = call("GET", "/api/v1/users/me", token, "", "X-User-Id", "other", "X-Role", "SYSTEM_ADMIN", "X-Caller-Role", "SYSTEM_ADMIN", "X-Correlation-Id", "p3-safe");
        var profile = json.readTree(reread.body());
        assertThat(profile.get("displayName").isNull()).isTrue();
        assertThat(profile.get("phone").asText()).isEqualTo("+84 000");
        assertThat(profile.get("role").asText()).isEqualTo("HOUSEHOLD_USER");
        assertThat(reread.headers().firstValue("X-Correlation-Id")).contains("p3-safe");
        assertThat(call("PATCH", "/api/v1/users/me", token, "{\"role\":\"SYSTEM_ADMIN\"}", "If-Match", "\"v2\"").statusCode()).isEqualTo(400);
        assertThat(call("PATCH", "/api/v1/users/me", token, "{\"id\":\"other-profile\"}", "If-Match", "\"v2\"").statusCode()).isEqualTo(400);
        assertThat(call("PATCH", "/api/v1/users/me", token, "{\"phone\":null}").statusCode()).isEqualTo(428);
        assertThat(call("PATCH", "/api/v1/users/me", token, "{\"phone\":null}", "If-Match", "\"v0\"").statusCode()).isEqualTo(412);
        assertThat(call("GET", "/api/v1/users/" + profile.get("id").asText(), firebase.token(run + "other"), "").statusCode()).isEqualTo(404);
        assertThat(call("GET", "/api/v1/users/me?profileId=" + profile.get("id").asText(), token, "").statusCode()).isEqualTo(400);
        try (var db = DatabaseFoundationIT.connect("identity", true); var statement = db.prepareStatement("UPDATE user_profile SET status='DISABLED' WHERE id=?")) {
            statement.setObject(1, UUID.fromString(profile.get("id").asText())); statement.executeUpdate();
        }
        assertThat(code(bootstrap(token, key))).isEqualTo("ACCOUNT_DISABLED");
    }

    @Test void concurrentBootstrapCreatesOneProfile() throws Exception {
        String uid = run + "race", token = firebase.token(uid);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<HttpResponse<String>> task = () -> { gate.await(); return bootstrap(token, UUID.randomUUID().toString()); };
            var a = executor.submit(task); var b = executor.submit(task); gate.countDown();
            var one = a.get(20, TimeUnit.SECONDS); var two = b.get(20, TimeUnit.SECONDS);
            assertThat(List.of(one.statusCode(), two.statusCode())).containsExactlyInAnyOrder(201,200);
            assertThat(json.readTree(one.body()).get("id")).isEqualTo(json.readTree(two.body()).get("id"));
        }
        try (var db = DatabaseFoundationIT.connect("identity", false); var query = db.prepareStatement("SELECT count(*) FROM external_identity WHERE firebase_uid=?")) {
            query.setString(1, uid); try (var rows=query.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(1); }
        }
    }

    @Test void directBypassRequiresCertificateAndWorkloadAndValidBearer() throws Exception {
        String token = firebase.token(run + "bypass");
        assertThatThrownBy(() -> call(client, internal, "GET", "/api/v1/users/me", token, "")).isInstanceOf(java.io.IOException.class);
        var untrusted = HttpClient.newBuilder().sslContext(pki.ssl("outsider")).build();
        assertThatThrownBy(() -> call(untrusted, internal, "GET", "/api/v1/users/me", token, "")).isInstanceOf(java.io.IOException.class);
        var rogue = HttpClient.newBuilder().sslContext(pki.ssl("rogue")).build();
        assertThat(code(call(rogue, internal, "GET", "/api/v1/users/me", token, "", "X-Caller-Id", "gateway"))).isEqualTo("WORKLOAD_FORBIDDEN");
        var workload = HttpClient.newBuilder().sslContext(pki.ssl("gateway")).build();
        assertThat(call(workload, internal, "GET", "/api/v1/users/me", "invalid", "").statusCode()).isEqualTo(401);
        assertThat(call("POST", "/internal/v1/principals/resolve", token, "{}").statusCode()).isEqualTo(404);
    }

    @Test void exactGuestPathsCannotWidenAccessAndDependenciesFailClosed() throws Exception {
        for (String path : List.of("/api/v1/products/x/reviews", "/api/v1/providers/x/schedule", "/actuator/env", "/internal/v1/principals/resolve"))
            assertThat(call("GET", path, null, "").statusCode()).isEqualTo(404);
        assertThat(call("POST", "/api/v1/products", null, "{}").statusCode()).isEqualTo(404);
        assertThat(call("GET", "/api/v1/products", "bad", "").statusCode()).isEqualTo(401);
        // Guest is forwarded without invented identity; Identity fixture target denies, so no synthetic guest business API is exposed.
        assertThat(call("GET", "/api/v1/products", null, "").statusCode()).isEqualTo(401);
        try {
            firebase.unavailable = true;
            assertThat(code(call("GET", "/api/v1/users/me", firebase.token(run + "offline"), ""))).isEqualTo("AUTHENTICATION_UNAVAILABLE");
        } finally { firebase.unavailable = false; }
    }

    @Test void fiveRolesUseOnlyTheirOwnProfileAndResolveIsLive() throws Exception {
        var workload = HttpClient.newBuilder().sslContext(pki.ssl("gateway")).build();
        Set<String> ids = new HashSet<>();
        for (String role : List.of("HOUSEHOLD_USER", "SERVICE_PROVIDER", "TECHNICIAN", "MERCHANT", "SYSTEM_ADMIN")) {
            String token = firebase.token(run + role);
            var created = bootstrap(token, UUID.randomUUID().toString());
            String id = json.readTree(created.body()).get("id").asText(); ids.add(id);
            // Operator-only role provisioning on synthetic test records, never a public grant endpoint.
            try (var db = DatabaseFoundationIT.connect("identity", true); var update = db.prepareStatement("UPDATE profile_role SET role=? WHERE profile_id=?")) {
                update.setString(1, role); update.setObject(2, UUID.fromString(id)); update.executeUpdate();
            }
            var self = call("GET", "/api/v1/users/me", token, "");
            assertThat(json.readTree(self.body()).get("role").asText()).isEqualTo(role);
            var resolved = call(workload, internal, "POST", "/internal/v1/principals/resolve", token, "{}");
            assertThat(resolved.statusCode()).isEqualTo(200);
            assertThat(json.readTree(resolved.body()).get("profileId").asText()).isEqualTo(id);
            assertThat(json.readTree(resolved.body()).get("role").asText()).isEqualTo(role);
            assertThat(call(workload, internal, "POST", "/internal/v1/principals/resolve", token,
                    "{\"subject\":\"other\"}").statusCode()).isEqualTo(400);
        }
        assertThat(ids).hasSize(5);
    }

    @Test void operatorSeedManifestCreatesFiveSyntheticAccountsWithoutPublicGrantApi() throws Exception {
        // Fresh identities may already be present when this suite is rerun; validate instead of changing their roles.
        try (var db = DatabaseFoundationIT.connect("identity", true); var sql = db.createStatement()) {
            try (var rows = sql.executeQuery("SELECT count(*) FROM external_identity WHERE firebase_uid LIKE 'fixture-%'")) {
                rows.next();
                if (rows.getInt(1) == 0) {
                    String seed = Files.readString(Path.of("../../scripts/seed-identity-test.sql"))
                            .replace(":'firebase_project_id'", "'" + FirebaseFixture.PROJECT + "'");
                    sql.execute(seed);
                }
            }
        }
        Map<String, String> roles = Map.of("household", "HOUSEHOLD_USER", "provider", "SERVICE_PROVIDER", "technician", "TECHNICIAN",
                "merchant", "MERCHANT", "admin", "SYSTEM_ADMIN");
        for (var role : roles.entrySet()) {
            String token = firebase.token("fixture-" + role.getKey());
            var response = call("GET", "/api/v1/users/me", token, "");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json.readTree(response.body()).get("role").asText()).isEqualTo(role.getValue());
            assertThat(call("POST", "/api/v1/admin/users/grant-role", token, "{}").statusCode()).isEqualTo(404);
        }
    }

    @Test @Order(Integer.MAX_VALUE) void identityOutageFailsClosed() throws Exception {
        identity.getBean(com.zaxxer.hikari.HikariDataSource.class).close();
        var databaseDown = call("GET", "/api/v1/users/me", firebase.token(run + "database-down"), "");
        assertThat(databaseDown.statusCode()).isEqualTo(503);
        assertThat(code(databaseDown)).isEqualTo("IDENTITY_UNAVAILABLE");
        identity.close();
        var result = call("GET", "/api/v1/users/me", firebase.token(run + "downstream-down"), "");
        assertThat(result.statusCode()).isEqualTo(503);
        assertThat(code(result)).isEqualTo("IDENTITY_UNAVAILABLE");
    }

    @Test void idempotencyExpiryRemovesReplayWithoutRemovingProfile() throws Exception {
        String token = firebase.token(run + "expiry"), key = UUID.randomUUID().toString();
        var response = bootstrap(token, key);
        UUID profileId = UUID.fromString(json.readTree(response.body()).get("id").asText());
        try (var db = DatabaseFoundationIT.connect("identity", true)) {
            try (var query = db.prepareStatement("SELECT EXTRACT(EPOCH FROM expires_at-committed_at) FROM identity_idempotency WHERE profile_id=?")) {
                query.setObject(1, profileId);
                try (var row = query.executeQuery()) { assertThat(row.next()).isTrue(); assertThat(row.getLong(1)).isEqualTo(86400); }
            }
            try (var update = db.prepareStatement("UPDATE identity_idempotency SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE profile_id=?")) {
                update.setObject(1, profileId); update.executeUpdate();
            }
        }
        identity.getBean(com.homecare.identity.profile.IdempotencyCleanupJob.class).purgeExpired();
        assertThat(bootstrap(token, key).statusCode()).isEqualTo(200);
        assertThat(json.readTree(call("GET", "/api/v1/users/me", token, "").body()).get("id").asText()).isEqualTo(profileId.toString());
    }
}
