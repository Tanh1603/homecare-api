package com.homecare.provider.organization.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.homecare.common.security.RbacConstants;
import com.homecare.common.security.SupabaseJwtConverterSupport;
import com.homecare.common.web.servlet.ServletCorrelationIdFilter;

@SpringBootTest
@ActiveProfiles("test")
class AdminOrganizationControllerSecurityTest {

    @Autowired
    private AdminOrganizationController adminOrganizationController;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void allowsOrganizationReviewPermissionWithoutAdminRole() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/organizations");
        request.setAttribute(ServletCorrelationIdFilter.REQUEST_ATTRIBUTE, "test-corr-1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        setJwtAuth(Map.of(
                "sub", "reviewer-1",
                "permissions", List.of(RbacConstants.PERM_ORGANIZATION_REVIEW)));

        var response = adminOrganizationController.listOrganizations(null, request);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void allowsAdminRole() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/organizations");
        request.setAttribute(ServletCorrelationIdFilter.REQUEST_ATTRIBUTE, "test-corr-2");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        setJwtAuth(Map.of(
                "sub", "admin-1",
                "user_roles", List.of(RbacConstants.ROLE_ADMIN)));

        var response = adminOrganizationController.listOrganizations(null, request);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void deniesCustomerRole() {
        setJwtAuth(Map.of(
                "sub", "customer-1",
                "user_roles", List.of(RbacConstants.ROLE_CUSTOMER)));

        assertThatThrownBy(() -> adminOrganizationController.listOrganizations(null, new MockHttpServletRequest()))
                .isInstanceOf(AccessDeniedException.class);
    }

    private void setJwtAuth(Map<String, Object> claims) {
        Jwt jwt = new Jwt("test-token", Instant.now(), Instant.now().plusSeconds(3600), Map.of("alg", "HS256"), claims);
        var auth = new JwtAuthenticationToken(jwt, SupabaseJwtConverterSupport.extractAuthorities(jwt));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
