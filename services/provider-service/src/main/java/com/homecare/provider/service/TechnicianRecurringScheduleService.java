package com.homecare.provider.service;

import tools.jackson.databind.ObjectMapper;
import com.homecare.common.domain.EntityStatus;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.common.security.ActorContext;
import com.homecare.common.web.EtagSupport;
import com.homecare.provider.dto.RecurringScheduleDtos.DayTimeIntervalDto;
import com.homecare.provider.dto.RecurringScheduleDtos.LocalTimeIntervalDto;
import com.homecare.provider.dto.RecurringScheduleDtos.ScheduleAuditResponse;
import com.homecare.provider.dto.RecurringScheduleDtos.ScheduleConflictItem;
import com.homecare.provider.dto.RecurringScheduleDtos.ScheduleExceptionResponse;
import com.homecare.provider.dto.RecurringScheduleDtos.UpsertScheduleExceptionsRequest;
import com.homecare.provider.dto.RecurringScheduleDtos.UpsertWeeklyScheduleRequest;
import com.homecare.provider.dto.RecurringScheduleDtos.WeeklyScheduleOverviewResponse;
import com.homecare.provider.dto.RecurringScheduleDtos.WeeklyScheduleRuleResponse;
import com.homecare.provider.entity.ScheduleException;
import com.homecare.provider.entity.ScheduleExceptionType;
import com.homecare.provider.entity.Technician;
import com.homecare.provider.entity.TechnicianScheduleAudit;
import com.homecare.provider.entity.WeeklyScheduleRule;
import com.homecare.provider.entity.WeeklyScheduleRuleInterval;
import com.homecare.provider.exception.ProviderErrorCode;
import com.homecare.provider.organization.entity.ProviderOutboxEventEntity;
import com.homecare.provider.organization.repository.ProviderOutboxEventRepository;
import com.homecare.provider.repository.ScheduleExceptionRepository;
import com.homecare.provider.repository.TechnicianRepository;
import com.homecare.provider.repository.TechnicianScheduleAuditRepository;
import com.homecare.provider.repository.WeeklyScheduleRuleRepository;
import com.homecare.provider.security.ProviderAccessPolicy;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages technician recurring weekly schedule rules, date-specific exceptions,
 * conflict protection, ETag optimistic concurrency, and audit logging (D-32, BR-SCH-001..060).
 */
@Service
@RequiredArgsConstructor
public class TechnicianRecurringScheduleService {

    private static final int MAX_BULK_EXCEPTION_DAYS = 90;

    private final TechnicianRepository technicianRepository;
    private final WeeklyScheduleRuleRepository weeklyRuleRepository;
    private final ScheduleExceptionRepository exceptionRepository;
    private final TechnicianScheduleAuditRepository auditRepository;
    private final ProviderOutboxEventRepository outboxRepository;
    private final ProviderAccessPolicy accessPolicy;
    private final ScheduleReconciliationService reconciliationService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public WeeklyScheduleOverviewResponse getWeeklyScheduleOverview(String technicianIdOrMe, ActorContext actor) {
        Technician tech = resolveTechnician(technicianIdOrMe, actor);
        accessPolicy.assertCanViewTechnicianSchedule(actor, tech);
        List<WeeklyScheduleRule> rules = weeklyRuleRepository.findByTechnicianIdOrderByEffectiveFromAsc(tech.getId());
        return toOverview(tech.getId(), rules);
    }

    @Transactional(readOnly = true)
    public List<ScheduleAuditResponse> listScheduleAudits(
            String technicianIdOrMe, ActorContext actor) {
        Technician tech = resolveTechnician(technicianIdOrMe, actor);
        accessPolicy.assertCanViewTechnicianSchedule(actor, tech);
        return auditRepository.findByTechnicianIdOrderByCreatedAtDesc(tech.getId())
                .stream()
                .limit(100)
                .map(a -> new ScheduleAuditResponse(
                        a.getId(),
                        a.getTechnicianId(),
                        a.getActorId(),
                        a.getAction(),
                        a.getScope(),
                        a.getEffectiveFrom(),
                        a.getEffectiveUntil(),
                        a.getDetailsJson(),
                        a.getCreatedAt()))
                .toList();
    }

