package com.homecare.identity.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RoleMappingResource;
import org.keycloak.admin.client.resource.RoleResource;
import org.keycloak.admin.client.resource.RoleScopeResource;
import org.keycloak.admin.client.resource.RolesResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.homecare.identity.dto.AuthDto.LoginRequest;
import com.homecare.identity.dto.AuthDto.LogoutRequest;
import com.homecare.identity.dto.AuthDto.RegisterRequest;
import com.homecare.identity.entity.UserProfile;
import com.homecare.identity.exception.ApiErrorCode;
import com.homecare.identity.exception.IdentityException;
import com.homecare.identity.repository.UserRepository;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import tools.jackson.databind.ObjectMapper;

class AuthServiceTest {

    private Keycloak keycloak;
    private UsersResource usersResource;
    private UserRepository userRepository;
    private RestTemplate restTemplate;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ObjectMapper objectMapper;
    private AuthService authService;
    private RealmResource realmResource;
    private RolesResource rolesResource;
    private RoleResource roleResource;
    private UserResource userResource;
    private RoleMappingResource roleMappingResource;
    private RoleScopeResource roleScopeResource;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        keycloak = mock(Keycloak.class);
        usersResource = mock(UsersResource.class);
        restTemplate = mock(RestTemplate.class);
        userRepository = mock(UserRepository.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        objectMapper = new ObjectMapper();

        realmResource = mock(RealmResource.class);
        rolesResource = mock(RolesResource.class);
        roleResource = mock(RoleResource.class);
        userResource = mock(UserResource.class);
        roleMappingResource = mock(RoleMappingResource.class);
        roleScopeResource = mock(RoleScopeResource.class);
        when(keycloak.realm("homecare")).thenReturn(realmResource);
        when(realmResource.users()).thenReturn(usersResource);
        when(realmResource.roles()).thenReturn(rolesResource);

        authService = new AuthService(
                keycloak,
                userRepository,
                restTemplate,
                redisTemplate,
                objectMapper);
        ReflectionTestUtils.setField(authService, "serverUrl", "https://identity.example.test");
        ReflectionTestUtils.setField(authService, "realm", "homecare");
        ReflectionTestUtils.setField(authService, "clientId", "homecare-client");
        ReflectionTestUtils.setField(authService, "clientSecret", "test-secret");
        ReflectionTestUtils.setField(authService, "defaultRole", "CUSTOMER");
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
    void returnsInvalidCredentialsWhenAccountIsFullySetUp() {
        UserRepresentation user = new UserRepresentation();
        user.setEmailVerified(true);
        when(usersResource.searchByEmail("person@example.test", true)).thenReturn(List.of(user));
        rejectLoginWithInvalidGrant();

        assertThatThrownBy(() -> authService.login(loginRequest()))
                .isInstanceOf(IdentityException.class)
                .extracting(exception -> ((IdentityException) exception).getErrorCode())
                .isEqualTo(ApiErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void rollsBackUserWhenAssigningDefaultRoleFails() {
        mockUserCreation();
        when(rolesResource.get("CUSTOMER")).thenReturn(roleResource);
        when(usersResource.get("keycloak-user-id")).thenReturn(userResource);
        when(userResource.roles()).thenReturn(roleMappingResource);
        when(roleMappingResource.realmLevel()).thenReturn(roleScopeResource);
        doThrow(new RuntimeException("Role mapping failed")).when(roleScopeResource).add(anyList());

        assertThatThrownBy(() -> authService.register(registerRequest()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Role mapping failed");

        verify(userResource).remove();
        verify(userRepository, never()).save(any(UserProfile.class));
    }

    @Test
    void removesCreatedUserWhenLocalRepositorySaveFails() {
        mockUserCreation();
        RoleRepresentation role = new RoleRepresentation();
        role.setName("CUSTOMER");
        when(rolesResource.get("CUSTOMER")).thenReturn(roleResource);
        when(roleResource.toRepresentation()).thenReturn(role);
        when(usersResource.get("keycloak-user-id")).thenReturn(userResource);
        when(userResource.roles()).thenReturn(roleMappingResource);
        when(roleMappingResource.realmLevel()).thenReturn(roleScopeResource);
        when(userRepository.save(any(UserProfile.class))).thenThrow(new RuntimeException("Database error"));

        assertThatThrownBy(() -> authService.register(registerRequest()))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Database error");

        verify(userResource).remove();
    }

    @Test
    void rejectsDuplicateUsernameBeforeCallingKeycloak() {
        when(userRepository.existsByUsername("person")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(registerRequest()))
                .isInstanceOf(IdentityException.class)
                .extracting(exception -> ((IdentityException) exception).getErrorCode())
                .isEqualTo(ApiErrorCode.USERNAME_ALREADY_EXISTS);

        org.mockito.Mockito.verifyNoInteractions(keycloak);
    }

    @Test
    void logoutDeletesSessionAndBlacklistsSidInRedis() {
        LogoutRequest request = new LogoutRequest();
        long exp = Instant.now().plusSeconds(300).getEpochSecond();
        String payloadJson = "{\"sid\":\"test-session-123\",\"exp\":" + exp + "}";
        String payloadB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String dummyRefreshJwt = "header." + payloadB64 + ".signature";

        request.setRefreshToken(dummyRefreshJwt);

        authService.logout(request);

        verify(realmResource).deleteSession("test-session-123", false);
        verify(valueOperations).set(
                eq(AuthService.BLACKLIST_SESSION_KEY_PREFIX + "test-session-123"),
                eq("revoked"),
                any(Duration.class));
    }

    @Test
    void logoutThrowsInvalidRefreshTokenWhenTokenIsExpired() {
        LogoutRequest request = new LogoutRequest();
        long exp = Instant.now().minusSeconds(10).getEpochSecond();
        String payloadJson = "{\"sid\":\"expired-session-123\",\"exp\":" + exp + "}";
        String payloadB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String dummyRefreshJwt = "header." + payloadB64 + ".signature";

        request.setRefreshToken(dummyRefreshJwt);

        assertThatThrownBy(() -> authService.logout(request))
                .isInstanceOf(IdentityException.class)
                .extracting(exception -> ((IdentityException) exception).getErrorCode())
                .isEqualTo(ApiErrorCode.INVALID_REFRESH_TOKEN);

        verify(realmResource, never()).deleteSession(anyString(), anyBoolean());
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void logoutThrowsInvalidRefreshTokenWhenKeycloakSessionNotFound() {
        LogoutRequest request = new LogoutRequest();
        long exp = Instant.now().plusSeconds(300).getEpochSecond();
        String payloadJson = "{\"sid\":\"missing-session-123\",\"exp\":" + exp + "}";
        String payloadB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String dummyRefreshJwt = "header." + payloadB64 + ".signature";

        request.setRefreshToken(dummyRefreshJwt);

        Response notFoundResponse = mock(Response.class);
        when(notFoundResponse.getStatus()).thenReturn(404);
        doThrow(new WebApplicationException(notFoundResponse))
                .when(realmResource).deleteSession("missing-session-123", false);

        assertThatThrownBy(() -> authService.logout(request))
                .isInstanceOf(IdentityException.class)
                .extracting(exception -> ((IdentityException) exception).getErrorCode())
                .isEqualTo(ApiErrorCode.INVALID_REFRESH_TOKEN);

        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    private LoginRequest loginRequest() {
        LoginRequest request = new LoginRequest();
        request.setEmail("person@example.test");
        request.setPassword("not-a-real-password");
        return request;
    }

    private RegisterRequest registerRequest() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("person");
        request.setEmail("person@example.test");
        request.setPassword("test-password");
        request.setFirstName("Test");
        request.setLastName("Person");
        return request;
    }

    private void mockUserCreation() {
        Response response = mock(Response.class);
        when(response.getStatus()).thenReturn(201);
        when(response.getLocation()).thenReturn(URI.create("https://identity.example.test/users/keycloak-user-id"));
        when(usersResource.create(any(UserRepresentation.class))).thenReturn(response);
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
                org.mockito.ArgumentMatchers
                        .<ParameterizedTypeReference<java.util.Map<String, Object>>>any()))
                .thenThrow(exception);
    }
}
