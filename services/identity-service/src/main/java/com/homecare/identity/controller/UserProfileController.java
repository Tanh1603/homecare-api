package com.homecare.identity.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homecare.identity.dto.AuthDto.ChangePasswordRequest;
import com.homecare.identity.dto.AuthDto.UpdateProfileRequest;
import com.homecare.identity.dto.ApiMessageResponse;
import com.homecare.identity.dto.UserProfileResponse;
import com.homecare.identity.mapper.UserProfileMapper;
import com.homecare.identity.service.UserProfileService;
import com.homecare.identity.web.ApiMessageFactory;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileService userProfileService;
    private final UserProfileMapper userProfileMapper;
    private final ApiMessageFactory messageFactory;

    @GetMapping
    public ResponseEntity<UserProfileResponse> getMyProfile(@AuthenticationPrincipal Jwt jwt) {
        String keycloakUserId = jwt.getSubject();
        return ResponseEntity.ok(userProfileMapper.toResponse(userProfileService.getProfile(keycloakUserId)));
    }

    @PutMapping
    public ResponseEntity<UserProfileResponse> updateMyProfile(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateProfileRequest request) {
        String keycloakUserId = jwt.getSubject();
        return ResponseEntity.ok(
                userProfileMapper.toResponse(userProfileService.updateProfile(keycloakUserId, request)));
    }

    @PostMapping("/change-password")
    public ResponseEntity<ApiMessageResponse> changePassword(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ChangePasswordRequest request) {
        String keycloakUserId = jwt.getSubject();
        userProfileService.changePassword(keycloakUserId, request);
        return ResponseEntity.ok(messageFactory.create("success.password.updated"));
    }
}