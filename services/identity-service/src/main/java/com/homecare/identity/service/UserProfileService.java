package com.homecare.identity.service;

import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.identity.dto.AuthDto.ChangePasswordRequest;
import com.homecare.identity.dto.AuthDto.UpdateProfileRequest;
import com.homecare.identity.entity.UserProfile;
import com.homecare.identity.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserRepository userRepository;
    private final Keycloak keycloakAdminClient;

    @Value("${keycloak.realm}")
    private String realm;

    /**
     * US-HU-002: Lấy thông tin hồ sơ cá nhân hiện tại
     */
    public UserProfile getProfile(String keycloakUserId) {
        return userRepository.findByKeycloakUserId(keycloakUserId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy hồ sơ người dùng"));
    }

    /**
     * US-HU-002: Cập nhật thông tin hồ sơ cá nhân (Họ tên, SĐT, Địa chỉ)
     */
    @Transactional
    public UserProfile updateProfile(String keycloakUserId, UpdateProfileRequest request) {
        UserProfile profile = getProfile(keycloakUserId);

        profile.setFirstName(request.getFirstName());
        profile.setLastName(request.getLastName());
        profile.setPhone(request.getPhone());
        profile.setAddress(request.getAddress());

        // Đồng bộ họ tên sang Keycloak User Representation
        try {
            var userResource = keycloakAdminClient.realm(realm).users().get(keycloakUserId);
            var keycloakUser = userResource.toRepresentation();
            keycloakUser.setFirstName(request.getFirstName());
            keycloakUser.setLastName(request.getLastName());
            userResource.update(keycloakUser);
        } catch (Exception e) {
            // Log warning nếu sync Keycloak thất bại nhưng vẫn duy trì lưu DB local
        }

        return userRepository.save(profile);
    }

    /**
     * Đổi mật khẩu chủ động khi đã đăng nhập
     */
    public void changePassword(String keycloakUserId, ChangePasswordRequest request) {
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(request.getNewPassword());
        credential.setTemporary(false);

        keycloakAdminClient.realm(realm)
                .users()
                .get(keycloakUserId)
                .resetPassword(credential);
    }
}