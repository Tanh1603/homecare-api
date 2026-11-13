package com.homecare.provider.organization.security;

import java.util.Arrays;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.provider.exception.ProviderErrorCode;
import com.homecare.provider.organization.entity.OrganizationEntity;
import com.homecare.provider.organization.entity.OrganizationMemberEntity;
import com.homecare.provider.organization.entity.OrganizationStatus;
import com.homecare.provider.organization.repository.OrganizationMemberRepository;
import com.homecare.provider.organization.repository.OrganizationRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class OrgContextInterceptor implements HandlerInterceptor {

    public static final String ORG_ID_HEADER = "X-Organization-Id";

    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository memberRepository;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }
        RequireOrgRole annotation = hm.getMethodAnnotation(RequireOrgRole.class);
        if (annotation == null) {
            annotation = hm.getBeanType().getAnnotation(RequireOrgRole.class);
        }
        if (annotation == null) {
            return true;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            throw new DomainException(CommonErrorCode.UNAUTHENTICATED, "Authentication required.");
        }
        String userId = auth.getName();

        String rawOrgId = request.getHeader(ORG_ID_HEADER);
        if (rawOrgId == null || rawOrgId.isBlank()) {
            throw new DomainException(ProviderErrorCode.MISSING_ORGANIZATION_HEADER);
        }
        UUID orgId;
        try {
            orgId = UUID.fromString(rawOrgId.trim());
        } catch (IllegalArgumentException ex) {
            throw new DomainException(ProviderErrorCode.MISSING_ORGANIZATION_HEADER);
        }

        OrganizationEntity org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new DomainException(ProviderErrorCode.ORGANIZATION_NOT_FOUND));

        if (org.getStatus() != OrganizationStatus.APPROVED) {
            throw new DomainException(
                    ProviderErrorCode.ORGANIZATION_NOT_APPROVED,
                    "Organization is currently " + org.getStatus() + " and cannot perform business operations.");
        }

        OrganizationMemberEntity member = memberRepository.findByOrganizationIdAndUserId(orgId, userId)
                .orElseThrow(() -> new DomainException(ProviderErrorCode.ORG_MEMBERSHIP_REQUIRED));

        if (!"ACTIVE".equalsIgnoreCase(member.getStatus())) {
            throw new DomainException(ProviderErrorCode.ORG_MEMBERSHIP_REQUIRED, "Organization membership is inactive.");
        }

        boolean roleAllowed = annotation.value().length == 0
                || Arrays.asList(annotation.value()).contains(member.getRole());
        if (!roleAllowed) {
            throw new DomainException(ProviderErrorCode.ORG_ROLE_FORBIDDEN);
        }

        OrgContext.set(new OrgContext(orgId, userId, member.getRole()));
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex) {
        OrgContext.clear();
    }
}
