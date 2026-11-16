package com.homecare.common.web;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

import com.homecare.common.idempotency.IdempotencyService;
import com.homecare.common.security.SupabaseJwtConverterSupport;

class EtagAndIdempotencyTest {

    @Test
    void etagValidationThrows428OnMissingIfMatchAnd412OnVersionMismatch() {
        assertThat(EtagSupport.format(3L)).isEqualTo("\"v3\"");

        assertThatThrownBy(() -> EtagSupport.verifyIfMatch(null, 3L))
                .isInstanceOf(EtagSupport.PreconditionException.class)
                .satisfies(ex -> {
                    var pe = (EtagSupport.PreconditionException) ex;
                    assertThat(pe.status()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
                    assertThat(pe.errorCode()).isEqualTo("PRECONDITION_REQUIRED");
                });

        assertThatThrownBy(() -> EtagSupport.verifyIfMatch("\"v2\"", 3L))
                .isInstanceOf(EtagSupport.PreconditionException.class)
                .satisfies(ex -> {
                    var pe = (EtagSupport.PreconditionException) ex;
                    assertThat(pe.status()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
                    assertThat(pe.errorCode()).isEqualTo("RESOURCE_VERSION_MISMATCH");
                });

        EtagSupport.verifyIfMatch("\"v3\"", 3L);
    }

    @Test
    void idempotencyServiceReplaysSamePayloadAndRejectsReusedKeyWithDifferentPayloadOrInvalidKey() {
        IdempotencyService idempotencyService = new IdempotencyService();

        assertThatThrownBy(
                () -> idempotencyService.execute("user-1", "POST /bookings", "short-key", "{\"a\":1}", () -> "ok"))
                .isInstanceOf(IdempotencyService.IdempotencyException.class)
                .satisfies(ex -> {
                    var ie = (IdempotencyService.IdempotencyException) ex;
                    assertThat(ie.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ie.errorCode()).isEqualTo("VALIDATION_FAILED");
                });

        String validKey = "idem_key_1234567890_abcd";
        String first = idempotencyService.execute("user-1", "POST /bookings", validKey, "{\"serviceId\":\"srv1\"}",
                () -> "booking-001");
        String replay = idempotencyService.execute("user-1", "POST /bookings", validKey, "{\"serviceId\":\"srv1\"}",
                () -> "booking-002");
        assertThat(first).isEqualTo("booking-001");
        assertThat(replay).isEqualTo("booking-001");

        assertThatThrownBy(() -> idempotencyService.execute("user-1", "POST /bookings", validKey,
                "{\"serviceId\":\"srv2\"}", () -> "booking-003"))
                .isInstanceOf(IdempotencyService.IdempotencyException.class)
                .satisfies(ex -> {
                    var ie = (IdempotencyService.IdempotencyException) ex;
                    assertThat(ie.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ie.errorCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                });

        AtomicInteger runs = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String concurrentKey = "idem_concurrent_1234567890";
        var r1 = new java.util.concurrent.atomic.AtomicReference<String>();
        var r2 = new java.util.concurrent.atomic.AtomicReference<String>();
        Thread t1 = new Thread(() -> r1.set(idempotencyService.execute(
                "user-1", "POST /orders", concurrentKey, "{\"x\":1}", () -> {
                    runs.incrementAndGet();
                    entered.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "order-abc";
                })));
        Thread t2 = new Thread(() -> r2.set(idempotencyService.execute(
                "user-1", "POST /orders", concurrentKey, "{\"x\":1}", () -> {
                    runs.incrementAndGet();
                    return "order-duplicate";
                })));
        t1.start();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            t2.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (t2.getState() != Thread.State.WAITING
                    && t2.getState() != Thread.State.TIMED_WAITING
                    && t2.getState() != Thread.State.TERMINATED
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(t2.getState()).isIn(Thread.State.WAITING, Thread.State.TIMED_WAITING);
            release.countDown();
            t1.join(5000);
            t2.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(r1.get()).isEqualTo("order-abc");
        assertThat(r2.get()).isEqualTo("order-abc");
        assertThat(runs.get()).isEqualTo(1);

        IdempotencyService bounded = new IdempotencyService(java.time.Duration.ofMillis(50), 2);
        bounded.execute("u1", "OP", "idem_key_00000000000001", "p1", () -> "v1");
        bounded.execute("u1", "OP", "idem_key_00000000000002", "p2", () -> "v2");
        bounded.execute("u1", "OP", "idem_key_00000000000003", "p3", () -> "v3");
        assertThat(bounded.size()).isLessThanOrEqualTo(2);
    }

    @Test
    void apiResponsePageResponseAndSupabaseRoleExtractionWorkAsExpected() {
        PageResponse<String> page = PageResponse.of(List.of("a", "b"), 0, 20, 2L);
        assertThat(page.items()).containsExactly("a", "b");
        assertThat(page.pagination().totalItems()).isEqualTo(2L);

        ApiResponse<PageResponse<String>> resp = ApiResponse.ok("Loaded", page, "trace-1");
        assertThat(resp.success()).isTrue();
        assertThat(resp.code()).isEqualTo(200);
        assertThat(resp.traceId()).isEqualTo("trace-1");

        Jwt testJwt = new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "HS256"),
                Map.of("sub", "u1", "user_roles", List.of("CUSTOMER", "TECHNICIAN"), "permissions",
                        List.of("booking:create")));
        var authorities = SupabaseJwtConverterSupport.extractAuthorities(testJwt);
        assertThat(authorities).extracting("authority")
                .containsExactlyInAnyOrder("ROLE_CUSTOMER", "ROLE_TECHNICIAN", "booking:create");
    }

    @Test
    void correlationIdSupportAndServletExceptionHandlerMapStandardErrorsAndAlwaysIncludeNullData() {
        assertThat(CorrelationIdSupport.resolveOrGenerate("valid-corr_id.01")).isEqualTo("valid-corr_id.01");
        assertThat(CorrelationIdSupport.resolveOrGenerate("invalid id with spaces!")).hasSize(36);
        assertThat(CorrelationIdSupport.resolveOrGenerate(null)).hasSize(36);

        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(CorrelationIdSupport.REQUEST_ATTRIBUTE, "corr-servlet-test");

        var factory = new com.homecare.common.web.servlet.ServletApiResponseFactory(null);
        var handler = new com.homecare.common.web.servlet.BaseApiExceptionHandler(factory) {
        };

        var preconditionResp = handler.handlePreconditionException(
                new EtagSupport.PreconditionException(
                        HttpStatus.PRECONDITION_FAILED,
                        "RESOURCE_VERSION_MISMATCH",
                        "Version mismatch."),
                request);
        assertThat(preconditionResp.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
        assertThat(preconditionResp.getBody().errorCode()).isEqualTo("RESOURCE_VERSION_MISMATCH");
        assertThat(preconditionResp.getBody().traceId()).isEqualTo("corr-servlet-test");

        var mapper = new tools.jackson.databind.ObjectMapper();
        var json = mapper.valueToTree(preconditionResp.getBody());
        assertThat(json.has("data")).isTrue();
        assertThat(json.path("data").isNull()).isTrue();
        assertThat(json.has("errors")).isFalse();

        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new DummyForbiddenController())
                .setControllerAdvice(new DummyForbiddenAdvice(factory))
                .build();
        try {
            var res = mvc
                    .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/dummy-forbidden")
                            .requestAttr(CorrelationIdSupport.REQUEST_ATTRIBUTE, "corr-forbidden-test"))
                    .andReturn()
                    .getResponse();
            assertThat(res.getStatus()).isEqualTo(403);
            assertThat(res.getContentAsString()).contains("\"errorCode\":\"RESOURCE_FORBIDDEN\"");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @org.springframework.web.bind.annotation.RestControllerAdvice
    static class DummyForbiddenAdvice extends com.homecare.common.web.servlet.BaseApiExceptionHandler {
        DummyForbiddenAdvice(com.homecare.common.web.servlet.ServletApiResponseFactory responseFactory) {
            super(responseFactory);
        }
    }

    @org.springframework.web.bind.annotation.RestController
    static class DummyForbiddenController {
        @org.springframework.web.bind.annotation.GetMapping("/dummy-forbidden")
        public String forbidden() {
            throw new org.springframework.security.access.AccessDeniedException("Denied");
        }
    }
}
