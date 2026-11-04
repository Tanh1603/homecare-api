package com.homecare.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.net.URI;

import jakarta.ws.rs.core.Response;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.homecare.identity.dto.AuthDto.LoginRequest;
import com.homecare.identity.dto.AuthDto.RegisterRequest;
import com.homecare.identity.entity.UserProfile;
import com.homecare.identity.exception.ApiErrorCode;
import com.homecare.identity.exception.IdentityException;
import com.homecare.identity.repository.UserRepository;

class AuthServiceTest {

    private Keycloak keycloak;
    private UsersResource usersResource;
    private UserRepository userRepository;
    private RestTemplate restTemplate;
    private AuthService authService;
    private RealmResource realmResource;
    private RolesResource rolesResource;
    private RoleResource roleResource;
    private UserResource userResource;
    private RoleMappingResource roleMappingResource;
    private RoleScopeResource roleScopeResource;

    @BeforeEach
    void setUp() {
        keycloak = mock(Keycloak.class);
        usersResource = mock(UsersResource.class);
        restTemplate = mock(RestTemplate.class);
        userRepository = mock(UserRepository.class);
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
                restTemplate);
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

    @Test
    void verifiesCurrentPasswordUsingTheUsersCredentials() {
        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of(
                        "access_token", "discarded-test-token",
                        "expires_in", 300,
                        "token_type", "Bearer")));

        authService.verifyCurrentPassword("person", "current-test-password");

        org.mockito.ArgumentCaptor<HttpEntity> request = org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                anyString(),
                eq(HttpMethod.POST),
                request.capture(),
                eq(Map.class));
        assertThat(request.getValue().getBody()).isInstanceOf(MultiValueMap.class);
        Map<?, ?> form = (Map<?, ?>) request.getValue().getBody();
        assertThat(form.get("username")).isEqualTo(List.of("person"));
        assertThat(form.get("password")).isEqualTo(List.of("current-test-password"));
    }

    @Test
    void rejectsIncorrectCurrentPassword() {
        rejectLoginWithInvalidGrant();

        assertThatThrownBy(() -> authService.verifyCurrentPassword("person", "wrong-test-password"))
                .isInstanceOf(IdentityException.class)
                .extracting(exception -> ((IdentityException) exception).getErrorCode())
                .isEqualTo(ApiErrorCode.INVALID_CREDENTIALS);
    }

    @Test
    void registersUserAndAssignsDefaultRoleBeforeSavingProfile() {
        mockUserCreation();
        when(rolesResource.get("CUSTOMER")).thenReturn(roleResource);
        when(roleResource.toRepresentation()).thenReturn(new RoleRepresentation());
        when(usersResource.get("keycloak-user-id")).thenReturn(userResource);
        when(userResource.roles()).thenReturn(roleMappingResource);
        when(roleMappingResource.realmLevel()).thenReturn(roleScopeResource);
        when(userRepository.save(any(UserProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        UserProfile profile = authService.register(registerRequest());

        assertThat(profile.getKeycloakUserId()).isEqualTo("keycloak-user-id");
        verify(roleScopeResource).add(anyList());
        verify(userRepository).save(profile);
    }

    @Test
    void cleansUpCreatedUserAndFailsRegistrationWhenDefaultRoleAssignmentFails() {
        mockUserCreation();
        when(rolesResource.get("CUSTOMER")).thenReturn(roleResource);
        when(roleResource.toRepresentation()).thenReturn(new RoleRepresentation());
        when(usersResource.get("keycloak-user-id")).thenReturn(userResource);
        when(userResource.roles()).thenReturn(roleMappingResource);
        when(roleMappingResource.realmLevel()).thenReturn(roleScopeResource);
        doThrow(new IllegalStateException("simulated role assignment failure"))
                .when(roleScopeResource).add(anyList());

        assertThatThrownBy(() -> authService.register(registerRequest()))
                .isInstanceOf(IdentityException.class)
                .extracting(exception -> ((IdentityException) exception).getErrorCode())
                .isEqualTo(ApiErrorCode.AUTH_PROVIDER_UNAVAILABLE);

        verify(userResource).remove();
        verify(userRepository, never()).save(any(UserProfile.class));
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
                eq(java.util.Map.class)))
                .thenThrow(exception);
    }
}
