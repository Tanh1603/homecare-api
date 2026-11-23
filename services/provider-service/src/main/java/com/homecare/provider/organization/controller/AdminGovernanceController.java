package com.homecare.provider.organization.controller;

import com.homecare.common.security.RbacConstants;
import com.homecare.common.web.ApiMessages;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.provider.entity.PlatformGovernanceSettingEntity;
import com.homecare.provider.service.PlatformGovernanceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/governance/settings")
@PreAuthorize(RbacConstants.EXPR_ORG_REVIEW)
@Validated
@RequiredArgsConstructor
public class AdminGovernanceController {

    private final PlatformGovernanceService governanceService;
    private final ServletApiResponseFactory responseFactory;

    public record UpdateGovernanceSettingRequest(@NotBlank String value) {}

    public record GovernanceSettingResponse(
            String settingKey,
            String settingValue,
            String updatedBy,
            Instant updatedAt) {
        static GovernanceSettingResponse from(PlatformGovernanceSettingEntity e) {
            return new GovernanceSettingResponse(
                    e.getSettingKey(), e.getSettingValue(), e.getUpdatedBy(), e.getUpdatedAt());
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<GovernanceSettingResponse>>> listSettings(
            HttpServletRequest httpRequest) {
        List<GovernanceSettingResponse> items = governanceService.listAllSettings().stream()
                .map(GovernanceSettingResponse::from)
                .toList();
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.RETRIEVED,
                items,
                httpRequest));
    }

    @PutMapping("/{key}")
    public ResponseEntity<ApiResponse<GovernanceSettingResponse>> updateSetting(
            @PathVariable String key,
            @Valid @RequestBody UpdateGovernanceSettingRequest request,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        PlatformGovernanceSettingEntity updated = governanceService.updateSetting(
                key, request.value(), jwt.getSubject());
        return ResponseEntity.ok(responseFactory.ok(
                ApiMessages.UPDATED,
                GovernanceSettingResponse.from(updated),
                httpRequest));
    }
}
