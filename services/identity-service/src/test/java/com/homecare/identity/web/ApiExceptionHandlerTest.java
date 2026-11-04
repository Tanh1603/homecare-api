package com.homecare.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import tools.jackson.databind.ObjectMapper;
import com.homecare.identity.dto.ApiResponse;
import com.homecare.identity.dto.AuthDto;
import com.homecare.identity.exception.ApiErrorCode;
import com.homecare.identity.exception.IdentityException;

class ApiExceptionHandlerTest {

    private ApiExceptionHandler handler;
    private MockHttpServletRequest request;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        StaticMessageSource messages = new StaticMessageSource();
        messages.addMessage("error.profile.not-found", Locale.ENGLISH, "The user profile was not found.");
        messages.addMessage("error.request.validation", Locale.ENGLISH, "The request contains invalid data.");
        handler = new ApiExceptionHandler(new ApiResponseFactory(messages));
        request = new MockHttpServletRequest();
        request.setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, "request-123");
        objectMapper = new ObjectMapper();
    }

    @Test
    void mapsIdentityExceptionToStandardErrorEnvelope() {
        var response = handler.handleIdentityException(
                new IdentityException(ApiErrorCode.PROFILE_NOT_FOUND),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isEqualTo(new ApiResponse<>(
                false,
                HttpStatus.NOT_FOUND.value(),
                "PROFILE_NOT_FOUND",
                "The user profile was not found.",
                null,
                null,
                response.getBody().timestamp(),
                "request-123"));

        var json = objectMapper.valueToTree(response.getBody());
        assertThat(json.path("data").isNull()).isTrue();
        assertThat(json.path("code").asInt()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(json.path("traceId").asText()).isEqualTo("request-123");
        assertThat(json.has("errors")).isFalse();
    }

    @Test
    void mapsFieldValidationToStandardErrorEnvelope() throws Exception {
        AuthDto.LoginRequest target = new AuthDto.LoginRequest();
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(target, "loginRequest");
        bindingResult.addError(new FieldError(
                "loginRequest",
                "email",
                "invalid",
                false,
                new String[] { "Email" },
                null,
                "Enter a valid email address."));
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(
                loginRequestParameter(),
                bindingResult);

        var response = handler.handleInvalidArguments(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().code()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(response.getBody().errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().data()).isNull();
        assertThat(response.getBody().errors()).containsExactly(
                new ApiResponse.FieldError("email", "Enter a valid email address."));
        assertThat(response.getBody().traceId()).isEqualTo("request-123");

        var json = objectMapper.valueToTree(response.getBody());
        assertThat(json.path("errors").get(0).has("code")).isFalse();
        assertThat(json.has("error")).isFalse();
    }

    private MethodParameter loginRequestParameter() throws NoSuchMethodException {
        Method method = ValidationController.class.getDeclaredMethod("login", AuthDto.LoginRequest.class);
        return new MethodParameter(method, 0);
    }

    private static class ValidationController {
        void login(AuthDto.LoginRequest request) {
        }
    }
}
