package com.homecare.fixture;

import com.google.api.client.http.*;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.*;
import com.google.firebase.auth.FirebaseAuth;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Actual Firebase Admin verifier; only Google's certificate/account HTTP responses are local fixtures. */
public final class FirebaseFixture {
    public static final String PROJECT = "homecare-fixture";
    public final Map<String, String> state = new ConcurrentHashMap<>();
    public volatile boolean unavailable;
    private final TestPki pki;
    private final ObjectMapper json = JsonMapper.builder().build();
    private final List<FirebaseApp> apps = new ArrayList<>();
    public FirebaseFixture(TestPki pki) { this.pki = pki; }
    public FirebaseAuth auth(String name) throws Exception {
        var transport = new HttpTransport() {
            @Override protected LowLevelHttpRequest buildRequest(String method, String url) throws IOException {
                return new MockLowLevelHttpRequest(url) {
                    @Override public LowLevelHttpResponse execute() throws IOException {
                        if (unavailable) throw new IOException("Synthetic dependency unavailable");
                        String body;
                        if (url.contains("/metadata/x509/")) {
                            body = json.writeValueAsString(Map.of("fixture-key", Files.readString(java.nio.file.Path.of(pki.file("token.pem")))));
                        } else if (url.contains("accounts:lookup")) {
                            String uid = json.readTree(getContentAsString()).get("localId").get(0).asText();
                            String status = state.getOrDefault(uid, "ACTIVE");
                            body = json.writeValueAsString(Map.of("users", List.of(Map.of("localId", uid,
                                    "disabled", status.equals("DISABLED"), "validSince", status.equals("REVOKED")
                                            ? Long.toString(Instant.now().plusSeconds(600).getEpochSecond()) : "0"))));
                        } else throw new IOException("Unexpected fixture dependency request");
                        return new MockLowLevelHttpResponse().setStatusCode(200).setContentType("application/json")
                                .setContent(body).addHeader("Cache-Control", "public,max-age=3600");
                    }
                };
            }
        };
        var options = FirebaseOptions.builder().setProjectId(PROJECT).setHttpTransport(transport)
                .setCredentials(GoogleCredentials.create(new AccessToken("synthetic-local-credential", new Date(System.currentTimeMillis() + 3600000))))
                .setConnectTimeout(1000).setReadTimeout(1000).build();
        var app = FirebaseApp.initializeApp(options, name + UUID.randomUUID()); apps.add(app); return FirebaseAuth.getInstance(app);
    }
    public String token(String uid, long expiresOffset, String project, boolean wrongKey) throws Exception {
        long now = Instant.now().getEpochSecond();
        var payload = Map.of("sub", uid, "aud", project, "iss", "https://securetoken.google.com/" + project,
                "iat", now - 10, "auth_time", now - 10, "exp", now + expiresOffset, "role", "SYSTEM_ADMIN");
        String unsigned = encode(json.writeValueAsBytes(Map.of("alg", "RS256", "kid", "fixture-key", "typ", "JWT"))) + "." + encode(json.writeValueAsBytes(payload));
        var signature = Signature.getInstance("SHA256withRSA");
        signature.initSign((PrivateKey) pki.store(wrongKey ? "rogue" : "token").getKey(wrongKey ? "rogue" : "token", pki.password.toCharArray()));
        signature.update(unsigned.getBytes(StandardCharsets.US_ASCII)); return unsigned + "." + encode(signature.sign());
    }
    public String token(String uid) throws Exception { return token(uid, 3600, PROJECT, false); }
    private String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    public void close() { apps.forEach(FirebaseApp::delete); }
}
