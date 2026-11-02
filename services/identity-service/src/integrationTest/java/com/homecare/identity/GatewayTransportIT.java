package com.homecare.identity;

import com.homecare.fixture.*;
import com.homecare.gateway.GatewayServiceApplication;
import com.google.firebase.auth.FirebaseAuth;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Real Gateway -> TLS wire faults. Separate evidence from Google live acceptance. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GatewayTransportIT {
    static FirebaseFixture firebase;
    TestPki pki;
    @TestConfiguration(proxyBeanMethods = false)
    static class LocalFirebase { @Bean FirebaseAuth firebaseAuth() throws Exception { return firebase.auth("transport-fixture"); } }
    @BeforeAll void keys() throws Exception {
        pki = new TestPki(Files.createTempDirectory(Files.createDirectories(Path.of("../../build/security-fixtures")), "transport-"));
        firebase = new FirebaseFixture(pki);
    }
    @AfterAll void close() { if (firebase != null) firebase.close(); }
    ConfigurableApplicationContext gateway(int upstreamPort) {
        var settings = new HashMap<String,Object>();
        settings.put("server.port",0); settings.put("server.address","127.0.0.1");
        settings.put("spring.ssl.bundle.jks.workload.keystore.location",Path.of(pki.file("gateway.p12")).toUri().toString());
        settings.put("spring.ssl.bundle.jks.workload.keystore.password",pki.password);
        settings.put("spring.ssl.bundle.jks.workload.truststore.location",Path.of(pki.file("trust.p12")).toUri().toString());
        settings.put("spring.ssl.bundle.jks.workload.truststore.password",pki.password);
        settings.put("homecare.firebase.project-id",FirebaseFixture.PROJECT);
        settings.put("homecare.gateway.identity-url","https://localhost:"+upstreamPort);
        settings.put("homecare.gateway.provider-url","https://localhost:"+upstreamPort);
        settings.put("homecare.gateway.commerce-url","https://localhost:"+upstreamPort);
        settings.put("spring.autoconfigure.exclude","org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration");
        var app = new SpringApplication(LocalFirebase.class,GatewayServiceApplication.class);
        app.setDefaultProperties(Map.of("spring.config.location",Path.of("../gateway-service/src/main/resources/application.yml").toAbsolutePath().toUri().toString()));
        app.addInitializers(ctx -> ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("transport-fixture",settings)));
        return app.run();
    }
    void expect(ConfigurableApplicationContext gateway, int status, String code) throws Exception {
        int port = ((WebServerApplicationContext)gateway).getWebServer().getPort();
        var request = HttpRequest.newBuilder(URI.create("https://localhost:"+port+"/api/v1/users/me/bootstrap"))
                .timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+firebase.token("transport"))
                .header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString())
                .header("X-Correlation-Id","gw-04").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        var response = HttpClient.newBuilder().sslContext(pki.ssl(null)).build().send(request,HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(JsonMapper.builder().build().readTree(response.body()).at("/error/code").asText()).isEqualTo(code);
        assertThat(response.headers().firstValue("X-Correlation-Id")).contains("gw-04");
    }
    @Test void connectionRefusedIs503() throws Exception {
        int port;
        try (var free = new ServerSocket(0,1,InetAddress.getLoopbackAddress())) { port=free.getLocalPort(); }
        try (var app = gateway(port)) { expect(app,503,"IDENTITY_UNAVAILABLE"); }
    }
    @Test void malformedDownstreamIs502AndMutationIsNotRetried() throws Exception { wireFault(false,502,"DOWNSTREAM_INVALID_RESPONSE"); }
    @Test void responseTimeoutIs504AndMutationIsNotRetried() throws Exception { wireFault(true,504,"DOWNSTREAM_TIMEOUT"); }
    void wireFault(boolean timeout, int status, String code) throws Exception {
        var count = new AtomicInteger();
        try (var server = (SSLServerSocket)pki.ssl("identity").getServerSocketFactory().createServerSocket(0,8,InetAddress.getLoopbackAddress());
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setNeedClientAuth(true); server.setSoTimeout(9000);
            var received = executor.submit(() -> {
                try {
                    while (!server.isClosed()) {
                        try (var socket=(SSLSocket)server.accept()) {
                            socket.setSoTimeout(3000); socket.startHandshake();
                            int matched=0;
                            byte[] end = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
                            while (matched<4) { int b=socket.getInputStream().read(); if (b<0) throw new java.io.EOFException(); matched=b==end[matched]?matched+1:0; }
                            count.incrementAndGet();
                            if (timeout) Thread.sleep(6500);
                            else { socket.getOutputStream().write("NOT-HTTP\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush(); }
                        }
                    }
                } catch (java.net.SocketException | java.net.SocketTimeoutException expectedShutdown) { }
                return null;
            });
            try (var app=gateway(server.getLocalPort())) { expect(app,status,code); }
            server.close(); received.get(12,TimeUnit.SECONDS);
            assertThat(count.get()).isEqualTo(1);
        }
    }
}
