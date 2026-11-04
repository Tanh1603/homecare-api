package com.homecare.identity.service;

import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.identity.dto.AuthDto.ChangePasswordRequest;
import com.homecare.identity.dto.AuthDto.UpdateProfileRequest;
import com.homecare.identity.entity.UserProfile;
import com.homecare.identity.exception.ApiErrorCode;
import com.homecare.identity.exception.IdentityException;
import com.homecare.identity.repository.UserRepository;

import jakarta.ws.rs.WebApplicationException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);
    private final UserRepository userRepository;
    private final Keycloak keycloakAdminClient;
    private final AuthService authService;

    @Value("${keycloak.realm}")
    private String realm;

    /** Returns the current user's profile. */
    public UserProfile getProfile(String keycloakUserId) {
        return userRepository.findByKeycloakUserId(keycloakUserId)
                .orElseThrow(() -> new IdentityException(ApiErrorCode.PROFILE_NOT_FOUND));
    }

    /** Updates the current user's profile. */
    @Transactional
    public UserProfile updateProfile(String keycloakUserId, UpdateProfileRequest request) {
        UserProfile profile = getProfile(keycloakUserId);

        profile.setFirstName(request.getFirstName());
        profile.setLastName(request.getLastName());
        profile.setPhone(request.getPhone());
        profile.setAddress(request.getAddress());

        // Synchronize the name with the Keycloak user representation.
        try {
            var userResource = keycloakAdminClient.realm(realm).users().get(keycloakUserId);
            var keycloakUser = userResource.toRepresentation();
            keycloakUser.setFirstName(request.getFirstName());
            keycloakUser.setLastName(request.getLastName());
            userResource.update(keycloakUser);
        } catch (RuntimeException exception) {
            log.warn("Keycloak profile sync failed; local profile update will be retained exceptionType={}",
                    exception.getClass().getSimpleName());
        }

        return userRepository.save(profile);
    }

    /** Changes the password for the authenticated user. */
    public void changePassword(String keycloakUserId, ChangePasswordRequest request) {
        UserProfile profile = getProfile(keycloakUserId);
        authService.verifyCurrentPassword(profile.getUsername(), request.getCurrentPassword());

        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(request.getNewPassword());
        credential.setTemporary(false);

        try {
            keycloakAdminClient.realm(realm)
                    .users()
                    .get(keycloakUserId)
                    .resetPassword(credential);
        } catch (WebApplicationException exception) {
            log.warn("Keycloak rejected password update status={}",
                    exception.getResponse().getStatus());
            throw new IdentityException(ApiErrorCode.OPERATION_FAILED, exception);
        } catch (RuntimeException exception) {
            log.error("Keycloak password update unavailable exceptionType={}",
                    exception.getClass().getSimpleName());
            throw new IdentityException(ApiErrorCode.AUTH_PROVIDER_UNAVAILABLE, exception);
        }
    }
}