package com.homecare.identity.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.springframework.test.util.ReflectionTestUtils;

import com.homecare.identity.dto.AuthDto.ChangePasswordRequest;
import com.homecare.identity.entity.UserProfile;
import com.homecare.identity.exception.ApiErrorCode;
import com.homecare.identity.exception.IdentityException;
import com.homecare.identity.repository.UserRepository;

class UserProfileServiceTest {

    private UserRepository userRepository;
    private Keycloak keycloak;
    private AuthService authService;
    private UserResource userResource;
    private UserProfileService userProfileService;
    private UserProfile profile;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        keycloak = mock(Keycloak.class);
        authService = mock(AuthService.class);
        userResource = mock(UserResource.class);

        RealmResource realmResource = mock(RealmResource.class);
        UsersResource usersResource = mock(UsersResource.class);
        when(keycloak.realm("homecare")).thenReturn(realmResource);
        when(realmResource.users()).thenReturn(usersResource);
        when(usersResource.get("keycloak-user-id")).thenReturn(userResource);

        profile = UserProfile.builder()
                .keycloakUserId("keycloak-user-id")
                .username("person")
                .email("person@example.test")
                .firstName("Test")
                .lastName("Person")
                .build();
        userProfileService = new UserProfileService(userRepository, keycloak, authService);
        ReflectionTestUtils.setField(userProfileService, "realm", "homecare");
    }

    @Test
    void verifiesCurrentPasswordBeforeResettingCredential() {
        when(userRepository.findByKeycloakUserId("keycloak-user-id")).thenReturn(Optional.of(profile));

        userProfileService.changePassword("keycloak-user-id", changePasswordRequest());

        var order = inOrder(authService, userResource);
        order.verify(authService).verifyCurrentPassword("person", "current-test-password");
        order.verify(userResource).resetPassword(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void doesNotResetPasswordWhenCurrentPasswordIsRejected() {
        when(userRepository.findByKeycloakUserId("keycloak-user-id")).thenReturn(Optional.of(profile));
        org.mockito.Mockito.doThrow(new IdentityException(ApiErrorCode.INVALID_CREDENTIALS))
                .when(authService).verifyCurrentPassword("person", "current-test-password");

        assertThatThrownBy(() -> userProfileService.changePassword(
                "keycloak-user-id",
                changePasswordRequest()))
                .isInstanceOf(IdentityException.class)
                .extracting(exception -> ((IdentityException) exception).getErrorCode())
                .isEqualTo(ApiErrorCode.INVALID_CREDENTIALS);

        verify(userResource, never()).resetPassword(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void doesNotVerifyPasswordOrCallKeycloakWhenProfileDoesNotExist() {
        when(userRepository.findByKeycloakUserId("keycloak-user-id")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userProfileService.changePassword(
                "keycloak-user-id",
                changePasswordRequest()))
                .isInstanceOf(IdentityException.class)
                .extracting(exception -> ((IdentityException) exception).getErrorCode())
                .isEqualTo(ApiErrorCode.PROFILE_NOT_FOUND);

        verify(authService, never()).verifyCurrentPassword(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(keycloak, never()).realm("homecare");
    }

    private ChangePasswordRequest changePasswordRequest() {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setCurrentPassword("current-test-password");
        request.setNewPassword("new-test-password");
        return request;
    }
}
