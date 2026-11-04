package com.homecare.identity.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homecare.identity.dto.ApiResponse;
import com.homecare.identity.dto.AuthDto.ChangePasswordRequest;
import com.homecare.identity.dto.AuthDto.UpdateProfileRequest;
import com.homecare.identity.dto.UserProfileResponse;
import com.homecare.identity.mapper.UserProfileMapper;
import com.homecare.identity.service.UserProfileService;
import com.homecare.identity.web.ApiResponseFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileService userProfileService;
    private final UserProfileMapper userProfileMapper;
    private final ApiResponseFactory responseFactory;

    @GetMapping
    public ResponseEntity<ApiResponse<UserProfileResponse>> getMyProfile(
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        String keycloakUserId = jwt.getSubject();
        return ResponseEntity.ok(responseFactory.success(
                HttpStatus.OK,
                "success.profile.retrieved",
                userProfileMapper.toResponse(userProfileService.getProfile(keycloakUserId)),
                httpRequest));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<UserProfileResponse>> updateMyProfile(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateProfileRequest request,
            HttpServletRequest httpRequest) {
        String keycloakUserId = jwt.getSubject();
        return ResponseEntity.ok(responseFactory.success(
                HttpStatus.OK,
                "success.profile.updated",
                userProfileMapper.toResponse(userProfileService.updateProfile(keycloakUserId, request)),
                httpRequest));
    }

    @PostMapping("/change-password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest httpRequest) {
        String keycloakUserId = jwt.getSubject();
        userProfileService.changePassword(keycloakUserId, request);
        return ResponseEntity.ok(responseFactory.success(
                HttpStatus.OK,
                "success.password.updated",
                null,
                httpRequest));
    }
}