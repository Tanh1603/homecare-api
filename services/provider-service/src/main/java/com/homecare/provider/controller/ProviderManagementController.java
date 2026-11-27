package com.homecare.provider.controller;

import com.homecare.common.security.ActorContext;
import com.homecare.common.security.ActorContextResolver;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.web.ApiMessages;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.provider.dto.ProviderDtos.CoverageAreaItem;
import com.homecare.provider.dto.ProviderDtos.OfferingInput;
import com.homecare.provider.dto.ProviderDtos.ProviderInput;
import com.homecare.provider.dto.ProviderDtos.ProviderResponse;
import com.homecare.provider.dto.ProviderDtos.ServiceOfferingResponse;
import com.homecare.provider.dto.ProviderDtos.TechnicianInput;
import com.homecare.provider.dto.ProviderDtos.TechnicianResponse;
import com.homecare.provider.dto.ProviderDtos.UpdateTechnicianOnlineStatusRequest;
import com.homecare.provider.dto.ProviderDtos.UpsertServiceCoverageRequest;
import com.homecare.provider.entity.ServiceCategory;
import com.homecare.provider.organization.controller.AdminProviderQualificationController.CategoryQualificationResponse;
import com.homecare.provider.security.ProviderAccessPolicy;
import com.homecare.provider.service.ProviderDomainService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ProviderManagementController {
    private final ProviderDomainService service;
    private final ProviderAccessPolicy accessPolicy;
    private final ServletApiResponseFactory responses;

    private ActorContext resolveActor(Jwt jwt, String rawOrgId) {
        return ActorContextResolver.resolve(jwt, ActorContextResolver.parseOrganizationId(rawOrgId), accessPolicy);
    }

    @PatchMapping("/providers/{id}")
    public ResponseEntity<ApiResponse<ProviderResponse>> updateProvider(
            @PathVariable UUID id,
            @Valid @RequestBody ProviderInput input,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        return ResponseEntity.ok(responses.ok(
                ApiMessages.UPDATED,
                service.updateProvider(id, input, resolveActor(jwt, rawOrgId)),
                request));
    }

    @PostMapping("/services")
    public ResponseEntity<ApiResponse<ServiceOfferingResponse>> createOffering(
            @Valid @RequestBody OfferingInput input,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(responses.created(
                ApiMessages.CREATED,
                service.saveOffering(null, input, resolveActor(jwt, rawOrgId)),
                request));
    }

    @PatchMapping("/services/{id}")
    public ResponseEntity<ApiResponse<ServiceOfferingResponse>> updateOffering(
            @PathVariable UUID id,
            @Valid @RequestBody OfferingInput input,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        return ResponseEntity.ok(responses.ok(
                ApiMessages.UPDATED,
                service.saveOffering(id, input, resolveActor(jwt, rawOrgId)),
                request));
    }

    @PutMapping("/services/{id}/coverage")
    public ResponseEntity<ApiResponse<List<CoverageAreaItem>>> replaceOfferingCoverage(
            @PathVariable UUID id,
            @Valid @RequestBody UpsertServiceCoverageRequest input,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        return ResponseEntity.ok(responses.ok(
                ApiMessages.UPDATED,
                service.replaceOfferingCoverage(id, input, resolveActor(jwt, rawOrgId)),
                request));
    }

    @DeleteMapping("/services/{id}")
    public ResponseEntity<Void> deleteOffering(
            @PathVariable UUID id,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @AuthenticationPrincipal Jwt jwt) {
        service.deleteOffering(id, resolveActor(jwt, rawOrgId));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/technicians/me")
    public ResponseEntity<ApiResponse<TechnicianResponse>> me(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            HttpServletRequest request) {
        ActorContext actor = ActorContextResolver.resolvePersonalOnly(jwt, ActorContextResolver.parseOrganizationId(rawOrgId));
        return ResponseEntity.ok(responses.ok(ApiMessages.RETRIEVED, service.me(actor.userId()), request));
    }

    @PatchMapping("/technicians/me/online-status")
    public ResponseEntity<ApiResponse<TechnicianResponse>> updateMyOnlineStatus(
            @Valid @RequestBody UpdateTechnicianOnlineStatusRequest body,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            HttpServletRequest request) {
        ActorContext actor = ActorContextResolver.resolvePersonalOnly(jwt, ActorContextResolver.parseOrganizationId(rawOrgId));
        return ResponseEntity.ok(responses.ok(
                ApiMessages.UPDATED,
                service.updateMyOnlineStatus(actor.userId(), Boolean.TRUE.equals(body.online())),
                request));
    }

    @PostMapping("/technicians")
    public ResponseEntity<ApiResponse<TechnicianResponse>> createTechnician(
            @Valid @RequestBody TechnicianInput input,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(responses.created(
                ApiMessages.CREATED,
                service.saveTechnician(null, input, resolveActor(jwt, rawOrgId)),
                request));
    }

    @PatchMapping("/technicians/{id}")
    public ResponseEntity<ApiResponse<TechnicianResponse>> updateTechnician(
            @PathVariable UUID id,
            @Valid @RequestBody TechnicianInput input,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        return ResponseEntity.ok(responses.ok(
                ApiMessages.UPDATED,
                service.saveTechnician(id, input, resolveActor(jwt, rawOrgId)),
                request));
    }

    @DeleteMapping("/technicians/{id}")
    public ResponseEntity<Void> deleteTechnician(
            @PathVariable UUID id,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @AuthenticationPrincipal Jwt jwt) {
        service.deleteTechnician(id, resolveActor(jwt, rawOrgId));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/technicians/{id}/schedules/{windowId}")
    public ResponseEntity<Void> deleteSchedule(
            @PathVariable String id,
            @PathVariable UUID windowId,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal Jwt jwt) {
        service.deleteSchedule(id, windowId, resolveActor(jwt, rawOrgId), ifMatch);
        return ResponseEntity.noContent().build();
    }

    public record SubmitQualificationRequest(@NotBlank String evidenceRefs) {}

    @GetMapping("/providers/current/qualifications")
    public ResponseEntity<ApiResponse<List<CategoryQualificationResponse>>> listCurrentQualifications(
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        ActorContext actor = resolveActor(jwt, rawOrgId);
        List<CategoryQualificationResponse> items = actor.organizationId() == null
                ? List.of()
                : service.listCategoryQualifications(actor.organizationId()).stream()
                        .map(CategoryQualificationResponse::from)
                        .toList();
        return ResponseEntity.ok(responses.ok(ApiMessages.RETRIEVED, items, request));
    }

    @PostMapping("/providers/current/qualifications/{category}")
    public ResponseEntity<ApiResponse<CategoryQualificationResponse>> submitQualification(
            @PathVariable ServiceCategory category,
            @Valid @RequestBody SubmitQualificationRequest body,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest request) {
        CategoryQualificationResponse saved = CategoryQualificationResponse.from(
                service.submitCategoryQualification(category, body.evidenceRefs(), resolveActor(jwt, rawOrgId)));
        return ResponseEntity.ok(responses.ok(ApiMessages.UPDATED, saved, request));
    }

    @DeleteMapping("/providers/current/qualifications/{category}")
    public ResponseEntity<Void> deleteQualification(
            @PathVariable ServiceCategory category,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @AuthenticationPrincipal Jwt jwt) {
        service.deleteCategoryQualification(category, resolveActor(jwt, rawOrgId));
        return ResponseEntity.noContent().build();
    }
}
