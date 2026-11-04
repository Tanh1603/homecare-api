package com.homecare.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.mock.web.MockHttpServletRequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homecare.identity.dto.ApiErrorResponse;
import com.homecare.identity.dto.AuthDto;
import com.homecare.identity.exception.ApiErrorCode;
import com.homecare.identity.exception.IdentityException;

class ApiExceptionHandlerTest {

    private ApiExceptionHandler handler;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        StaticMessageSource messages = new StaticMessageSource();
        messages.addMessage("error.profile.not-found", Locale.ENGLISH, "The user profile was not found.");
        messages.addMessage("error.request.validation", Locale.ENGLISH, "One or more request fields are invalid.");
        handler = new ApiExceptionHandler(new ApiErrorResponseFactory(messages));
        request = new MockHttpServletRequest();
        request.setAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE, "request-123");
    }

    @Test
    void mapsIdentityExceptionToStandardErrorEnvelope() {
        var response = handler.handleIdentityException(
                new IdentityException(ApiErrorCode.PROFILE_NOT_FOUND),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isEqualTo(new ApiErrorResponse(
                new ApiErrorResponse.ErrorDetail(
                        "PROFILE_NOT_FOUND",
                        "The user profile was not found.",
                        null,
                        "request-123")));
        assertThat(new ObjectMapper().valueToTree(response.getBody())
            .path("error").path("code").asText()).isEqualTo("PROFILE_NOT_FOUND");
        assertThat(new ObjectMapper().valueToTree(response.getBody())
            .path("error").has("fieldErrors")).isFalse();
    }

    @Test
    void mapsFieldValidationToStableCodeAndEnglishMessage() throws Exception {
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
        assertThat(response.getBody().error().code()).isEqualTo("REQUEST_VALIDATION_FAILED");
        assertThat(response.getBody().error().fieldErrors()).containsExactly(
                new ApiErrorResponse.FieldErrorDetail(
                        "email",
                        "INVALID_FORMAT",
                        "Enter a valid email address."));
        assertThat(response.getBody().error().traceId()).isEqualTo("request-123");
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
