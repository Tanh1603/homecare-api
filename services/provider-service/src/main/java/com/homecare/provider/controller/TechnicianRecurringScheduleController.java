package com.homecare.provider.controller;

import com.homecare.common.security.ActorContext;
import com.homecare.common.security.ActorContextResolver;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.web.ApiMessages;
import com.homecare.common.web.ApiResponse;
import com.homecare.common.web.EtagSupport;
import com.homecare.common.web.servlet.ServletApiResponseFactory;
import com.homecare.provider.dto.RecurringScheduleDtos.ScheduleAuditResponse;
import com.homecare.provider.dto.RecurringScheduleDtos.ScheduleExceptionResponse;
import com.homecare.provider.dto.RecurringScheduleDtos.TechnicianCalendarRowDto;
import com.homecare.provider.dto.RecurringScheduleDtos.UpsertScheduleExceptionsRequest;
import com.homecare.provider.dto.RecurringScheduleDtos.UpsertWeeklyScheduleRequest;
import com.homecare.provider.dto.RecurringScheduleDtos.WeeklyScheduleOverviewResponse;
import com.homecare.provider.security.ProviderAccessPolicy;
import com.homecare.provider.service.TechnicianCalendarService;
import com.homecare.provider.service.TechnicianRecurringScheduleService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/technicians/{technicianId}")
@RequiredArgsConstructor
public class TechnicianRecurringScheduleController {

    private final TechnicianRecurringScheduleService recurringScheduleService;
    private final TechnicianCalendarService calendarService;
    private final ProviderAccessPolicy accessPolicy;
    private final ServletApiResponseFactory responseFactory;

    private ActorContext resolveActor(Jwt jwt, String rawOrgId) {
        return ActorContextResolver.resolve(jwt, ActorContextResolver.parseOrganizationId(rawOrgId), accessPolicy);
    }

    @GetMapping("/calendar")
    public ResponseEntity<ApiResponse<TechnicianCalendarRowDto>> getTechnicianCalendar(
            @PathVariable String technicianId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        ActorContext actor = resolveActor(jwt, rawOrgId);
        TechnicianCalendarRowDto calendar = calendarService.getTechnicianCalendar(technicianId, from, to, actor);
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.RETRIEVED, calendar, httpRequest));
    }

    @GetMapping("/schedule-audits")
    public ResponseEntity<ApiResponse<List<ScheduleAuditResponse>>> listScheduleAudits(
            @PathVariable String technicianId,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        ActorContext actor = resolveActor(jwt, rawOrgId);
        List<ScheduleAuditResponse> audits = recurringScheduleService.listScheduleAudits(technicianId, actor);
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.RETRIEVED, audits, httpRequest));
    }

    @GetMapping("/weekly-schedules")
    public ResponseEntity<ApiResponse<WeeklyScheduleOverviewResponse>> getWeeklySchedule(
            @PathVariable String technicianId,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        ActorContext actor = resolveActor(jwt, rawOrgId);
        WeeklyScheduleOverviewResponse overview = recurringScheduleService.getWeeklyScheduleOverview(technicianId, actor);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(latestVersion(overview)))
                .body(responseFactory.ok(ApiMessages.RETRIEVED, overview, httpRequest));
    }

    @PutMapping("/weekly-schedules")
    public ResponseEntity<ApiResponse<WeeklyScheduleOverviewResponse>> upsertWeeklySchedule(
            @PathVariable String technicianId,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpsertWeeklyScheduleRequest request,
            HttpServletRequest httpRequest) {
        ActorContext actor = resolveActor(jwt, rawOrgId);
        WeeklyScheduleOverviewResponse updated = recurringScheduleService.upsertWeeklySchedule(
                technicianId, actor, ifMatch, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EtagSupport.format(latestVersion(updated)))
                .body(responseFactory.ok(ApiMessages.UPDATED, updated, httpRequest));
    }

    @DeleteMapping("/weekly-schedules")
    public ResponseEntity<Void> endWeeklySchedule(
            @PathVariable String technicianId,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveUntil,
            @AuthenticationPrincipal Jwt jwt) {
        ActorContext actor = resolveActor(jwt, rawOrgId);
        recurringScheduleService.endWeeklySchedule(technicianId, actor, ifMatch, effectiveUntil);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/schedule-exceptions")
    public ResponseEntity<ApiResponse<List<ScheduleExceptionResponse>>> listExceptions(
            @PathVariable String technicianId,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal Jwt jwt,
            HttpServletRequest httpRequest) {
        ActorContext actor = resolveActor(jwt, rawOrgId);
        List<ScheduleExceptionResponse> items = recurringScheduleService.listExceptions(technicianId, actor, from, to);
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.RETRIEVED, items, httpRequest));
    }

    @PutMapping("/schedule-exceptions")
    public ResponseEntity<ApiResponse<List<ScheduleExceptionResponse>>> upsertExceptions(
            @PathVariable String technicianId,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpsertScheduleExceptionsRequest request,
            HttpServletRequest httpRequest) {
        ActorContext actor = resolveActor(jwt, rawOrgId);
        List<ScheduleExceptionResponse> saved = recurringScheduleService.upsertExceptions(technicianId, actor, request);
        return ResponseEntity.ok(responseFactory.ok(ApiMessages.UPDATED, saved, httpRequest));
    }

    @DeleteMapping("/schedule-exceptions/{exceptionId}")
    public ResponseEntity<Void> deleteException(
            @PathVariable String technicianId,
            @PathVariable UUID exceptionId,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal Jwt jwt) {
        ActorContext actor = resolveActor(jwt, rawOrgId);
        recurringScheduleService.deleteExceptionById(technicianId, actor, exceptionId, ifMatch);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/schedule-exceptions")
    public ResponseEntity<Void> deleteExceptionsByDate(
            @PathVariable String technicianId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestHeader(value = RbacConstants.HEADER_ORGANIZATION_ID, required = false) String rawOrgId,
            @AuthenticationPrincipal Jwt jwt) {
        ActorContext actor = resolveActor(jwt, rawOrgId);
        recurringScheduleService.deleteExceptionsByDate(technicianId, actor, date);
        return ResponseEntity.noContent().build();
    }

    private Long latestVersion(WeeklyScheduleOverviewResponse overview) {
        if (overview.upcomingRules() != null && !overview.upcomingRules().isEmpty()) {
            return overview.upcomingRules().getLast().version();
        }
        return overview.currentRule() != null ? overview.currentRule().version() : 0L;
    }
}