    @Transactional
    public WeeklyScheduleOverviewResponse upsertWeeklySchedule(
            String technicianIdOrMe,
            ActorContext actor,
            String ifMatch,
            UpsertWeeklyScheduleRequest request) {
        Technician tech = resolveTechnician(technicianIdOrMe, actor);
        accessPolicy.assertCanManageTechnicianSchedule(actor, tech);
        tech = technicianRepository.lockById(tech.getId())
                .orElseThrow(() -> new DomainException(ProviderErrorCode.TECHNICIAN_NOT_FOUND));

        ZoneId zone = parseZone(request.timezone());
        LocalDate today = LocalDate.now(zone);
        if (request.effectiveFrom().isBefore(today)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Ngày bắt đầu hiệu lực (effectiveFrom) không được ở quá khứ.");
        }
        if (request.effectiveUntil() != null && request.effectiveUntil().isBefore(request.effectiveFrom())) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "effectiveUntil phải lớn hơn hoặc bằng effectiveFrom.");
        }
        validateWeeklyIntervals(request.intervals());

        List<WeeklyScheduleRule> existingRules = weeklyRuleRepository.findByTechnicianIdOrderByEffectiveFromAsc(tech.getId());
        WeeklyScheduleRule versionTarget = findLatestRule(existingRules);
        if (versionTarget != null) {
            EtagSupport.verifyIfMatch(ifMatch, versionTarget.getVersion());
        }

        // Build candidate rules after applying THIS_AND_FUTURE at request.effectiveFrom()
        Instant now = reconciliationService.now();
        List<WeeklyScheduleRule> candidateRules = new ArrayList<>();
        List<WeeklyScheduleRule> rulesToDelete = new ArrayList<>();
        List<WeeklyScheduleRule> rulesToClose = new ArrayList<>();
        WeeklyScheduleRule inPlaceRule = null;

        for (WeeklyScheduleRule r : existingRules) {
            if (!r.getEffectiveFrom().isBefore(request.effectiveFrom())) {
                // Rule starts on or after new effectiveFrom
                if (r.getEffectiveFrom().equals(request.effectiveFrom()) && inPlaceRule == null) {
                    inPlaceRule = r;
                } else {
                    rulesToDelete.add(r);
                }
            } else if (r.getEffectiveUntil() == null || !r.getEffectiveUntil().isBefore(request.effectiveFrom())) {
                // Prior rule overlaps effectiveFrom -> close it at effectiveFrom - 1 day
                rulesToClose.add(r);
                candidateRules.add(WeeklyScheduleRule.builder()
                        .id(r.getId())
                        .technicianId(tech.getId())
                        .timezone(r.getTimezone())
                        .effectiveFrom(r.getEffectiveFrom())
                        .effectiveUntil(request.effectiveFrom().minusDays(1))
                        .intervals(r.getIntervals())
                        .build());
            } else {
                candidateRules.add(r);
            }
        }

        WeeklyScheduleRule candidateNewRule = WeeklyScheduleRule.builder()
                .id(inPlaceRule != null ? inPlaceRule.getId() : UUID.randomUUID())
                .technicianId(tech.getId())
                .timezone(zone.getId())
                .effectiveFrom(request.effectiveFrom())
                .effectiveUntil(request.effectiveUntil())
                .createdAt(inPlaceRule != null ? inPlaceRule.getCreatedAt() : now)
                .updatedAt(now)
                .build();
        List<WeeklyScheduleRuleInterval> candidateIntervals = new ArrayList<>();
        for (DayTimeIntervalDto dto : request.intervals()) {
            candidateIntervals.add(WeeklyScheduleRuleInterval.builder()
                    .id(UUID.randomUUID())
                    .rule(candidateNewRule)
                    .dayOfWeek((short) dto.dayOfWeek().getValue())
                    .startTime(dto.startTime())
                    .endTime(dto.endTime())
                    .build());
        }
        candidateNewRule.setIntervals(candidateIntervals);
        candidateRules.add(candidateNewRule);

        LocalDate horizonEnd = today.plusDays(reconciliationService.getHorizonDays() - 1L);
        LocalDate conflictCheckEnd = request.effectiveUntil() != null && request.effectiveUntil().isAfter(horizonEnd)
                ? request.effectiveUntil()
                : horizonEnd;
        List<ScheduleException> exceptions = exceptionRepository
                .findByTechnicianIdAndLocalDateBetweenOrderByLocalDateAscStartTimeAsc(
                        tech.getId(), request.effectiveFrom(), conflictCheckEnd);

        boolean canViewBookingIds = accessPolicy.canViewScheduleBookingIds(actor, tech);
        List<ScheduleConflictItem> conflicts = reconciliationService.findBookingConflicts(
                tech.getId(), request.effectiveFrom(), conflictCheckEnd, candidateRules, exceptions, canViewBookingIds);
        if (!conflicts.isEmpty()) {
            throw new ScheduleAllocationConflictException(
                    "Lịch mới xung đột với " + conflicts.size() + " booking đã được phân bổ. Vui lòng đổi lịch, phân công lại hoặc hủy booking trước.",
                    conflicts);
        }

        // Persist rule changes (close/delete overlapping rules and flush BEFORE inserting new rule to satisfy EXCLUDE USING gist)
        if (!rulesToDelete.isEmpty()) {
            weeklyRuleRepository.deleteAll(rulesToDelete);
        }
        for (WeeklyScheduleRule r : rulesToClose) {
            r.setEffectiveUntil(request.effectiveFrom().minusDays(1));
            r.setUpdatedAt(now);
            weeklyRuleRepository.save(r);
        }
        if (inPlaceRule != null) {
            inPlaceRule.setTimezone(zone.getId());
            inPlaceRule.setEffectiveUntil(request.effectiveUntil());
            inPlaceRule.setUpdatedAt(now);
            inPlaceRule.getIntervals().clear();
            weeklyRuleRepository.saveAndFlush(inPlaceRule);
            for (DayTimeIntervalDto dto : request.intervals()) {
                inPlaceRule.getIntervals().add(WeeklyScheduleRuleInterval.builder()
                        .id(UUID.randomUUID())
                        .rule(inPlaceRule)
                        .dayOfWeek((short) dto.dayOfWeek().getValue())
                        .startTime(dto.startTime())
                        .endTime(dto.endTime())
                        .build());
            }
            weeklyRuleRepository.saveAndFlush(inPlaceRule);
        } else {
            weeklyRuleRepository.flush();
            weeklyRuleRepository.saveAndFlush(candidateNewRule);
        }

        reconciliationService.reconcileDateRange(tech, request.effectiveFrom(), horizonEnd, false);
        recordAudit(tech, actor, "WEEKLY_RULE_UPSERTED", "THIS_AND_FUTURE",
                request.effectiveFrom(), request.effectiveUntil(),
                Map.of("timezone", zone.getId(), "intervalCount", request.intervals().size()));

        List<WeeklyScheduleRule> updatedRules = weeklyRuleRepository.findByTechnicianIdOrderByEffectiveFromAsc(tech.getId());
        return toOverview(tech.getId(), updatedRules);
    }

    @Transactional
    public void endWeeklySchedule(
            String technicianIdOrMe,
            ActorContext actor,
            String ifMatch,
            LocalDate effectiveUntil) {
        Technician tech = resolveTechnician(technicianIdOrMe, actor);
        accessPolicy.assertCanManageTechnicianSchedule(actor, tech);
        tech = technicianRepository.lockById(tech.getId())
                .orElseThrow(() -> new DomainException(ProviderErrorCode.TECHNICIAN_NOT_FOUND));

        List<WeeklyScheduleRule> existingRules = weeklyRuleRepository.findByTechnicianIdOrderByEffectiveFromAsc(tech.getId());
        if (existingRules.isEmpty()) {
            return;
        }
        WeeklyScheduleRule latest = findLatestRule(existingRules);
        EtagSupport.verifyIfMatch(ifMatch, latest.getVersion());

        ZoneId zone = reconciliationService.resolveZone(existingRules);
        LocalDate today = LocalDate.now(zone);
        LocalDate cutDate = effectiveUntil != null ? effectiveUntil : today.minusDays(1);
        if (cutDate.isBefore(today.minusDays(1))) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Không thể kết thúc hồi tố lịch tuần trước ngày hôm nay.");
        }

        LocalDate checkFrom = cutDate.plusDays(1);
        LocalDate horizonEnd = today.plusDays(reconciliationService.getHorizonDays() - 1L);
        List<WeeklyScheduleRule> candidateRules = new ArrayList<>();
        for (WeeklyScheduleRule r : existingRules) {
            if (!r.getEffectiveFrom().isAfter(cutDate)) {
                LocalDate nextUntil = r.getEffectiveUntil() == null || r.getEffectiveUntil().isAfter(cutDate)
                        ? cutDate
                        : r.getEffectiveUntil();
                candidateRules.add(WeeklyScheduleRule.builder()
                        .id(r.getId())
                        .technicianId(tech.getId())
                        .timezone(r.getTimezone())
                        .effectiveFrom(r.getEffectiveFrom())
                        .effectiveUntil(nextUntil)
                        .intervals(r.getIntervals())
                        .build());
            }
        }
        List<ScheduleException> exceptions = exceptionRepository
                .findByTechnicianIdAndLocalDateBetweenOrderByLocalDateAscStartTimeAsc(
                        tech.getId(), checkFrom, horizonEnd.plusDays(60));
        List<ScheduleConflictItem> conflicts = reconciliationService.findBookingConflicts(
                tech.getId(), checkFrom, horizonEnd.plusDays(60), candidateRules, exceptions,
                accessPolicy.canViewScheduleBookingIds(actor, tech));
        if (!conflicts.isEmpty()) {
            throw new ScheduleAllocationConflictException(
                    "Không thể kết thúc lịch tuần vì còn " + conflicts.size() + " booking đã phân bổ sau ngày " + cutDate + ".",
                    conflicts);
        }

        Instant now = reconciliationService.now();
        for (WeeklyScheduleRule r : existingRules) {
            if (r.getEffectiveFrom().isAfter(cutDate)) {
                weeklyRuleRepository.delete(r);
            } else if (r.getEffectiveUntil() == null || r.getEffectiveUntil().isAfter(cutDate)) {
                r.setEffectiveUntil(cutDate);
                r.setUpdatedAt(now);
                weeklyRuleRepository.save(r);
            }
        }
        weeklyRuleRepository.flush();

        if (!checkFrom.isAfter(horizonEnd)) {
            reconciliationService.reconcileDateRange(tech, checkFrom, horizonEnd, false);
        }
        recordAudit(tech, actor, "WEEKLY_RULE_ENDED", "THIS_AND_FUTURE",
                checkFrom, cutDate, Map.of("effectiveUntil", cutDate.toString()));
    }

    @Transactional(readOnly = true)
    public List<ScheduleExceptionResponse> listExceptions(
            String technicianIdOrMe,
            ActorContext actor,
            LocalDate fromDate,
            LocalDate toDate) {
        Technician tech = resolveTechnician(technicianIdOrMe, actor);
        accessPolicy.assertCanViewTechnicianSchedule(actor, tech);
        List<WeeklyScheduleRule> rules = weeklyRuleRepository.findByTechnicianIdOrderByEffectiveFromAsc(tech.getId());
        ZoneId zone = reconciliationService.resolveZone(rules);
        LocalDate today = LocalDate.now(zone);
        LocalDate from = fromDate != null ? fromDate : today;
        LocalDate to = toDate != null ? toDate : today.plusDays(Math.max(90, reconciliationService.getHorizonDays()));
        return exceptionRepository
                .findByTechnicianIdAndLocalDateBetweenOrderByLocalDateAscStartTimeAsc(tech.getId(), from, to)
                .stream()
                .map(this::toExceptionResponse)
                .toList();
    }

    @Transactional
    public List<ScheduleExceptionResponse> upsertExceptions(
            String technicianIdOrMe,
            ActorContext actor,
            UpsertScheduleExceptionsRequest request) {
        Technician tech = resolveTechnician(technicianIdOrMe, actor);
        accessPolicy.assertCanManageTechnicianSchedule(actor, tech);
        tech = technicianRepository.lockById(tech.getId())
                .orElseThrow(() -> new DomainException(ProviderErrorCode.TECHNICIAN_NOT_FOUND));

        List<WeeklyScheduleRule> rules = weeklyRuleRepository.findByTechnicianIdOrderByEffectiveFromAsc(tech.getId());
        ZoneId zone = reconciliationService.resolveZone(rules);
        LocalDate today = LocalDate.now(zone);
        LocalDate fromDate = request.fromDate();
        LocalDate toDate = request.toDate() != null ? request.toDate() : fromDate;

        if (fromDate.isBefore(today)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Không được tạo hoặc sửa ngoại lệ cho ngày trong quá khứ.");
        }
        if (toDate.isBefore(fromDate)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "toDate phải lớn hơn hoặc bằng fromDate.");
        }
        long daySpan = ChronoUnit.DAYS.between(fromDate, toDate) + 1;
        if (daySpan > MAX_BULK_EXCEPTION_DAYS) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Mỗi thao tác ngoại lệ tối đa " + MAX_BULK_EXCEPTION_DAYS + " ngày.");
        }

        List<LocalTimeIntervalDto> intervals = request.intervals() == null ? List.of() : request.intervals();
        validateExceptionIntervals(request.exceptionType(), intervals);

        Instant now = reconciliationService.now();
        List<ScheduleException> newExceptions = new ArrayList<>();
        for (LocalDate d = fromDate; !d.isAfter(toDate); d = d.plusDays(1)) {
            if (request.exceptionType() == ScheduleExceptionType.UNAVAILABLE && intervals.isEmpty()) {
                newExceptions.add(ScheduleException.builder()
                        .id(UUID.randomUUID())
                        .technicianId(tech.getId())
                        .localDate(d)
                        .exceptionType(ScheduleExceptionType.UNAVAILABLE)
                        .reason(trimOrNull(request.reason()))
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
            } else {
                for (LocalTimeIntervalDto iv : intervals) {
                    newExceptions.add(ScheduleException.builder()
                            .id(UUID.randomUUID())
                            .technicianId(tech.getId())
                            .localDate(d)
                            .exceptionType(request.exceptionType())
                            .startTime(iv.startTime())
                            .endTime(iv.endTime())
                            .reason(trimOrNull(request.reason()))
                            .createdAt(now)
                            .updatedAt(now)
                            .build());
                }
            }
        }

        // Check conflicts atomically across all dates in [fromDate, toDate] BEFORE persisting (BR-SCH-025, BR-SCH-031)
        List<ScheduleConflictItem> conflicts = reconciliationService.findBookingConflicts(
                tech.getId(), fromDate, toDate, rules, newExceptions,
                accessPolicy.canViewScheduleBookingIds(actor, tech));
        if (!conflicts.isEmpty()) {
            throw new ScheduleAllocationConflictException(
                    "Ngoại lệ xung đột với " + conflicts.size() + " booking đã được phân bổ. Vui lòng xử lý booking trước.",
                    conflicts);
        }

        // Replace existing exceptions on the target dates atomically
        for (LocalDate d = fromDate; !d.isAfter(toDate); d = d.plusDays(1)) {
            exceptionRepository.deleteByTechnicianIdAndLocalDate(tech.getId(), d);
        }
        exceptionRepository.flush();
        List<ScheduleException> saved = exceptionRepository.saveAllAndFlush(newExceptions);

        boolean applyUnavailableToManual = request.exceptionType() == ScheduleExceptionType.UNAVAILABLE;
        reconciliationService.reconcileDateRange(tech, fromDate, toDate, applyUnavailableToManual);

        recordAudit(tech, actor, "EXCEPTION_UPSERTED",
                daySpan > 1 ? "BULK_DATES" : "THIS_DATE_ONLY",
                fromDate, toDate,
                Map.of("exceptionType", request.exceptionType().name(), "days", daySpan));

        return saved.stream().map(this::toExceptionResponse).toList();
    }

    @Transactional
    public void deleteExceptionById(String technicianIdOrMe, ActorContext actor, UUID exceptionId, String ifMatch) {
        Technician tech = resolveTechnician(technicianIdOrMe, actor);
        accessPolicy.assertCanManageTechnicianSchedule(actor, tech);
        tech = technicianRepository.lockById(tech.getId())
                .orElseThrow(() -> new DomainException(ProviderErrorCode.TECHNICIAN_NOT_FOUND));

        ScheduleException ex = exceptionRepository.findById(exceptionId).orElse(null);
        if (ex == null) {
            return;
        }
        if (!ex.getTechnicianId().equals(tech.getId())) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        EtagSupport.verifyIfMatch(ifMatch, ex.getVersion());
        LocalDate date = ex.getLocalDate();
        List<WeeklyScheduleRule> rules = weeklyRuleRepository.findByTechnicianIdOrderByEffectiveFromAsc(tech.getId());
        ZoneId zone = reconciliationService.resolveZone(rules);
        LocalDate today = LocalDate.ofInstant(reconciliationService.now(), zone);
        if (date.isBefore(today)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Không thể xóa ngoại lệ lịch làm việc trong quá khứ.");
        }
        List<ScheduleException> remaining = exceptionRepository
                .findByTechnicianIdAndLocalDateOrderByStartTimeAsc(tech.getId(), date)
                .stream()
                .filter(e -> !e.getId().equals(ex.getId()))
                .toList();
        List<ScheduleConflictItem> conflicts = reconciliationService.findBookingConflicts(
                tech.getId(), date, date, rules, remaining, accessPolicy.canViewScheduleBookingIds(actor, tech));
        if (!conflicts.isEmpty()) {
            throw new ScheduleAllocationConflictException(
                    "Không thể xóa ngoại lệ vì có " + conflicts.size() + " booking đã phân công phụ thuộc khung giờ này.",
                    conflicts);
        }

        exceptionRepository.delete(ex);
        exceptionRepository.flush();

        reconciliationService.reconcileDateRange(tech, date, date, false);
        recordAudit(tech, actor, "EXCEPTION_DELETED", "THIS_DATE_ONLY", date, date,
                Map.of("exceptionId", exceptionId.toString()));
    }

    @Transactional
    public void deleteExceptionsByDate(String technicianIdOrMe, ActorContext actor, LocalDate date) {
        Technician tech = resolveTechnician(technicianIdOrMe, actor);
        accessPolicy.assertCanManageTechnicianSchedule(actor, tech);
        tech = technicianRepository.lockById(tech.getId())
                .orElseThrow(() -> new DomainException(ProviderErrorCode.TECHNICIAN_NOT_FOUND));

        List<WeeklyScheduleRule> rules = weeklyRuleRepository.findByTechnicianIdOrderByEffectiveFromAsc(tech.getId());
        ZoneId zone = reconciliationService.resolveZone(rules);
        LocalDate today = LocalDate.ofInstant(reconciliationService.now(), zone);
        if (date.isBefore(today)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Không thể xóa ngoại lệ lịch làm việc trong quá khứ.");
        }
        List<ScheduleConflictItem> conflicts = reconciliationService.findBookingConflicts(
                tech.getId(), date, date, rules, List.of(), accessPolicy.canViewScheduleBookingIds(actor, tech));
        if (!conflicts.isEmpty()) {
            throw new ScheduleAllocationConflictException(
                    "Không thể xóa ngoại lệ ngày " + date + " vì có " + conflicts.size() + " booking đã phân công.",
                    conflicts);
        }

        exceptionRepository.deleteByTechnicianIdAndLocalDate(tech.getId(), date);
        exceptionRepository.flush();

        reconciliationService.reconcileDateRange(tech, date, date, false);
        recordAudit(tech, actor, "EXCEPTION_DELETED", "THIS_DATE_ONLY", date, date,
                Map.of("localDate", date.toString()));
    }

    public void recordAudit(
            Technician tech,
            ActorContext actor,
            String action,
            String scope,
            LocalDate effectiveFrom,
            LocalDate effectiveUntil,
            Map<String, Object> details) {
        Instant now = recordAuditOnly(tech, actor, action, scope, effectiveFrom, effectiveUntil, details);
        enqueueScheduleUpdatedEvent(tech, action, effectiveFrom, effectiveUntil, now);
    }

    public Instant recordAuditOnly(
            Technician tech,
            ActorContext actor,
            String action,
            String scope,
            LocalDate effectiveFrom,
            LocalDate effectiveUntil,
            Map<String, Object> details) {
        String json;
        try {
            json = objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            json = "{}";
        }
        Instant now = reconciliationService.now();
        auditRepository.save(TechnicianScheduleAudit.builder()
                .id(UUID.randomUUID())
                .technicianId(tech.getId())
                .organizationId(tech.getProviderId())
                .actorId(actor != null ? actor.userId() : "SYSTEM")
                .action(action)
                .scope(scope)
                .effectiveFrom(effectiveFrom)
                .effectiveUntil(effectiveUntil)
                .detailsJson(json)
                .createdAt(now)
                .build());
        return now;
    }

    private void enqueueScheduleUpdatedEvent(
            Technician tech,
            String action,
            LocalDate effectiveFrom,
            LocalDate effectiveUntil,
            Instant now) {
        if (tech.getProfileId() == null || tech.getProfileId().isBlank()) {
            return;
        }
        UUID eventId = UUID.randomUUID();
        String summary = buildScheduleUpdatedSummary(action, effectiveFrom, effectiveUntil);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("technicianId", tech.getId().toString());
        data.put("technicianProfileId", tech.getProfileId());
        data.put("organizationId", tech.getProviderId().toString());
        data.put("action", action);
        if (effectiveFrom != null) {
            data.put("effectiveFrom", effectiveFrom.toString());
        }
        if (effectiveUntil != null) {
            data.put("effectiveUntil", effectiveUntil.toString());
        }
        data.put("summary", summary);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(DomainEventContracts.FIELD_EVENT_ID, eventId.toString());
        envelope.put(DomainEventContracts.FIELD_EVENT_TYPE, DomainEventContracts.PROVIDER_SCHEDULE_UPDATED);
        envelope.put(DomainEventContracts.FIELD_OCCURRED_AT, now.toString());
        envelope.put(DomainEventContracts.FIELD_PRODUCER, "provider-service");
        envelope.put(DomainEventContracts.FIELD_DATA, data);
        try {
            outboxRepository.save(ProviderOutboxEventEntity.builder()
                    .id(eventId)
                    .aggregateType("TechnicianSchedule")
                    .aggregateId(tech.getId())
                    .eventType(DomainEventContracts.PROVIDER_SCHEDULE_UPDATED)
                    .routingKey(DomainEventContracts.PROVIDER_SCHEDULE_UPDATED)
                    .payloadJson(objectMapper.writeValueAsString(envelope))
                    .status(EntityStatus.PENDING.name())
                    .retryCount(0)
                    .nextAttemptAt(now)
                    .createdAt(now)
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize schedule updated outbox event.", ex);
        }
    }

    private String buildScheduleUpdatedSummary(String action, LocalDate from, LocalDate until) {
        String range = from == null ? "" : (until == null || from.equals(until) ? " (" + from + ")" : " (" + from + " → " + until + ")");
        return switch (action) {
            case "WEEKLY_RULE_UPSERTED", "WEEKLY_SCHEDULE_UPSERTED" -> "Tổ chức vừa cập nhật lịch làm việc định kỳ của bạn" + range + ".";
            case "WEEKLY_RULE_ENDED", "WEEKLY_SCHEDULE_ENDED" -> "Tổ chức vừa kết thúc hiệu lực lịch làm việc định kỳ của bạn" + range + ".";
            case "EXCEPTION_UPSERTED" -> "Tổ chức vừa cập nhật ngoại lệ lịch làm việc của bạn" + range + ".";
            case "EXCEPTION_DELETED" -> "Tổ chức vừa gỡ bỏ ngoại lệ lịch làm việc của bạn" + range + ".";
            default -> "Tổ chức vừa thay đổi lịch làm việc của bạn" + range + ".";
        };
    }

    private Technician resolveTechnician(String technicianIdOrMe, ActorContext actor) {
        if ("me".equalsIgnoreCase(technicianIdOrMe)) {
            if (actor == null || actor.userId() == null) {
                throw new DomainException(ProviderErrorCode.TECHNICIAN_NOT_FOUND);
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

    private ZoneId parseZone(String rawZone) {
        String value = (rawZone == null || rawZone.isBlank())
                ? ScheduleReconciliationService.DEFAULT_TIMEZONE
                : rawZone.trim();
        try {
            return ZoneId.of(value);
        } catch (DateTimeException ex) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Timezone không hợp lệ: " + value);
        }
    }

    private void validateWeeklyIntervals(List<DayTimeIntervalDto> intervals) {
        Map<DayOfWeek, List<DayTimeIntervalDto>> byDay = new EnumMap<>(DayOfWeek.class);
        for (DayTimeIntervalDto iv : intervals) {
            if (!iv.endTime().isAfter(iv.startTime())) {
                throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                        "Giờ kết thúc phải lớn hơn giờ bắt đầu (" + iv.dayOfWeek() + ").");
            }
            byDay.computeIfAbsent(iv.dayOfWeek(), k -> new ArrayList<>()).add(iv);
        }
        for (Map.Entry<DayOfWeek, List<DayTimeIntervalDto>> entry : byDay.entrySet()) {
            List<DayTimeIntervalDto> list = entry.getValue();
            list.sort(Comparator.comparing(DayTimeIntervalDto::startTime));
            for (int i = 1; i < list.size(); i++) {
                if (list.get(i).startTime().isBefore(list.get(i - 1).endTime())) {
                    throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                            "Các khung giờ trong cùng ngày " + entry.getKey() + " không được chồng lấn.");
                }
            }
        }
    }

    private void validateExceptionIntervals(ScheduleExceptionType type, List<LocalTimeIntervalDto> intervals) {
        if (type != ScheduleExceptionType.UNAVAILABLE && intervals.isEmpty()) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    type + " yêu cầu ít nhất một khoảng giờ hợp lệ.");
        }
        List<LocalTimeIntervalDto> sorted = new ArrayList<>(intervals);
        for (LocalTimeIntervalDto iv : sorted) {
            if (!iv.endTime().isAfter(iv.startTime())) {
                throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                        "Giờ kết thúc phải lớn hơn giờ bắt đầu.");
            }
        }
        sorted.sort(Comparator.comparing(LocalTimeIntervalDto::startTime));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).startTime().isBefore(sorted.get(i - 1).endTime())) {
                throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                        "Các khoảng giờ ngoại lệ không được chồng lấn.");
            }
        }
    }

    private WeeklyScheduleRule findLatestRule(List<WeeklyScheduleRule> rules) {
        return rules == null || rules.isEmpty() ? null : rules.getLast();
    }

    private WeeklyScheduleOverviewResponse toOverview(UUID technicianId, List<WeeklyScheduleRule> rules) {
        ZoneId zone = reconciliationService.resolveZone(rules);
        LocalDate today = LocalDate.now(zone);
        WeeklyScheduleRuleResponse current = null;
        List<WeeklyScheduleRuleResponse> upcoming = new ArrayList<>();
        for (WeeklyScheduleRule r : rules) {
            WeeklyScheduleRuleResponse dto = toRuleResponse(r);
            boolean activeToday = !today.isBefore(r.getEffectiveFrom())
                    && (r.getEffectiveUntil() == null || !today.isAfter(r.getEffectiveUntil()));
            if (activeToday) {
                current = dto;
            } else if (r.getEffectiveFrom().isAfter(today)) {
                upcoming.add(dto);
            }
        }
        return new WeeklyScheduleOverviewResponse(
                technicianId,
                reconciliationService.getHorizonDays(),
                current,
                upcoming);
    }

    private WeeklyScheduleRuleResponse toRuleResponse(WeeklyScheduleRule r) {
        List<DayTimeIntervalDto> ivs = r.getIntervals().stream()
                .map(iv -> new DayTimeIntervalDto(
                        DayOfWeek.of(iv.getDayOfWeek()),
                        iv.getStartTime(),
                        iv.getEndTime()))
                .toList();
        return new WeeklyScheduleRuleResponse(
                r.getId(),
                r.getTimezone(),
                r.getEffectiveFrom(),
                r.getEffectiveUntil(),
                r.getVersion(),
                ivs);
    }

    private ScheduleExceptionResponse toExceptionResponse(ScheduleException ex) {
        return new ScheduleExceptionResponse(
                ex.getId(),
                ex.getTechnicianId(),
                ex.getLocalDate(),
                ex.getExceptionType(),
                ex.getStartTime(),
                ex.getEndTime(),
                ex.getReason(),
                ex.getVersion());
    }

    private String trimOrNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
