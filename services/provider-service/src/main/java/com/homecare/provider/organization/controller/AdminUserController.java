package com.homecare.provider.organization.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.homecare.common.security.RbacConstants;
import com.homecare.common.web.ApiMessages;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.provider.organization.dto.OrganizationDtos.AdminUserResponse;
import com.homecare.provider.organization.service.SupabaseRoleClient;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize(RbacConstants.EXPR_ORG_REVIEW)
@Validated
@RequiredArgsConstructor
public class AdminUserController {

    private final SupabaseRoleClient supabaseRoleClient;
    private final ServletApiResponseFactory responseFactory;

    @GetMapping
    public ResponseEntity<ApiResponse<List<AdminUserResponse>>> listUsers(HttpServletRequest httpRequest) {
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                supabaseRoleClient.listUsers(),
                httpRequest));
    }
}
