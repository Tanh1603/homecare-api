package com.homecare.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.homecare.common.idempotency.IdempotencyService;
import com.homecare.common.security.KeycloakJwtConverterSupport;

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

        assertThatThrownBy(() -> idempotencyService.execute("user-1", "POST /bookings", "short-key", "{\"a\":1}", () -> "ok"))
                .isInstanceOf(IdempotencyService.IdempotencyException.class)
                .satisfies(ex -> {
                    var ie = (IdempotencyService.IdempotencyException) ex;
                    assertThat(ie.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ie.errorCode()).isEqualTo("VALIDATION_FAILED");
                });

        String validKey = "idem_key_1234567890_abcd";
        String first = idempotencyService.execute("user-1", "POST /bookings", validKey, "{\"serviceId\":\"srv1\"}", () -> "booking-001");
        String replay = idempotencyService.execute("user-1", "POST /bookings", validKey, "{\"serviceId\":\"srv1\"}", () -> "booking-002");
        assertThat(first).isEqualTo("booking-001");
        assertThat(replay).isEqualTo("booking-001");

        assertThatThrownBy(() -> idempotencyService.execute("user-1", "POST /bookings", validKey, "{\"serviceId\":\"srv2\"}", () -> "booking-003"))
                .isInstanceOf(IdempotencyService.IdempotencyException.class)
                .satisfies(ex -> {
                    var ie = (IdempotencyService.IdempotencyException) ex;
                    assertThat(ie.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ie.errorCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
                });
    }

    @Test
    void apiResponsePageResponseAndKeycloakRoleExtractionWorkAsExpected() {
        PageResponse<String> page = PageResponse.of(List.of("a", "b"), 0, 20, 2L);
        assertThat(page.items()).containsExactly("a", "b");
        assertThat(page.pagination().totalItems()).isEqualTo(2L);

        ApiResponse<PageResponse<String>> resp = ApiResponse.ok("Loaded", page, "trace-1");
        assertThat(resp.success()).isTrue();
        assertThat(resp.code()).isEqualTo(200);
        assertThat(resp.traceId()).isEqualTo("trace-1");

        var authorities = KeycloakJwtConverterSupport.extractAuthoritiesFromRealmAccess(
                Map.of("roles", List.of("CUSTOMER", "TECHNICIAN")));
        assertThat(authorities).extracting("authority")
                .containsExactly("ROLE_CUSTOMER", "ROLE_TECHNICIAN");
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
    }
}
