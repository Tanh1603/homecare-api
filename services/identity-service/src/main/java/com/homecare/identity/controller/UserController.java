package com.homecare.identity.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import com.homecare.identity.dto.RegisterRequest;
import com.homecare.identity.dto.UpdateProfileRequest;
import com.homecare.identity.dto.UserResponse;
import com.homecare.identity.service.UserService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/services/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService service;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest req) {
        return service.register(req);
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return service.getOrCreateFromJwt(jwt);
    }

    @PutMapping("/me")
    public UserResponse updateMe(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest req) {
        return service.updateMe(UUID.fromString(jwt.getSubject()), req);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UserResponse byId(@PathVariable UUID id) {
        return service.getById(id);
    }
}