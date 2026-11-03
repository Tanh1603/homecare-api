package com.homecare.identity.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.homecare.identity.config.KeycloakAdminClient;
import com.homecare.identity.domain.UserProfile;
import com.homecare.identity.dto.RegisterRequest;
import com.homecare.identity.dto.UpdateProfileRequest;
import com.homecare.identity.dto.UserResponse;
import com.homecare.identity.repository.UserProfileRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserProfileRepository repo;
    private final KeycloakAdminClient keycloak;

    /** Đăng ký: tạo user ở Keycloak rồi lưu profile vào DB. */
    @Transactional
    public UserResponse register(RegisterRequest r) {
        UUID id = keycloak.createUser(r.username(), r.email(), r.firstName(), r.lastName(), r.password());
        return UserResponse.from(upsert(id, r.username(), r.email(), r.firstName(), r.lastName()));
    }

    /** JIT provisioning: chưa có trong DB (webhook bị miss) thì tạo từ JWT. */
    @Transactional
    public UserResponse getOrCreateFromJwt(Jwt jwt) {
        UUID id = UUID.fromString(jwt.getSubject());
        UserProfile u = repo.findById(id).orElseGet(() -> upsert(id,
                jwt.getClaimAsString("preferred_username"),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("given_name"),
                jwt.getClaimAsString("family_name")));
        return UserResponse.from(u);
    }

    /** Dùng chung cho register và webhook (idempotent). */
    @Transactional
    public UserProfile upsert(UUID id, String username, String email, String first, String last) {
        UserProfile u = repo.findById(id).orElseGet(() -> UserProfile.builder().id(id).build());
        if (username != null)
            u.setUsername(username);
        if (email != null)
            u.setEmail(email);
        if (first != null)
            u.setFirstName(first);
        if (last != null)
            u.setLastName(last);
        return repo.save(u);
    }

    @Transactional
    public void touchLogin(UUID id) {
        repo.findById(id).ifPresent(u -> u.setLastLoginAt(Instant.now()));
    }

    @Transactional
    public void markDeleted(UUID id) {
        repo.findById(id).ifPresent(u -> u.setStatus(UserProfile.Status.DISABLED));
    }

    @Transactional
    public UserResponse updateMe(UUID id, UpdateProfileRequest r) {
        UserProfile u = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (r.firstName() != null)
            u.setFirstName(r.firstName());
        if (r.lastName() != null)
            u.setLastName(r.lastName());
        if (r.phone() != null)
            u.setPhone(r.phone());
        if (r.avatarUrl() != null)
            u.setAvatarUrl(r.avatarUrl());
        return UserResponse.from(u);
    }

    @Transactional(readOnly = true)
    public UserResponse getById(UUID id) {
        return repo.findById(id).map(UserResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }
}