package com.homecare.live;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.firebase.*;
import com.google.firebase.auth.FirebaseAuth;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import javax.net.ssl.*;
import tools.jackson.databind.json.JsonMapper;

/** Operator-only utilities: never on a production runtime classpath. */
final class LiveSupport {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static boolean present(String key) {
        String value = System.getenv(key);
        return value != null && !value.isBlank() && !value.equals("PLACEHOLDER");
    }
    static String env(String key) {
        if (!present(key)) throw new IllegalStateException("Missing operator env: " + key);
        return System.getenv(key);
    }
    static Path file(String key) {
        String value = env(key);
        Path path = value.startsWith("file:") ? Path.of(URI.create(value)) : Path.of(value);
        Path workspace = Path.of("../../..").toAbsolutePath().normalize();
        if (!path.isAbsolute() || path.normalize().startsWith(workspace))
            throw new IllegalStateException("Operator key material must be outside workspace: " + key);
        return path;
    }
    static FirebaseApp admin(boolean operator) throws Exception {
        String key = operator && present("FIREBASE_OPERATOR_CREDENTIALS")
                ? "FIREBASE_OPERATOR_CREDENTIALS" : "GOOGLE_APPLICATION_CREDENTIALS";
        try (var input = Files.newInputStream(file(key))) {
            var credentials = GoogleCredentials.fromStream(input);
            if (!(credentials instanceof ServiceAccountCredentials account)
                    || !env("FIREBASE_PROJECT_ID").equals(account.getProjectId()))
                throw new IllegalStateException("Operator credential project mismatch");
            return FirebaseApp.initializeApp(FirebaseOptions.builder().setCredentials(credentials)
                    .setProjectId(env("FIREBASE_PROJECT_ID")).setConnectTimeout(2000).setReadTimeout(5000).build(),
                    "live-operator-" + UUID.randomUUID());
        } catch (Exception failure) { throw new IllegalStateException("Operator ADC invalid (details suppressed)"); }
    }
    static HttpClient client(String workload) throws Exception {
        var trust = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(file("TLS_TRUSTSTORE"))) {
            trust.load(in, env("TLS_TRUSTSTORE_PASSWORD").toCharArray());
        }
        var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(trust);
        KeyManager[] keys = null;
        if (workload != null) {
            var store = KeyStore.getInstance("PKCS12");
            try (var in = Files.newInputStream(file(workload + "_TLS_KEYSTORE"))) {
                store.load(in, env(workload + "_TLS_KEYSTORE_PASSWORD").toCharArray());
            }
            var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            km.init(store, env(workload + "_TLS_KEYSTORE_PASSWORD").toCharArray()); keys = km.getKeyManagers();
        }
        var ssl = SSLContext.getInstance("TLS"); ssl.init(keys, tm.getTrustManagers(), new SecureRandom());
        return HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    static HttpResponse<String> call(HttpClient client, String origin, String method, String path,
            String token, String body, String... headers) throws Exception {
        try {
            var request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json").header("X-Correlation-Id", "live-" + UUID.randomUUID())
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
            if (token != null) request.header("Authorization", "Bearer " + token);
            for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception failure) { throw new IllegalStateException("Live HTTP transport failed (details suppressed)"); }
    }
    static String login() throws Exception {
        try {
            // No URI/body logging: the URL includes the operator Web API key.
            var request = HttpRequest.newBuilder(URI.create("https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key="
                    + env("FIREBASE_WEB_API_KEY"))).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of(
                            "email", env("FIREBASE_TEST_EMAIL"), "password", env("FIREBASE_TEST_PASSWORD"), "returnSecureToken", true)))).build();
            var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException();
            var result = JSON.readTree(response.body());
            if (!env("FIREBASE_TEST_UID").equals(result.path("localId").asText())) throw new IllegalStateException();
            return result.path("idToken").asText();
        } catch (Exception failure) { throw new IllegalStateException("Google sign-in failed or test UID mismatch (details suppressed)"); }
    }
    static Connection database(boolean mapping) throws Exception {
        String prefix = mapping ? "MAP_DB_" : "LIVE_DB_";
        String url = env(prefix + "URL");
        var uri = URI.create(url.substring("jdbc:".length()));
        String database = uri.getPath().substring(1);
        if (!url.startsWith("jdbc:postgresql://") || uri.getUserInfo() != null || uri.getQuery() != null
                || !(database.matches("hc_test_[a-z0-9]+_identity") || mapping && database.equals("hc_dev_identity")))
            throw new IllegalStateException("Operator operation requires isolated Identity database URL");
        return DriverManager.getConnection(url, env(prefix + "USERNAME"), env(prefix + "PASSWORD"));
    }
    static void status(int expected, HttpResponse<String> response, String code) {
        if (response.statusCode() != expected) throw new AssertionError("Expected HTTP " + expected + "; received " + response.statusCode());
        if (code != null && !code.equals(JSON.readTree(response.body()).at("/error/code").asText()))
            throw new AssertionError("Unexpected error code (response suppressed)");
        if (response.headers().firstValue("X-Correlation-Id").isEmpty()) throw new AssertionError("Missing correlation header");
    }
}
