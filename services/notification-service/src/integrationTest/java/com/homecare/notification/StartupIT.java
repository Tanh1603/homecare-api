package com.homecare.notification;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = NotificationServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StartupIT {
    @LocalServerPort int port;
    @Autowired HealthEndpoint health;

    @Test void realDependenciesReadyButHttpProtected() throws Exception {
        assertThat(health.healthForPath("readiness").getStatus().getCode()).isEqualTo("UP");
        var client = HttpClient.newHttpClient();
        for (String path : new String[]{"/actuator/health/readiness", "/actuator/env", "/api/v1/users/me"}) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isIn(401, 403);
            assertThat(response.body()).doesNotContain("jdbc:", "DB_PASSWORD", "components");
        }
    }
}
