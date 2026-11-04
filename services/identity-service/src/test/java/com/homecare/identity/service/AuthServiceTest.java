package com.homecare.identity.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.homecare.identity.dto.AuthDto.LoginRequest;
import com.homecare.identity.exception.ApiErrorCode;
import com.homecare.identity.exception.IdentityException;
import com.homecare.identity.repository.UserRepository;

class AuthServiceTest {

    private Keycloak keycloak;
    private UsersResource usersResource;
    private RestTemplate restTemplate;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        keycloak = mock(Keycloak.class);
        usersResource = mock(UsersResource.class);
        restTemplate = mock(RestTemplate.class);

        RealmResource realmResource = mock(RealmResource.class);
        when(keycloak.realm("homecare")).thenReturn(realmResource);
        when(realmResource.users()).thenReturn(usersResource);

        authService = new AuthService(
                keycloak,
                mock(UserRepository.class),
                restTemplate,
                new StaticMessageSource());
        ReflectionTestUtils.setField(authService, "serverUrl", "https://identity.example.test");
        ReflectionTestUtils.setField(authService, "realm", "homecare");
        ReflectionTestUtils.setField(authService, "clientId", "homecare-client");
        ReflectionTestUtils.setField(authService, "clientSecret", "test-secret");
    }

    @Test
    void returnsVerificationErrorForUnverifiedAccount() {
        UserRepresentation user = new UserRepresentation();
        user.setEmailVerified(false);
        when(usersResource.searchByEmail("person@example.test", true)).thenReturn(List.of(user));
        rejectLoginWithInvalidGrant();

        assertThatThrownBy(() -> authService.login(loginRequest()))
                .isInstanceOf(IdentityException.class)
                .extracting(exception -> ((IdentityException) exception).getErrorCode())
                .isEqualTo(ApiErrorCode.EMAIL_VERIFICATION_REQUIRED);
    }

    @Test
    void returnsInvalidCredentialsForVerifiedAccount() {
        UserRepresentation user = new UserRepresentation();
        user.setEmailVerified(true);
        when(usersResource.searchByEmail("person@example.test", true)).thenReturn(List.of(user));
        rejectLoginWithInvalidGrant();

        assertThatThrownBy(() -> authService.login(loginRequest()))
                .isInstanceOf(IdentityException.class)
                .extracting(exception -> ((IdentityException) exception).getErrorCode())
                .isEqualTo(ApiErrorCode.INVALID_CREDENTIALS);
    }

    private LoginRequest loginRequest() {
        LoginRequest request = new LoginRequest();
        request.setEmail("person@example.test");
        request.setPassword("not-a-real-password");
        return request;
    }

    private void rejectLoginWithInvalidGrant() {
        HttpClientErrorException exception = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                new HttpHeaders(),
                "{\"error\":\"invalid_grant\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(java.util.Map.class)))
                .thenThrow(exception);
    }
}
