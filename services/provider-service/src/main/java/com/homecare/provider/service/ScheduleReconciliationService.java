package com.homecare.provider.service;

import com.homecare.common.domain.EntityStatus;
import com.homecare.provider.dto.RecurringScheduleDtos.ScheduleConflictItem;
import com.homecare.provider.entity.ScheduleException;
import com.homecare.provider.entity.ScheduleExceptionType;
import com.homecare.provider.entity.ScheduleWindow;
import com.homecare.provider.entity.Technician;
import com.homecare.provider.entity.WeeklyScheduleRule;
import com.homecare.provider.entity.WeeklyScheduleRuleInterval;
import com.homecare.provider.repository.ScheduleExceptionRepository;
import com.homecare.provider.repository.ScheduleWindowRepository;
import com.homecare.provider.repository.TechnicianRepository;
import com.homecare.provider.repository.WeeklyScheduleRuleRepository;
import com.homecare.provider.service.IntervalMath.LocalTimeRange;
import com.homecare.provider.service.IntervalMath.TimeRange;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single authoritative reconciliation engine for recurring weekly schedules,
 * date-specific exceptions, rolling horizon replenishment, and booking release recalculation.
 */
@Service
@RequiredArgsConstructor
public class ScheduleReconciliationService {

    public static final String DEFAULT_TIMEZONE = "Asia/Ho_Chi_Minh";
    public static final String SOURCE_RECURRING = "RECURRING";
    public static final String SOURCE_MANUAL = "MANUAL";

    private static final Logger log = LoggerFactory.getLogger(ScheduleReconciliationService.class);

    private final TechnicianRepository technicianRepository;
    private final WeeklyScheduleRuleRepository weeklyRuleRepository;
    private final ScheduleExceptionRepository exceptionRepository;
    private final ScheduleWindowRepository scheduleWindowRepository;

    @Value("${homecare.schedule.horizon-days:30}")
    private int horizonDays = 30;

    private Clock clock = Clock.systemUTC();

    public int getHorizonDays() {
        return Math.max(1, horizonDays);
    }

    public void setHorizonDaysForTest(int horizonDays) {
        this.horizonDays = horizonDays;
    }

