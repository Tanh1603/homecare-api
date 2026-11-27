package com.homecare.provider.service;

import com.homecare.common.domain.EntityStatus;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.security.ActorContext;
import com.homecare.provider.dto.RecurringScheduleDtos.CalendarDayDto;
import com.homecare.provider.dto.RecurringScheduleDtos.CalendarSlotDto;
import com.homecare.provider.dto.RecurringScheduleDtos.OrganizationCalendarResponse;
import com.homecare.provider.dto.RecurringScheduleDtos.TechnicianCalendarRowDto;
import com.homecare.provider.entity.ScheduleException;
import com.homecare.provider.entity.ScheduleWindow;
import com.homecare.provider.entity.ScheduleWindowStatus;
import com.homecare.provider.entity.Technician;
import com.homecare.provider.entity.WeeklyScheduleRule;
import com.homecare.provider.exception.ProviderErrorCode;
import com.homecare.provider.repository.ScheduleExceptionRepository;
import com.homecare.provider.repository.ScheduleWindowRepository;
import com.homecare.provider.repository.TechnicianRepository;
import com.homecare.provider.repository.WeeklyScheduleRuleRepository;
import com.homecare.provider.security.ProviderAccessPolicy;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single source of truth for effective technician schedule calendars across Org Portal and Technician Mobile.
 * Combines materialized schedule_windows with on-the-fly rule/exception projection beyond the materialization horizon.
 */
@Service
@RequiredArgsConstructor
public class TechnicianCalendarService {

    public static final int MAX_CALENDAR_RANGE_DAYS = 62;
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final TechnicianRepository technicianRepository;
    private final WeeklyScheduleRuleRepository weeklyRuleRepository;
    private final ScheduleExceptionRepository exceptionRepository;
    private final ScheduleWindowRepository scheduleWindowRepository;
    private final ScheduleReconciliationService reconciliationService;
    private final ProviderAccessPolicy accessPolicy;

