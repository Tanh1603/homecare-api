package com.homecare.provider.dto;

import com.homecare.provider.entity.ScheduleChangeRequestStatus;
import com.homecare.provider.entity.ScheduleChangeRequestType;
import com.homecare.provider.entity.ScheduleExceptionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class RecurringScheduleDtos {

    private RecurringScheduleDtos() {
    }

    public record DayTimeIntervalDto(
            @NotNull DayOfWeek dayOfWeek,
            @NotNull LocalTime startTime,
            @NotNull LocalTime endTime) {
    }

    public record LocalTimeIntervalDto(
            @NotNull LocalTime startTime,
            @NotNull LocalTime endTime) {
    }

    public record UpsertWeeklyScheduleRequest(
            String timezone,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveUntil,
            @NotNull List<@Valid DayTimeIntervalDto> intervals) {
    }

    public record WeeklyScheduleRuleResponse(
            UUID id,
            String timezone,
            LocalDate effectiveFrom,
            LocalDate effectiveUntil,
            Long version,
            List<DayTimeIntervalDto> intervals) {
    }

    public record WeeklyScheduleOverviewResponse(
            UUID technicianId,
            int horizonDays,
            WeeklyScheduleRuleResponse currentRule,
            List<WeeklyScheduleRuleResponse> upcomingRules) {
    }

    public record UpsertScheduleExceptionsRequest(
            @NotNull LocalDate fromDate,
            LocalDate toDate,
            @NotNull ScheduleExceptionType exceptionType,
            String reason,
            List<@Valid LocalTimeIntervalDto> intervals) {
    }

    public record ScheduleExceptionResponse(
            UUID id,
            UUID technicianId,
            LocalDate localDate,
            ScheduleExceptionType exceptionType,
            LocalTime startTime,
            LocalTime endTime,
            String reason,
            Long version) {
    }

    public record ScheduleConflictItem(
            UUID windowId,
            UUID bookingId,
            Instant startsAt,
            Instant endsAt) {
    }

    public record CalendarSlotDto(
            UUID windowId,
            Instant startsAt,
            Instant endsAt,
            String localStartTime,
            String localEndTime,
            String status,
            String source,
            UUID allocatedBookingId,
            Long version,
            boolean projected) {
    }

    public record CalendarDayDto(
            LocalDate date,
            DayOfWeek dayOfWeek,
            String dayStatus,
            boolean projected,
            int bookingCount,
            String exceptionType,
            String exceptionReason,
            List<CalendarSlotDto> slots) {
    }

    public record TechnicianCalendarRowDto(
            UUID technicianId,
            String profileId,
            String fullName,
            String status,
            boolean isOnline,
            List<String> specialties,
            String timezone,
            List<CalendarDayDto> days) {
    }

    public record OrganizationCalendarResponse(
            UUID organizationId,
            LocalDate from,
            LocalDate to,
            String timezone,
            int horizonDays,
            List<TechnicianCalendarRowDto> technicians) {
    }

    public record ScheduleAuditResponse(
            UUID id,
            UUID technicianId,
            String actorId,
            String action,
            String scope,
            LocalDate affectedFrom,
            LocalDate affectedTo,
            String detailsJson,
            Instant createdAt) {
    }

    public record CreateScheduleChangeRequest(
            @NotNull ScheduleChangeRequestType requestType,
            @NotNull LocalDate fromDate,
            LocalDate toDate,
            LocalTime requestedStartTime,
            LocalTime requestedEndTime,
            @Size(max = 500) String reason) {
    }

    public record ReviewScheduleChangeRequest(
            @NotNull Boolean approve,
            @Size(max = 500) String reviewNote) {
    }

    public record ScheduleChangeRequestResponse(
            UUID id,
            UUID technicianId,
            String technicianName,
            UUID organizationId,
            ScheduleChangeRequestType requestType,
            LocalDate fromDate,
            LocalDate toDate,
            LocalTime requestedStartTime,
            LocalTime requestedEndTime,
            String reason,
            ScheduleChangeRequestStatus status,
            String reviewedBy,
            String reviewNote,
            Instant reviewedAt,
            Long version,
            Instant createdAt) {
    }
}
