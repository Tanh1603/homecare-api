package com.homecare.live;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.*;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LiveFirebaseIT {
    HttpClient client, workload;
    String edge, internal;
    static final String ME = "/api/v1/users/me";

    @BeforeAll void preflight() throws Exception {
        var required = List.of("FIREBASE_PROJECT_ID", "GOOGLE_APPLICATION_CREDENTIALS", "FIREBASE_WEB_API_KEY",
                "FIREBASE_TEST_UID", "FIREBASE_TEST_EMAIL", "FIREBASE_TEST_PASSWORD", "GATEWAY_URL", "IDENTITY_URL",
                "GATEWAY_TLS_KEYSTORE", "GATEWAY_TLS_KEYSTORE_PASSWORD", "TLS_TRUSTSTORE", "TLS_TRUSTSTORE_PASSWORD",
                "LIVE_DB_URL", "LIVE_DB_USERNAME", "LIVE_DB_PASSWORD");
        var missing = required.stream().filter(key -> !LiveSupport.present(key)).toList();
        if (!"true".equals(System.getenv("FIREBASE_LIVE_TEST"))) {
            System.out.println("BLOCKED: FIREBASE_LIVE_TEST is not true; missing env names: " + String.join(",", missing));
            assumeTrue(false, "BLOCKED: live suite not enabled");
        }
        if (!missing.isEmpty()) System.out.println("BLOCKED missing env names: " + String.join(",", missing));
        assumeTrue(missing.isEmpty(), "BLOCKED missing env names: " + String.join(",", missing));
        assertNull(System.getenv("FIREBASE_AUTH_EMULATOR_HOST"), "Emulator forbidden");
        try {
            client = LiveSupport.client(null); workload = LiveSupport.client("GATEWAY");
            edge = LiveSupport.env("GATEWAY_URL"); internal = LiveSupport.env("IDENTITY_URL");
            assertTrue(edge.startsWith("https://") && internal.startsWith("https://"), "HTTPS required");
            try (var db = LiveSupport.database(false)) { assertTrue(db.isValid(2)); }
            var app = LiveSupport.admin(false); app.delete();
        } catch (Exception failure) { throw new IllegalStateException("Live preflight invalid (details suppressed)"); }
    }
    HttpResponse<String> call(String method, String path, String token, String body, String... headers) throws Exception {
        return LiveSupport.call(client, edge, method, path, token, body, headers);
    }
    void bootstrap(String token) throws Exception {
        var response = call("POST", ME + "/bootstrap", token, "{}", "Idempotency-Key", UUID.randomUUID().toString());
        assertTrue(response.statusCode() == 200 || response.statusCode() == 201, "Bootstrap status invalid; response suppressed");
    }
    @Test @Order(1) void googleTokenBootstrapReadPatchResolve() throws Exception {
        String token = LiveSupport.login(); bootstrap(token);
        var read = call("GET", ME, token, ""); LiveSupport.status(200, read, null);
        String etag = read.headers().firstValue("ETag").orElseThrow();
        var original = LiveSupport.JSON.readTree(read.body());
        var patch = call("PATCH", ME, token, "{\"displayName\":\"Synthetic live test\",\"phone\":null}", "If-Match", etag);
        LiveSupport.status(200, patch, null);
        try { LiveSupport.status(200, call("GET", ME, token, ""), null); }
        finally {
            String restore = LiveSupport.JSON.writeValueAsString(Map.of("displayName", original.get("displayName"), "phone", original.get("phone")));
            LiveSupport.status(200, call("PATCH", ME, token, restore, "If-Match", patch.headers().firstValue("ETag").orElseThrow()), null);
        }
        var resolve = LiveSupport.call(workload, internal, "POST", "/internal/v1/principals/resolve", token, "{}");
        LiveSupport.status(200, resolve, null);
        assertTrue(original.path("id").asText().equals(LiveSupport.JSON.readTree(resolve.body()).path("profileId").asText()), "Resolve/self mismatch");
    }
    @Test @Order(2) void signatureSpoofAndExactGuestBoundary() throws Exception {
        String token = LiveSupport.login(); bootstrap(token);
        String[] pieces = token.split("\\.");
        byte[] signature = Base64.getUrlDecoder().decode(pieces[2]); signature[0] ^= 1;
        String invalid = pieces[0] + "." + pieces[1] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        LiveSupport.status(401, call("GET", ME, invalid, ""), "AUTHENTICATION_INVALID");
        LiveSupport.status(401, call("GET", "/api/v1/products", invalid, ""), "AUTHENTICATION_INVALID");
        var normal = call("GET", ME, token, "");
        var spoof = call("GET", ME, token, "", "X-User-Id", "another-user", "X-Role", "SYSTEM_ADMIN");
        LiveSupport.status(200, spoof, null);
        assertTrue(LiveSupport.JSON.readTree(normal.body()).equals(LiveSupport.JSON.readTree(spoof.body())), "Caller changed by headers");
        LiveSupport.status(404, call("GET", "/api/v1/products/one/reviews", null, ""), "ROUTE_NOT_FOUND");
        LiveSupport.status(404, call("POST", "/api/v1/products", null, "{}"), "ROUTE_NOT_FOUND");
        LiveSupport.status(404, call("POST", "/internal/v1/principals/resolve", token, "{}"), "ROUTE_NOT_FOUND");
        assertTrue(call("GET", "/api/v1/products", null, "").statusCode() >= 400, "Unimplemented catalog must fail closed");
    }
    @Test @Order(3) void expiredGoogleToken() throws Exception {
        assumeTrue(LiveSupport.present("FIREBASE_EXPIRED_ID_TOKEN"), "BLOCKED: FIREBASE_EXPIRED_ID_TOKEN unavailable");
        var app = LiveSupport.admin(false);
        try {
            try { FirebaseAuth.getInstance(app).verifyIdToken(LiveSupport.env("FIREBASE_EXPIRED_ID_TOKEN")); fail("Expected Google token expiry"); }
            catch (FirebaseAuthException failure) { assertEquals(AuthErrorCode.EXPIRED_ID_TOKEN, failure.getAuthErrorCode()); }
            LiveSupport.status(401, call("GET", ME, LiveSupport.env("FIREBASE_EXPIRED_ID_TOKEN"), ""), "AUTHENTICATION_INVALID");
        } finally { app.delete(); }
    }
    @Test @Order(4) void wrongProjectGoogleToken() throws Exception {
        // A second project's issued token is optional; never fabricate it or create a project.
        assumeTrue(LiveSupport.present("FIREBASE_WRONG_PROJECT_ID_TOKEN"), "BLOCKED: FIREBASE_WRONG_PROJECT_ID_TOKEN unavailable");
        LiveSupport.status(401, call("GET", ME, LiveSupport.env("FIREBASE_WRONG_PROJECT_ID_TOKEN"), ""), "AUTHENTICATION_INVALID");
    }
    @Test @Order(5) void directIdentityRejectsNoCertificate() throws Exception {
        String token = LiveSupport.login();
        assertThrows(IllegalStateException.class, () -> LiveSupport.call(client, internal, "GET", ME, token, ""));
        // Positive control prevents a stopped listener from being mistaken for mTLS rejection.
        LiveSupport.status(200, LiveSupport.call(workload, internal, "GET", ME, token, ""), null);
    }
    @Test @Order(6) void directIdentityRejectsForeignWorkload() throws Exception {
        assumeTrue(LiveSupport.present("ROGUE_TLS_KEYSTORE") && LiveSupport.present("ROGUE_TLS_KEYSTORE_PASSWORD"), "BLOCKED: rogue test certificate unavailable");
        LiveSupport.status(403, LiveSupport.call(LiveSupport.client("ROGUE"), internal, "GET", ME, LiveSupport.login(), ""), "WORKLOAD_FORBIDDEN");
    }
    @Test @Order(7) void appDisabledDoesNotInvalidateFirebaseToken() throws Exception {
        String token = LiveSupport.login(); bootstrap(token);
        try (var db = LiveSupport.database(false)) {
            String sql = "UPDATE user_profile SET status=? WHERE id=(SELECT profile_id FROM external_identity WHERE firebase_project_id=? AND firebase_uid=?) AND status=?";
            try (var statement = db.prepareStatement(sql)) {
                statement.setString(1,"DISABLED"); statement.setString(2,LiveSupport.env("FIREBASE_PROJECT_ID"));
                statement.setString(3,LiveSupport.env("FIREBASE_TEST_UID")); statement.setString(4,"ACTIVE");
                assertEquals(1, statement.executeUpdate(), "Only active dedicated test profile can be disabled");
                try { LiveSupport.status(403, call("GET", ME, token, ""), "ACCOUNT_DISABLED"); }
                finally { statement.setString(1,"ACTIVE"); statement.setString(4,"DISABLED"); assertEquals(1,statement.executeUpdate(), "Profile restore failed"); }
            }
        } catch (java.sql.SQLException failure) { throw new IllegalStateException("Live test DB operation failed (details suppressed)"); }
    }
    @Test @Order(8) void firebaseRevokeAndDisable() throws Exception {
        assumeTrue("true".equals(System.getenv("FIREBASE_TEST_MUTATIONS")), "BLOCKED: dedicated user mutation not enabled");
        FirebaseApp app = LiveSupport.admin(true);
        var auth = FirebaseAuth.getInstance(app); String uid = LiveSupport.env("FIREBASE_TEST_UID");
        try {
            assertFalse(auth.getUser(uid).isDisabled(), "Dedicated test user must start enabled");
            String token = LiveSupport.login();
            // Avoid auth_time/revocation timestamp equality at second resolution.
            Thread.sleep(1100); auth.revokeRefreshTokens(uid);
            awaitInvalid(token);
            token = LiveSupport.login();
            try { auth.updateUser(new UserRecord.UpdateRequest(uid).setDisabled(true)); awaitInvalid(token); }
            finally { auth.updateUser(new UserRecord.UpdateRequest(uid).setDisabled(false)); }
        } catch (FirebaseAuthException failure) { throw new IllegalStateException("Firebase user-management operation failed; check operator permissions privately"); }
        finally { app.delete(); }
    }
    void awaitInvalid(String token) throws Exception {
        var deadline = Instant.now().plusSeconds(20);
        do {
            var response = call("GET", ME, token, "");
            if (response.statusCode() == 401) { LiveSupport.status(401,response,"AUTHENTICATION_INVALID"); return; }
            Thread.sleep(500);
        } while (Instant.now().isBefore(deadline));
        fail("Firebase mutation did not produce 401 within 20 seconds");
    }
    @Test @Order(9) void googleEgressFailureAtEachVerifier() throws Exception {
        assumeTrue("true".equals(System.getenv("FIREBASE_NETWORK_FAULT_TEST")), "BLOCKED: Docker egress fault test not enabled");
        String token = LiveSupport.login(); bootstrap(token);
        for (String service : List.of("gateway", "identity")) {
            String project = LiveSupport.env("LIVE_COMPOSE_PROJECT");
            if (!project.matches("[a-z0-9][a-z0-9_-]+")) fail("Invalid Docker project name");
            String container = docker("ps", "-q", "--filter", "label=com.docker.compose.project=" + project,
                    "--filter", "label=com.docker.compose.service=" + service).trim();
            if (!container.matches("[a-f0-9]{12,64}")) fail("Expected exactly one live container");
            String network = project + "_" + service + "_egress";
            docker("network", "disconnect", network, container);
            try { LiveSupport.status(503, call("GET", ME, token, ""), "AUTHENTICATION_UNAVAILABLE"); }
            finally { docker("network", "connect", network, container); }
            LiveSupport.status(200, call("GET", ME, token, ""), null);
        }
    }
    String docker(String... args) throws Exception {
        var command = new ArrayList<String>(); command.add("docker"); command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException("Docker fault-control command failed; inspect network restoration privately");
        return output;
    }
}