    @Transactional(readOnly = true)
    public OrganizationCalendarResponse getOrganizationCalendar(
            UUID organizationId,
            LocalDate from,
            LocalDate to,
            UUID technicianId,
            String keyword,
            String status,
            ActorContext actor) {
        accessPolicy.assertCanViewOrganizationCalendar(actor, organizationId);
        ZoneId defaultZone = ZoneId.of(ScheduleReconciliationService.DEFAULT_TIMEZONE);
        LocalDate today = LocalDate.now(defaultZone);
        LocalDate effectiveFrom = from != null ? from : today;
        LocalDate effectiveTo = to != null ? to : effectiveFrom.plusDays(6);
        validateRange(effectiveFrom, effectiveTo);

        List<Technician> orgTechs = technicianRepository.findByProviderIdAndStatus(
                organizationId, EntityStatus.ACTIVE.name());

        String normalizedKeyword = keyword != null && !keyword.isBlank()
                ? keyword.trim().toLowerCase(Locale.ROOT)
                : null;
        String normalizedStatus = status != null && !status.isBlank()
                ? status.trim().toUpperCase(Locale.ROOT)
                : "ALL";

        List<Technician> filteredTechs = orgTechs.stream()
                .filter(t -> technicianId == null || technicianId.equals(t.getId()))
                .filter(t -> matchesKeyword(t, normalizedKeyword))
                .sorted(Comparator.comparing(Technician::getFullName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        List<TechnicianCalendarRowDto> rows = new ArrayList<>();
        if (!filteredTechs.isEmpty()) {
            List<UUID> techIds = filteredTechs.stream().map(Technician::getId).toList();
            Map<UUID, List<WeeklyScheduleRule>> rulesByTech = new HashMap<>();
            for (WeeklyScheduleRule r : weeklyRuleRepository.findByTechnicianIdInOrderByEffectiveFromAsc(techIds)) {
                rulesByTech.computeIfAbsent(r.getTechnicianId(), k -> new ArrayList<>()).add(r);
            }
            Map<UUID, List<ScheduleException>> exByTech = new HashMap<>();
            for (ScheduleException ex : exceptionRepository
                    .findByTechnicianIdInAndLocalDateBetweenOrderByLocalDateAscStartTimeAsc(
                            techIds, effectiveFrom, effectiveTo)) {
                exByTech.computeIfAbsent(ex.getTechnicianId(), k -> new ArrayList<>()).add(ex);
            }
            // Broaden window query by +/-1 day to safely cover technicians in any timezone offset
            Instant broadStart = effectiveFrom.minusDays(1).atStartOfDay(defaultZone).toInstant();
            Instant broadEnd = effectiveTo.plusDays(2).atStartOfDay(defaultZone).toInstant();
            Map<UUID, List<ScheduleWindow>> windowsByTech = new HashMap<>();
            for (ScheduleWindow w : scheduleWindowRepository.findOverlappingForTechnicians(
                    techIds, broadStart, broadEnd)) {
                windowsByTech.computeIfAbsent(w.getTechnicianId(), k -> new ArrayList<>()).add(w);
            }

            for (Technician tech : filteredTechs) {
                TechnicianCalendarRowDto row = buildTechnicianRow(
                        tech,
                        effectiveFrom,
                        effectiveTo,
                        accessPolicy.canViewScheduleBookingIds(actor, tech),
                        rulesByTech.getOrDefault(tech.getId(), List.of()),
                        exByTech.getOrDefault(tech.getId(), List.of()),
                        windowsByTech.getOrDefault(tech.getId(), List.of()));
                if (matchesStatusFilter(row, normalizedStatus)) {
                    rows.add(row);
                }
            }
        }

        return new OrganizationCalendarResponse(
                organizationId,
                effectiveFrom,
                effectiveTo,
                defaultZone.getId(),
                reconciliationService.getHorizonDays(),
                rows);
    }

    @Transactional(readOnly = true)
    public TechnicianCalendarRowDto getTechnicianCalendar(
            String technicianIdOrMe,
            LocalDate from,
            LocalDate to,
            ActorContext actor) {
        Technician tech = resolveTechnician(technicianIdOrMe, actor);
        accessPolicy.assertCanViewTechnicianSchedule(actor, tech);
        List<WeeklyScheduleRule> rules = weeklyRuleRepository.findByTechnicianIdOrderByEffectiveFromAsc(tech.getId());
        ZoneId zone = reconciliationService.resolveZone(rules);
        LocalDate today = LocalDate.ofInstant(reconciliationService.now(), zone);
        LocalDate effectiveFrom = from != null ? from : today;
        LocalDate effectiveTo = to != null ? to : effectiveFrom.plusDays(6);
        validateRange(effectiveFrom, effectiveTo);

        List<ScheduleException> exceptions = exceptionRepository
                .findByTechnicianIdAndLocalDateBetweenOrderByLocalDateAscStartTimeAsc(
                        tech.getId(), effectiveFrom, effectiveTo);
        Instant rangeStart = effectiveFrom.atStartOfDay(zone).toInstant();
        Instant rangeEnd = effectiveTo.plusDays(1).atStartOfDay(zone).toInstant();
        List<ScheduleWindow> windows = scheduleWindowRepository
                .findOverlapping(tech.getId(), rangeStart, rangeEnd);

        return buildTechnicianRow(
                tech,
                effectiveFrom,
                effectiveTo,
                accessPolicy.canViewScheduleBookingIds(actor, tech),
                rules,
                exceptions,
                windows);
    }

    private TechnicianCalendarRowDto buildTechnicianRow(
            Technician tech,
            LocalDate from,
            LocalDate to,
            boolean includeBookingIds,
            List<WeeklyScheduleRule> rules,
            List<ScheduleException> exceptions,
            List<ScheduleWindow> preloadedWindows) {
        ZoneId zone = reconciliationService.resolveZone(rules);
        Instant now = reconciliationService.now();
        LocalDate today = LocalDate.ofInstant(now, zone);
        LocalDate horizonEnd = today.plusDays(reconciliationService.getHorizonDays() - 1L);

        Map<LocalDate, List<ScheduleException>> exByDate = new HashMap<>();
        for (ScheduleException ex : exceptions) {
            exByDate.computeIfAbsent(ex.getLocalDate(), k -> new ArrayList<>()).add(ex);
        }

        Instant rangeStart = from.atStartOfDay(zone).toInstant();
        Instant rangeEnd = to.plusDays(1).atStartOfDay(zone).toInstant();
        List<ScheduleWindow> windows = preloadedWindows.stream()
                .filter(w -> w.getStartsAt().isBefore(rangeEnd) && w.getEndsAt().isAfter(rangeStart))
                .sorted(Comparator.comparing(ScheduleWindow::getStartsAt))
                .toList();

        List<CalendarDayDto> days = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            Instant dayStart = date.atStartOfDay(zone).toInstant();
            Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
            List<ScheduleException> dayExceptions = exByDate.getOrDefault(date, List.of());
            ScheduleReconciliationService.EffectiveDayPlan plan =
                    reconciliationService.computeEffectiveDay(date, rules, dayExceptions, zone);

            List<ScheduleWindow> dayWindows = windows.stream()
                    .filter(w -> w.getStartsAt().isBefore(dayEnd) && w.getEndsAt().isAfter(dayStart))
                    .toList();

            List<CalendarSlotDto> slots = new ArrayList<>();
            int bookingCount = 0;
            boolean dayProjected = false;

            if (!dayWindows.isEmpty()) {
                for (ScheduleWindow w : dayWindows) {
                    boolean booked = ScheduleReconciliationService.isBooked(w);
                    if (booked) {
                        bookingCount++;
                    }
                    Instant displayStart = w.getStartsAt().isBefore(dayStart) ? dayStart : w.getStartsAt();
                    Instant displayEnd = w.getEndsAt().isAfter(dayEnd) ? dayEnd : w.getEndsAt();
                    slots.add(new CalendarSlotDto(
                            w.getId(),
                            w.getStartsAt(),
                            w.getEndsAt(),
                            formatLocalTime(displayStart, zone, false),
                            formatLocalTime(displayEnd, zone, displayEnd.equals(dayEnd)),
                            w.getStatus(),
                            w.getSource() != null ? w.getSource() : ScheduleReconciliationService.SOURCE_MANUAL,
                            includeBookingIds ? w.getAllocatedBookingId() : null,
                            w.getVersion(),
                            false));
                }
            } else if (date.isAfter(horizonEnd) && !plan.fullDayUnavailable() && !plan.desiredWorkingRanges().isEmpty()) {
                dayProjected = true;
                for (IntervalMath.TimeRange r : plan.desiredWorkingRanges()) {
                    slots.add(new CalendarSlotDto(
                            null,
                            r.start(),
                            r.end(),
                            formatLocalTime(r.start(), plan.zone(), false),
                            formatLocalTime(r.end(), plan.zone(), r.end().equals(dayEnd)),
                            ScheduleWindowStatus.AVAILABLE.name(),
                            ScheduleReconciliationService.SOURCE_RECURRING,
                            null,
                            null,
                            true));
                }
            }

            boolean hasAvailable = slots.stream()
                    .anyMatch(s -> ScheduleWindowStatus.AVAILABLE.name().equalsIgnoreCase(s.status()));
            String dayStatus;
            if (bookingCount > 0) {
                dayStatus = "BOOKED";
            } else if (hasAvailable) {
                dayStatus = "WORKING";
            } else if (plan.fullDayUnavailable() || plan.withinRuleRange()) {
                dayStatus = "OFF";
            } else {
                dayStatus = "UNSCHEDULED";
            }

            ScheduleException primaryEx = dayExceptions.isEmpty() ? null : dayExceptions.getFirst();
            days.add(new CalendarDayDto(
                    date,
                    date.getDayOfWeek(),
                    dayStatus,
                    dayProjected,
                    bookingCount,
                    primaryEx != null ? primaryEx.getExceptionType().name() : null,
                    primaryEx != null ? primaryEx.getReason() : null,
                    slots));
        }

        List<String> specialties = tech.getSpecialties() == null
                ? List.of()
                : Arrays.stream(tech.getSpecialties().split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();

        return new TechnicianCalendarRowDto(
                tech.getId(),
                tech.getProfileId(),
                tech.getFullName(),
                tech.getStatus(),
                tech.isOnline(),
                specialties,
                zone.getId(),
                days);
    }

    private boolean matchesKeyword(Technician tech, String keyword) {
        if (keyword == null) {
            return true;
        }
        return (tech.getFullName() != null && tech.getFullName().toLowerCase(Locale.ROOT).contains(keyword))
                || (tech.getProfileId() != null && tech.getProfileId().toLowerCase(Locale.ROOT).contains(keyword))
                || tech.getId().toString().toLowerCase(Locale.ROOT).contains(keyword);
    }

    private boolean matchesStatusFilter(TechnicianCalendarRowDto row, String status) {
        return switch (status) {
            case "ALL" -> true;
            case "HAS_SHIFT", "WORKING" -> row.days().stream()
                    .anyMatch(d -> "WORKING".equals(d.dayStatus()) || "BOOKED".equals(d.dayStatus()));
            case "AVAILABLE" -> row.days().stream()
                    .flatMap(d -> d.slots().stream())
                    .anyMatch(s -> ScheduleWindowStatus.AVAILABLE.name().equalsIgnoreCase(s.status()));
            case "BOOKED" -> row.days().stream().anyMatch(d -> d.bookingCount() > 0);
            case "OFF" -> row.days().stream().anyMatch(d -> "OFF".equals(d.dayStatus()));
            case "UNSCHEDULED" -> row.days().stream().allMatch(d -> "UNSCHEDULED".equals(d.dayStatus()));
            default -> true;
        };
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Ngày kết thúc (to) phải lớn hơn hoặc bằng ngày bắt đầu (from).");
        }
        long span = ChronoUnit.DAYS.between(from, to) + 1;
        if (span > MAX_CALENDAR_RANGE_DAYS) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Khoảng thời gian truy vấn lịch tối đa " + MAX_CALENDAR_RANGE_DAYS + " ngày.");
        }
    }

    private Technician resolveTechnician(String technicianIdOrMe, ActorContext actor) {
        if ("me".equalsIgnoreCase(technicianIdOrMe)) {
            if (actor == null || actor.userId() == null || actor.userId().isBlank()) {
                throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
            }
            return technicianRepository.findByProfileId(actor.userId())
                    .orElseThrow(() -> new DomainException(ProviderErrorCode.TECHNICIAN_NOT_FOUND));
        }
        UUID id;
        try {
            id = UUID.fromString(technicianIdOrMe);
        } catch (IllegalArgumentException ex) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "technicianId không hợp lệ.");
        }
        return technicianRepository.findById(id)
                .orElseThrow(() -> new DomainException(ProviderErrorCode.TECHNICIAN_NOT_FOUND));
    }

    private String formatLocalTime(Instant instant, ZoneId zone, boolean isEndOfDay) {
        if (isEndOfDay && instant.atZone(zone).toLocalTime().equals(LocalTime.MIDNIGHT)) {
            return "24:00";
        }
        return instant.atZone(zone).toLocalTime().format(HH_MM);
    }
}
