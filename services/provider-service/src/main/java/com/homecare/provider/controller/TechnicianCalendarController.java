package com.homecare.provider.controller;

import com.homecare.common.security.ActorContext;
import com.homecare.common.security.ActorContextResolver;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.web.ApiMessages;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.provider.dto.RecurringScheduleDtos.OrganizationCalendarResponse;
import com.homecare.provider.security.ProviderAccessPolicy;
import com.homecare.provider.service.TechnicianCalendarService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/technicians")
@RequiredArgsConstructor
public class TechnicianCalendarController {

    private final TechnicianCalendarService calendarService;
    private final ProviderAccessPolicy accessPolicy;
    private final ServletApiResponseFactory responseFactory;

    @GetMapping("/calendar")
    public ResponseEntity<ApiResponse<OrganizationCalendarResponse>> getOrganizationCalendar(
            @PathVariable UUID organizationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID technicianId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        UUID headerOrgId = ActorContextResolver.parseOrganizationId(rawOrgId);
        ActorContext actor = ActorContextResolver.resolve(jwt, headerOrgId, accessPolicy);
        OrganizationCalendarResponse calendar = calendarService.getOrganizationCalendar(
                organizationId, from, to, technicianId, keyword, status, actor);
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.RETRIEVED, calendar, httpRequest));
    }
}