    public void setClockForTest(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public Instant now() {
        return Instant.now(clock);
    }

    public ZoneId resolveZone(List<WeeklyScheduleRule> rules) {
        if (rules != null && !rules.isEmpty()) {
            String tz = rules.getLast().getTimezone();
            if (tz != null && !tz.isBlank()) {
                return ZoneId.of(tz);
            }
        }
        return ZoneId.of(DEFAULT_TIMEZONE);
    }

    public static boolean isBooked(ScheduleWindow w) {
        return "BOOKED".equalsIgnoreCase(w.getStatus()) || w.getAllocatedBookingId() != null;
    }

    /**
     * Returns true if [start, end) overlaps any UNAVAILABLE exception for the technician
     * (used to prevent legacy manual POST/PATCH /schedules APIs from bypassing leave days).
     */
    public boolean hasUnavailableExceptionOverlap(UUID technicianId, Instant start, Instant end) {
        if (start == null || end == null || !end.isAfter(start)) {
            return false;
        }
        List<WeeklyScheduleRule> rules = weeklyRuleRepository.findByTechnicianIdOrderByEffectiveFromAsc(technicianId);
        ZoneId zone = resolveZone(rules);
        LocalDate fromDate = start.atZone(zone).toLocalDate();
        LocalDate toDate = end.minusNanos(1).atZone(zone).toLocalDate();
        List<ScheduleException> exceptions = exceptionRepository
                .findByTechnicianIdAndLocalDateBetweenOrderByLocalDateAscStartTimeAsc(technicianId, fromDate, toDate);
        if (exceptions.isEmpty()) {
            return false;
        }
        TimeRange candidate = new TimeRange(start, end);
        Map<LocalDate, List<ScheduleException>> byDate = groupExceptions(exceptions);
        for (LocalDate d = fromDate; !d.isAfter(toDate); d = d.plusDays(1)) {
            DayComputation day = computeDay(d, rules, byDate.getOrDefault(d, List.of()), zone);
            if (day.fullDayUnavailable()) {
                return true;
            }
            for (TimeRange unavail : day.unavailableRanges()) {
                if (candidate.overlaps(unavail)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks whether any BOOKED window on dates in [fromDate, toDate] (ending after now)
     * would be left uncovered or overlapped by an UNAVAILABLE exception under the candidate rules & exceptions.
     */
    public List<ScheduleConflictItem> findBookingConflicts(
            UUID technicianId,
            LocalDate fromDate,
            LocalDate toDate,
            List<WeeklyScheduleRule> candidateRules,
            List<ScheduleException> candidateExceptions,
            boolean includeBookingIds) {
        Instant now = now();
        ZoneId zone = resolveZone(candidateRules);
        Instant rangeStart = fromDate.atStartOfDay(zone).toInstant();
        Instant effectiveFloor = rangeStart.isAfter(now) ? rangeStart : now;
        Instant rangeEnd = toDate.plusDays(1).atStartOfDay(zone).toInstant();

        List<ScheduleWindow> bookedWindows = scheduleWindowRepository.findBookedEndingAfter(technicianId, effectiveFloor)
                .stream()
                .filter(w -> w.getStartsAt().isBefore(rangeEnd))
                .toList();
        if (bookedWindows.isEmpty()) {
            return List.of();
        }

        Map<LocalDate, List<ScheduleException>> exByDate = groupExceptions(candidateExceptions);
        List<ScheduleConflictItem> conflicts = new ArrayList<>();

        for (ScheduleWindow booked : bookedWindows) {
            TimeRange bookedRange = new TimeRange(booked.getStartsAt(), booked.getEndsAt());
            LocalDate startLocal = booked.getStartsAt().atZone(zone).toLocalDate();
            LocalDate endLocal = booked.getEndsAt().minusNanos(1).atZone(zone).toLocalDate();
            LocalDate dStart = startLocal.isBefore(fromDate) ? fromDate : startLocal;
            LocalDate dEnd = endLocal.isAfter(toDate) ? toDate : endLocal;

            List<TimeRange> allowedForBookedDates = new ArrayList<>();
            boolean overlapsUnavailable = false;
            for (LocalDate d = dStart; !d.isAfter(dEnd); d = d.plusDays(1)) {
                DayComputation day = computeDay(d, candidateRules, exByDate.getOrDefault(d, List.of()), zone);
                if (day.fullDayUnavailable()) {
                    overlapsUnavailable = true;
                    break;
                }
                for (TimeRange unavail : day.unavailableRanges()) {
                    if (bookedRange.overlaps(unavail)) {
                        overlapsUnavailable = true;
                        break;
                    }
                }
                if (overlapsUnavailable) {
                    break;
                }
                allowedForBookedDates.addAll(day.desiredWorkingRanges());
            }

            // If this booked window was originally MANUAL and not hit by UNAVAILABLE, don't reject weekly rule edits (BR-SCH-018)
            boolean isManualWindow = SOURCE_MANUAL.equalsIgnoreCase(booked.getSource());
            boolean uncoveredByNewSchedule = !isManualWindow && !IntervalMath.isCoveredBy(bookedRange, allowedForBookedDates);

            if (overlapsUnavailable || uncoveredByNewSchedule) {
                conflicts.add(new ScheduleConflictItem(
                        booked.getId(),
                        includeBookingIds ? booked.getAllocatedBookingId() : null,
                        booked.getStartsAt(),
                        booked.getEndsAt()));
            }
        }
        return conflicts;
    }

    /**
     * Reconciles future RECURRING AVAILABLE windows for a technician across the rolling horizon.
     * Caller may already hold lockById(technicianId); acquires lockById safely inside transaction.
     */
    @Transactional
    public void reconcileHorizon(UUID technicianId) {
        Technician tech = technicianRepository.lockById(technicianId).orElse(null);
        if (tech == null) {
            return;
        }
        List<WeeklyScheduleRule> rules = weeklyRuleRepository.findByTechnicianIdOrderByEffectiveFromAsc(technicianId);
        ZoneId zone = resolveZone(rules);
        LocalDate today = LocalDate.now(zone);
        LocalDate horizonEnd = today.plusDays(getHorizonDays() - 1L);
        reconcileDateRangeLocked(tech, today, horizonEnd, rules, false);
    }

    /**
     * Reconciles a specific date range [fromDate, toDate] clamped to the current rolling horizon.
     * When {@code applyUnavailableToManual} is true (e.g., UNAVAILABLE exception upsert),
     * UNAVAILABLE ranges also subtract/remove overlapping MANUAL AVAILABLE windows per BR-SCH-030.
     */
    @Transactional
    public void reconcileDateRange(
            Technician tech,
            LocalDate fromDate,
            LocalDate toDate,
            boolean applyUnavailableToManual) {
        List<WeeklyScheduleRule> rules = weeklyRuleRepository.findByTechnicianIdOrderByEffectiveFromAsc(tech.getId());
        ZoneId zone = resolveZone(rules);
        LocalDate today = LocalDate.now(zone);
        LocalDate horizonEnd = today.plusDays(getHorizonDays() - 1L);

        LocalDate clampedStart = fromDate.isBefore(today) ? today : fromDate;
        LocalDate clampedEnd = toDate.isAfter(horizonEnd) ? horizonEnd : toDate;
        if (clampedEnd.isBefore(clampedStart)) {
            return;
        }
        reconcileDateRangeLocked(tech, clampedStart, clampedEnd, rules, applyUnavailableToManual);
    }

    private void reconcileDateRangeLocked(
            Technician tech,
            LocalDate startDate,
            LocalDate endDate,
            List<WeeklyScheduleRule> rules,
            boolean applyUnavailableToManual) {
        Instant now = now();
        ZoneId zone = resolveZone(rules);
        Instant rangeStart = startDate.atStartOfDay(zone).toInstant();
        Instant reconcileFloor = rangeStart.isAfter(now) ? rangeStart : now;
        Instant rangeEnd = endDate.plusDays(1).atStartOfDay(zone).toInstant();

        List<ScheduleException> exceptions = exceptionRepository
                .findByTechnicianIdAndLocalDateBetweenOrderByLocalDateAscStartTimeAsc(tech.getId(), startDate, endDate);
        Map<LocalDate, List<ScheduleException>> exByDate = groupExceptions(exceptions);

        boolean techActive = EntityStatus.ACTIVE.name().equalsIgnoreCase(tech.getStatus());

        List<DesiredWindow> desiredRecurring = new ArrayList<>();
        List<TimeRange> allUnavailable = new ArrayList<>();

        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            DayComputation day = computeDay(d, rules, exByDate.getOrDefault(d, List.of()), zone);
            allUnavailable.addAll(day.unavailableRanges());
            if (day.fullDayUnavailable()) {
                allUnavailable.add(new TimeRange(
                        d.atStartOfDay(zone).toInstant(),
                        d.plusDays(1).atStartOfDay(zone).toInstant()));
            }
            if (!techActive) {
                continue;
            }
            for (TimeRange r : IntervalMath.clipAfter(day.desiredWorkingRanges(), reconcileFloor)) {
                desiredRecurring.add(new DesiredWindow(r, day.originRuleId()));
            }
        }

        List<ScheduleWindow> existing = scheduleWindowRepository.findOverlapping(tech.getId(), reconcileFloor, rangeEnd);

        // If UNAVAILABLE exceptions must override MANUAL AVAILABLE windows (BR-SCH-030)
        List<ScheduleWindow> manualToKeep = new ArrayList<>();
        List<ScheduleWindow> manualToDelete = new ArrayList<>();
        List<ScheduleWindow> manualToInsert = new ArrayList<>();
        List<ScheduleWindow> occupied = new ArrayList<>();
        List<ScheduleWindow> existingRecurringAvailable = new ArrayList<>();

        for (ScheduleWindow w : existing) {
            if (isBooked(w) || !"AVAILABLE".equalsIgnoreCase(w.getStatus())) {
                occupied.add(w);
                continue;
            }
            if (SOURCE_MANUAL.equalsIgnoreCase(w.getSource())) {
                if (applyUnavailableToManual && !allUnavailable.isEmpty()) {
                    TimeRange wRange = new TimeRange(w.getStartsAt(), w.getEndsAt());
                    List<TimeRange> remaining = IntervalMath.subtract(List.of(wRange), allUnavailable);
                    if (remaining.size() == 1 && remaining.getFirst().equals(wRange)) {
                        manualToKeep.add(w);
                    } else {
                        manualToDelete.add(w);
                        for (TimeRange seg : remaining) {
                            manualToInsert.add(ScheduleWindow.builder()
                                    .id(UUID.randomUUID())
                                    .technicianId(tech.getId())
                                    .startsAt(seg.start())
                                    .endsAt(seg.end())
                                    .status("AVAILABLE")
                                    .source(SOURCE_MANUAL)
                                    .createdAt(now)
                                    .build());
                        }
                    }
                } else {
                    manualToKeep.add(w);
                }
            } else {
                // Only reconcile RECURRING AVAILABLE windows that start at or after reconcileFloor,
                // or clip any in-progress recurring window cleanly
                existingRecurringAvailable.add(w);
            }
        }

        List<TimeRange> preservedBlockers = new ArrayList<>();
        for (ScheduleWindow w : occupied) {
            preservedBlockers.add(new TimeRange(w.getStartsAt(), w.getEndsAt()));
        }
        for (ScheduleWindow w : manualToKeep) {
            preservedBlockers.add(new TimeRange(w.getStartsAt(), w.getEndsAt()));
        }
        for (ScheduleWindow w : manualToInsert) {
            preservedBlockers.add(new TimeRange(w.getStartsAt(), w.getEndsAt()));
        }
        // Handle any existing recurring AVAILABLE window spanning reconcileFloor (startsAt < reconcileFloor < endsAt):
        // clip it to [startsAt, reconcileFloor) so future availability after now is strictly reconciled (e.g., same-day leave).
        List<ScheduleWindow> recurringToDelete = new ArrayList<>();
        List<ScheduleWindow> recurringToClip = new ArrayList<>();
        List<DesiredWindow> finalRecurring = new ArrayList<>();
        for (ScheduleWindow w : existingRecurringAvailable) {
            if (w.getStartsAt().isBefore(reconcileFloor)) {
                TimeRange futureTail = new TimeRange(reconcileFloor, w.getEndsAt());
                boolean tailStillDesired = desiredRecurring.stream()
                        .anyMatch(d -> d.range().covers(futureTail));
                if (tailStillDesired) {
                    preservedBlockers.add(new TimeRange(w.getStartsAt(), w.getEndsAt()));
                } else {
                    recurringToClip.add(w);
                    preservedBlockers.add(new TimeRange(w.getStartsAt(), reconcileFloor));
                }
            } else {
                recurringToDelete.add(w);
            }
        }

        for (DesiredWindow dw : desiredRecurring) {
            List<TimeRange> subtracted = IntervalMath.subtract(List.of(dw.range()), preservedBlockers);
            for (TimeRange seg : subtracted) {
                finalRecurring.add(new DesiredWindow(seg, dw.originRuleId()));
            }
        }
        finalRecurring.sort(Comparator.comparing(d -> d.range().start()));

        // Check true idempotency before writing
        boolean manualChanged = !manualToDelete.isEmpty() || !manualToInsert.isEmpty();
        boolean clipChanged = !recurringToClip.isEmpty();
        boolean recurringSame = recurringMatches(recurringToDelete, finalRecurring);
        if (!manualChanged && !clipChanged && recurringSame) {
            return;
        }

        for (ScheduleWindow w : recurringToClip) {
            w.setEndsAt(reconcileFloor);
            scheduleWindowRepository.save(w);
        }

        if (!manualToDelete.isEmpty()) {
            scheduleWindowRepository.deleteAll(manualToDelete);
        }
        if (!recurringToDelete.isEmpty()) {
            scheduleWindowRepository.deleteAll(recurringToDelete);
        }
        scheduleWindowRepository.flush();

        if (!manualToInsert.isEmpty()) {
            scheduleWindowRepository.saveAll(manualToInsert);
        }
        if (!finalRecurring.isEmpty()) {
            List<ScheduleWindow> toSave = new ArrayList<>(finalRecurring.size());
            for (DesiredWindow dw : finalRecurring) {
                toSave.add(ScheduleWindow.builder()
                        .id(UUID.randomUUID())
                        .technicianId(tech.getId())
                        .startsAt(dw.range().start())
                        .endsAt(dw.range().end())
                        .status("AVAILABLE")
                        .source(SOURCE_RECURRING)
                        .originRuleId(dw.originRuleId())
                        .createdAt(now)
                        .build());
            }
            scheduleWindowRepository.saveAll(toSave);
        }
        scheduleWindowRepository.flush();
    }

    /**
     * Recalculates eligible availability after a BOOKED window [releasedStart, releasedEnd) is released
     * by booking cancellation or reassignment (BR-SCH-034..037).
     * Never blindly flips released windows to AVAILABLE.
     */
    @Transactional
    public void recalculateReleasedWindow(
            Technician tech,
            Instant releasedStart,
            Instant releasedEnd,
            String originalSource,
            UUID originalRuleId) {
        Instant now = now();
        if (!EntityStatus.ACTIVE.name().equalsIgnoreCase(tech.getStatus())) {
            return;
        }
        if (!releasedEnd.isAfter(now)) {
            return;
        }
        Instant effectiveStart = releasedStart.isBefore(now) ? now : releasedStart;
        TimeRange releasedFuture = new TimeRange(effectiveStart, releasedEnd);

        List<WeeklyScheduleRule> rules = weeklyRuleRepository.findByTechnicianIdOrderByEffectiveFromAsc(tech.getId());
        ZoneId zone = resolveZone(rules);
        LocalDate startDate = effectiveStart.atZone(zone).toLocalDate();
        LocalDate endDate = releasedEnd.minusNanos(1).atZone(zone).toLocalDate();

        List<ScheduleException> exceptions = exceptionRepository
                .findByTechnicianIdAndLocalDateBetweenOrderByLocalDateAscStartTimeAsc(tech.getId(), startDate, endDate);
        Map<LocalDate, List<ScheduleException>> exByDate = groupExceptions(exceptions);

        List<TimeRange> eligibleRanges = new ArrayList<>();
        List<TimeRange> unavailableRanges = new ArrayList<>();
        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            DayComputation day = computeDay(d, rules, exByDate.getOrDefault(d, List.of()), zone);
            if (day.fullDayUnavailable()) {
                unavailableRanges.add(new TimeRange(
                        d.atStartOfDay(zone).toInstant(),
                        d.plusDays(1).atStartOfDay(zone).toInstant()));
            }
            unavailableRanges.addAll(day.unavailableRanges());
            eligibleRanges.addAll(day.desiredWorkingRanges());
        }

        List<TimeRange> toRestore;
        if (SOURCE_MANUAL.equalsIgnoreCase(originalSource)) {
            // MANUAL booking restores eligible manual availability (whether or not technician has a weekly rule),
            // while still respecting UNAVAILABLE exceptions, now, and active status (BR-SCH-061).
            toRestore = IntervalMath.subtract(List.of(releasedFuture), unavailableRanges);
        } else {
            // RECURRING booking intersects released future interval with currently effective schedule + exceptions (BR-SCH-034..037)
            toRestore = IntervalMath.intersect(List.of(releasedFuture), eligibleRanges);
        }

        if (toRestore.isEmpty()) {
            return;
        }

        // Subtract any already-existing overlapping windows (safety against races/manual edits)
        List<TimeRange> existingOverlaps = scheduleWindowRepository
                .findOverlapping(tech.getId(), effectiveStart, releasedEnd)
                .stream()
                .map(w -> new TimeRange(w.getStartsAt(), w.getEndsAt()))
                .toList();
        toRestore = IntervalMath.subtract(toRestore, existingOverlaps);
        if (toRestore.isEmpty()) {
            return;
        }

        String restoredSource = SOURCE_RECURRING.equalsIgnoreCase(originalSource) ? SOURCE_RECURRING : SOURCE_MANUAL;
        for (TimeRange seg : toRestore) {
            scheduleWindowRepository.save(ScheduleWindow.builder()
                    .id(UUID.randomUUID())
                    .technicianId(tech.getId())
                    .startsAt(seg.start())
                    .endsAt(seg.end())
                    .status("AVAILABLE")
                    .source(restoredSource)
                    .originRuleId(SOURCE_RECURRING.equals(restoredSource) ? originalRuleId : null)
                    .createdAt(now)
                    .build());
        }
        scheduleWindowRepository.flush();

        // Merge adjacent AVAILABLE windows of the same source (preserving existing merge semantics without merging MANUAL with RECURRING)
        mergeAdjacentAvailableSameSource(tech.getId());
    }

    private void mergeAdjacentAvailableSameSource(UUID technicianId) {
        ScheduleWindow previous = null;
        for (ScheduleWindow window : scheduleWindowRepository.findByTechnicianIdOrderByStartsAtAsc(technicianId)) {
            if (!"AVAILABLE".equalsIgnoreCase(window.getStatus()) || window.getAllocatedBookingId() != null) {
                previous = null;
            } else if (previous != null
                    && previous.getEndsAt().equals(window.getStartsAt())
                    && String.valueOf(previous.getSource()).equals(String.valueOf(window.getSource()))) {
                Instant newEnd = window.getEndsAt();
                scheduleWindowRepository.delete(window);
                scheduleWindowRepository.flush();
                previous.setEndsAt(newEnd);
                scheduleWindowRepository.saveAndFlush(previous);
            } else {
                previous = window;
            }
        }
    }

    private boolean recurringMatches(List<ScheduleWindow> existing, List<DesiredWindow> desired) {
        if (existing.size() != desired.size()) {
            return false;
        }
        List<ScheduleWindow> sortedExisting = new ArrayList<>(existing);
        sortedExisting.sort(Comparator.comparing(ScheduleWindow::getStartsAt));
        for (int i = 0; i < sortedExisting.size(); i++) {
            ScheduleWindow e = sortedExisting.get(i);
            DesiredWindow d = desired.get(i);
            if (!e.getStartsAt().equals(d.range().start()) || !e.getEndsAt().equals(d.range().end())) {
                return false;
            }
        }
        return true;
    }

    private Map<LocalDate, List<ScheduleException>> groupExceptions(List<ScheduleException> exceptions) {
        Map<LocalDate, List<ScheduleException>> map = new HashMap<>();
        if (exceptions == null) {
            return map;
        }
        for (ScheduleException ex : exceptions) {
            map.computeIfAbsent(ex.getLocalDate(), k -> new ArrayList<>()).add(ex);
        }
        return map;
    }

    private DayComputation computeDay(
            LocalDate date,
            List<WeeklyScheduleRule> rules,
            List<ScheduleException> dayExceptions,
            ZoneId defaultZone) {
        WeeklyScheduleRule activeRule = findRuleForDate(rules, date).orElse(null);
        ZoneId zone = activeRule != null ? ZoneId.of(activeRule.getTimezone()) : defaultZone;

        boolean fullDayUnavailable = false;
        List<LocalTimeRange> partialUnavailableLocal = new ArrayList<>();
        List<LocalTimeRange> customHoursLocal = new ArrayList<>();
        List<LocalTimeRange> extraHoursLocal = new ArrayList<>();

        for (ScheduleException ex : dayExceptions) {
            if (ex.getExceptionType() == ScheduleExceptionType.UNAVAILABLE) {
                if (ex.getStartTime() == null || ex.getEndTime() == null) {
                    fullDayUnavailable = true;
                } else {
                    partialUnavailableLocal.add(new LocalTimeRange(ex.getStartTime(), ex.getEndTime()));
                }
            } else if (ex.getExceptionType() == ScheduleExceptionType.CUSTOM_HOURS
                    && ex.getStartTime() != null && ex.getEndTime() != null) {
                customHoursLocal.add(new LocalTimeRange(ex.getStartTime(), ex.getEndTime()));
            } else if (ex.getExceptionType() == ScheduleExceptionType.EXTRA_HOURS
                    && ex.getStartTime() != null && ex.getEndTime() != null) {
                extraHoursLocal.add(new LocalTimeRange(ex.getStartTime(), ex.getEndTime()));
            }
        }

        List<TimeRange> unavailableInstants = new ArrayList<>();
        for (LocalTimeRange lr : IntervalMath.mergeLocal(partialUnavailableLocal)) {
            IntervalMath.toInstantRangeStrictDst(date, lr.start(), lr.end(), zone)
                    .ifPresent(unavailableInstants::add);
        }

        if (fullDayUnavailable) {
            return new DayComputation(List.of(), unavailableInstants, true, activeRule != null ? activeRule.getId() : null);
        }

        List<LocalTimeRange> baseLocal = new ArrayList<>();
        if (!customHoursLocal.isEmpty()) {
            baseLocal.addAll(customHoursLocal);
        } else if (activeRule != null) {
            short dow = (short) date.getDayOfWeek().getValue();
            for (WeeklyScheduleRuleInterval iv : activeRule.getIntervals()) {
                if (iv.getDayOfWeek() == dow) {
                    baseLocal.add(new LocalTimeRange(iv.getStartTime(), iv.getEndTime()));
                }
            }
        }
        // Deterministic precedence (BR-SCH-063): (CUSTOM_HOURS or weekly base) + EXTRA_HOURS - UNAVAILABLE
        baseLocal.addAll(extraHoursLocal);

        List<LocalTimeRange> netLocal = IntervalMath.subtractLocal(baseLocal, partialUnavailableLocal);
        List<TimeRange> desiredInstants = new ArrayList<>();
        for (LocalTimeRange lr : netLocal) {
            IntervalMath.toInstantRangeStrictDst(date, lr.start(), lr.end(), zone)
                    .ifPresent(desiredInstants::add);
        }

        return new DayComputation(
                IntervalMath.merge(desiredInstants),
                IntervalMath.merge(unavailableInstants),
                false,
                activeRule != null ? activeRule.getId() : null);
    }

    private Optional<WeeklyScheduleRule> findRuleForDate(List<WeeklyScheduleRule> rules, LocalDate date) {
        if (rules == null) {
            return Optional.empty();
        }
        for (WeeklyScheduleRule r : rules) {
            boolean afterStart = !date.isBefore(r.getEffectiveFrom());
            boolean beforeEnd = r.getEffectiveUntil() == null || !date.isAfter(r.getEffectiveUntil());
            if (afterStart && beforeEnd) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    public EffectiveDayPlan computeEffectiveDay(
            LocalDate date,
            List<WeeklyScheduleRule> rules,
            List<ScheduleException> dayExceptions,
            ZoneId defaultZone) {
        WeeklyScheduleRule activeRule = findRuleForDate(rules, date).orElse(null);
        ZoneId zone = activeRule != null ? ZoneId.of(activeRule.getTimezone()) : defaultZone;
        DayComputation comp = computeDay(date, rules, dayExceptions == null ? List.of() : dayExceptions, zone);
        return new EffectiveDayPlan(
                comp.desiredWorkingRanges(),
                comp.unavailableRanges(),
                comp.fullDayUnavailable(),
                comp.originRuleId(),
                activeRule != null,
                zone);
    }

    public record EffectiveDayPlan(
            List<TimeRange> desiredWorkingRanges,
            List<TimeRange> unavailableRanges,
            boolean fullDayUnavailable,
            UUID originRuleId,
            boolean withinRuleRange,
            ZoneId zone) {
    }

    private record DesiredWindow(TimeRange range, UUID originRuleId) {
    }

    private record DayComputation(
            List<TimeRange> desiredWorkingRanges,
            List<TimeRange> unavailableRanges,
            boolean fullDayUnavailable,
            UUID originRuleId) {
    }
}
