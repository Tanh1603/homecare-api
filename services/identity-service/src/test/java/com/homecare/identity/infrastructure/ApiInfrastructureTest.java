package com.homecare.identity.infrastructure;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.preauth.AbstractPreAuthenticatedProcessingFilter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP on random port. Fixture controllers/security exist only in test output. */
@SpringBootTest(classes = ApiInfrastructureTest.Fixture.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=api-fixture", "spring.jackson.deserialization.fail-on-unknown-properties=true",
                "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
                "management.endpoint.health.group.readiness.include=readinessState", "server.ssl.enabled=false",
                "spring.ssl.bundle.jks.workload.keystore.location=", "spring.ssl.bundle.jks.workload.truststore.location="})
@ExtendWith(OutputCaptureExtension.class)
class ApiInfrastructureTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    private final HttpClient client = HttpClient.newHttpClient();

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({CorrelationFilter.class, ApiExceptionHandler.class, Controller.class})
    static class Fixture {
        @Bean org.springframework.security.core.userdetails.UserDetailsService noLocalAccounts() {
            return name -> { throw new org.springframework.security.core.userdetails.UsernameNotFoundException("No local accounts"); };
        }
        @Bean @Order(1)
        SecurityFilterChain denyFixture(HttpSecurity http, ObjectMapper json) throws Exception {
            return http.authorizeHttpRequests(a -> a.anyRequest().denyAll()).exceptionHandling(e -> e.authenticationEntryPoint((req,res,failure) -> {
                res.setStatus(401); res.setContentType("application/json");
                json.writeValue(res.getOutputStream(), ApiErrors.body("AUTHENTICATION_REQUIRED", "Required", java.util.List.of(), req));
            })).build();
        }
        @Bean @Order(0)
        SecurityFilterChain fixtureSecurity(HttpSecurity http) throws Exception {
            // Deterministic test principal; never packaged in bootJar and not a production auth bypass.
            var filter = new OncePerRequestFilter() {
                @Override protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest req,
                        jakarta.servlet.http.HttpServletResponse res, jakarta.servlet.FilterChain chain)
                        throws jakarta.servlet.ServletException, java.io.IOException {
                    SecurityContextHolder.getContext().setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated("synthetic-test-principal", null, java.util.List.of()));
                    chain.doFilter(req, res);
                }
            };
            return http.securityMatcher("/__fixture/**").csrf(c -> c.disable())
                    .addFilterBefore(filter, AbstractPreAuthenticatedProcessingFilter.class)
                    .authorizeHttpRequests(a -> a.anyRequest().authenticated()).build();
        }
    }

    record Input(@NotBlank String name) {}
    @RestController
    static class Controller {
        @PostMapping("/__fixture/input") Input input(@Valid @RequestBody Input input) { return input; }
        @GetMapping("/__fixture/page") ApiPagination page(@RequestParam MultiValueMap<String, String> query) {
            return ApiPagination.parse(query, Set.of("createdAt", "id"), Set.of(), "createdAt,desc");
        }
        @GetMapping("/__fixture/failure") void failure() { throw new IllegalStateException("synthetic-secret-password"); }
    }

    private HttpResponse<String> request(String method, String path, String body, String... correlation) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer synthetic-secret-token")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        for (String value : correlation) builder.header(CorrelationFilter.HEADER, value);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void validationUnknownFieldsAndMalformedJsonAreSafe() throws Exception {
        for (String body : new String[]{"{}", "{\"name\":null}", "{\"name\":\"\"}", "{\"name\":\"ok\",\"role\":\"SYSTEM_ADMIN\"}", "{"}) {
            var response = request("POST", "/__fixture/input", body, "test.valid-1");
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(json.readTree(response.body()).at("/error/code").asText()).isEqualTo("REQUEST_VALIDATION_FAILED");
            assertThat(json.readTree(response.body()).at("/error/traceId").asText()).isEqualTo("test.valid-1");
        }
    }

    @Test void paginationRejectsInvalidAndUnknownAndAddsStableTieBreak() throws Exception {
        var defaults = request("GET", "/__fixture/page", "");
        assertThat(defaults.statusCode()).isEqualTo(200);
        var tree = json.readTree(defaults.body());
        assertThat(tree.get("size").asInt()).isEqualTo(20);
        assertThat(tree.at("/sort/1/field").asText()).isEqualTo("id");
        for (String query : new String[]{"size=101", "size=0", "page=-1", "page=2147483648", "page=0&page=1",
                "sort=password,asc", "sort=id,ASC", "userId=other", "sort=id,asc&sort=id,desc"})
            assertThat(request("GET", "/__fixture/page?" + query, "").statusCode()).isEqualTo(400);
    }

    @Test void correlationRejectsMultipleAndInvalidAndIsNotIdentity() throws Exception {
        for (String[] values : new String[][]{{}, {"invalid,header"}, {"x".repeat(65)}, {"first", "second"}}) {
            var response = request("GET", "/__fixture/page", "", values);
            assertThat(response.headers().firstValue(CorrelationFilter.HEADER).orElseThrow())
                    .matches("[0-9a-f-]{36}");
        }
        assertThat(request("GET", "/api/v1/users/me", "", "SYSTEM_ADMIN").statusCode()).isEqualTo(401);
    }

    @Test void failuresAndLogsDoNotExposeSensitiveInput(CapturedOutput output) throws Exception {
        var failure = request("GET", "/__fixture/failure?token=synthetic-query-secret", "", "failure-1");
        assertThat(failure.statusCode()).isEqualTo(500);
        assertThat(failure.body()).doesNotContain("synthetic-secret", "IllegalStateException");
        assertThat(output.getAll()).doesNotContain("synthetic-secret-password", "synthetic-secret-token", "synthetic-query-secret");
    }

    @Test void actuatorAndUnknownRoutesStayProtected() throws Exception {
        for (String path : new String[]{"/actuator/health", "/actuator/health/readiness", "/actuator/env", "/actuator/configprops", "/missing"}) {
            var response = request("GET", path, "", "secure-probe");
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).doesNotContain("DB_PASSWORD", "jdbc:", "components");
        }
        assertThat(request("GET", "/__fixture/input", "").statusCode()).isEqualTo(405);
    }
}
