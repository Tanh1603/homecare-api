package com.homecare.provider.organization.controller;

import com.homecare.common.security.RbacConstants;
import com.homecare.common.web.ApiMessages;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.provider.entity.CategoryQualificationEntity;
import com.homecare.provider.entity.CategoryQualificationStatus;
import com.homecare.provider.entity.ServiceCategory;
import com.homecare.provider.service.ProviderDomainService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/providers")
@PreAuthorize(RbacConstants.EXPR_ORG_REVIEW)
@Validated
@RequiredArgsConstructor
public class AdminProviderQualificationController {

    private final ProviderDomainService providerService;
    private final ServletApiResponseFactory responseFactory;

    public record VerifyQualificationRequest(
            @NotNull CategoryQualificationStatus decision,
            String reason) {}

    public record CategoryQualificationResponse(
            UUID id,
            UUID providerId,
            ServiceCategory category,
            CategoryQualificationStatus status,
            String evidenceRefs,
            String reason,
            String verifiedBy,
            Instant verifiedAt) {
        public static CategoryQualificationResponse from(CategoryQualificationEntity e) {
            return new CategoryQualificationResponse(
                    e.getId(), e.getProviderId(), e.getCategory(), e.getStatus(),
                    e.getEvidenceRefs(), e.getReason(), e.getVerifiedBy(), e.getVerifiedAt());
        }
    }

    @GetMapping("/{providerId}/qualifications")
    public ResponseEntity<ApiResponse<List<CategoryQualificationResponse>>> listQualifications(
            @PathVariable UUID providerId,
            HttpServletRequest httpRequest) {
        List<CategoryQualificationResponse> list = providerService.listCategoryQualifications(providerId).stream()
                .map(CategoryQualificationResponse::from)
                .toList();
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.RETRIEVED, list, httpRequest));
    }

    @PostMapping("/{providerId}/qualifications/{category}/verify")
    public ResponseEntity<ApiResponse<CategoryQualificationResponse>> verifyQualification(
            @PathVariable UUID providerId,
            @PathVariable ServiceCategory category,
            @Valid @RequestBody VerifyQualificationRequest request,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        CategoryQualificationEntity result = providerService.verifyCategoryQualification(
                providerId, category, request.decision(), request.reason(), jwt.getSubject());
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.UPDATED,
                CategoryQualificationResponse.from(result),
                httpRequest));
    }
}
