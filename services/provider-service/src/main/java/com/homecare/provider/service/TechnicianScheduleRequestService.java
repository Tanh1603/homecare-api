package com.homecare.provider.service;

import com.homecare.common.domain.EntityStatus;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.common.security.ActorContext;
import com.homecare.provider.dto.RecurringScheduleDtos.CreateScheduleChangeRequest;
import com.homecare.provider.dto.RecurringScheduleDtos.LocalTimeIntervalDto;
import com.homecare.provider.dto.RecurringScheduleDtos.ReviewScheduleChangeRequest;
import com.homecare.provider.dto.RecurringScheduleDtos.ScheduleChangeRequestResponse;
import com.homecare.provider.dto.RecurringScheduleDtos.UpsertScheduleExceptionsRequest;
import com.homecare.provider.entity.ProviderEntity;
import com.homecare.provider.entity.ScheduleChangeRequestStatus;
import com.homecare.provider.entity.ScheduleChangeRequestType;
import com.homecare.provider.entity.ScheduleExceptionType;
import com.homecare.provider.entity.Technician;
import com.homecare.provider.entity.TechnicianScheduleChangeRequest;
import com.homecare.provider.entity.WeeklyScheduleRule;
import com.homecare.provider.exception.ProviderErrorCode;
import com.homecare.provider.organization.entity.OrgRole;
import com.homecare.provider.organization.entity.OrganizationMemberEntity;
import com.homecare.provider.organization.entity.ProviderOutboxEventEntity;
import com.homecare.provider.organization.repository.OrganizationMemberRepository;
import com.homecare.provider.organization.repository.ProviderOutboxEventRepository;
import com.homecare.provider.repository.ProviderRepository;
import com.homecare.provider.repository.TechnicianRepository;
import com.homecare.provider.repository.TechnicianScheduleChangeRequestRepository;
import com.homecare.provider.repository.WeeklyScheduleRuleRepository;
import com.homecare.provider.security.ProviderAccessPolicy;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Manages technician leave, shift-change, and extra-hours requests with Org Admin/Owner review
 * and atomic exception application + outbox notifications (Phase 6 & 7).
 */
@Service
@RequiredArgsConstructor
public class TechnicianScheduleRequestService {

    private static final int MAX_REQUEST_SPAN_DAYS = 30;
    private static final String ERROR_REQUEST_NOT_FOUND = "SCHEDULE_REQUEST_NOT_FOUND";
    private static final String ERROR_REQUEST_OVERLAP = "SCHEDULE_REQUEST_OVERLAP";
    private static final String ERROR_REQUEST_NOT_PENDING = "SCHEDULE_REQUEST_NOT_PENDING";

    private final TechnicianScheduleChangeRequestRepository requestRepository;
    private final TechnicianRepository technicianRepository;
    private final ProviderRepository providerRepository;
    private final OrganizationMemberRepository organizationMemberRepository;
    private final WeeklyScheduleRuleRepository weeklyRuleRepository;
    private final ScheduleReconciliationService reconciliationService;
    private final TechnicianRecurringScheduleService recurringScheduleService;
    private final ProviderOutboxEventRepository outboxRepository;
    private final ProviderAccessPolicy accessPolicy;
    private final ObjectMapper objectMapper;

    @Transactional
    public ScheduleChangeRequestResponse submitMyRequest(
            ActorContext actor,
            CreateScheduleChangeRequest input) {
        Technician tech = resolveMyActiveTechnician(actor);
        List<WeeklyScheduleRule> rules = weeklyRuleRepository.findByTechnicianIdOrderByEffectiveFromAsc(tech.getId());
        ZoneId zone = reconciliationService.resolveZone(rules);
        LocalDate today = LocalDate.now(zone);

        LocalDate fromDate = input.fromDate();
        LocalDate toDate = input.toDate() != null ? input.toDate() : fromDate;
        if (fromDate.isBefore(today)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Không thể gửi yêu cầu cho ngày trong quá khứ.");
        }
        if (toDate.isBefore(fromDate)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Ngày kết thúc phải lớn hơn hoặc bằng ngày bắt đầu.");
        }
        long span = ChronoUnit.DAYS.between(fromDate, toDate) + 1;
        if (span > MAX_REQUEST_SPAN_DAYS) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Mỗi yêu cầu chỉ được áp dụng tối đa " + MAX_REQUEST_SPAN_DAYS + " ngày.");
        }
        validateRequestedTimes(input);

        technicianRepository.lockById(tech.getId());
        if (requestRepository.existsOverlappingPending(tech.getId(), fromDate, toDate)) {
            throw new DomainException(
                    HttpStatus.CONFLICT,
                    ERROR_REQUEST_OVERLAP,
                    "Bạn đã có một yêu cầu đang chờ duyệt trùng khoảng ngày này.");
        }

        Instant now = reconciliationService.now();
        TechnicianScheduleChangeRequest saved = requestRepository.saveAndFlush(
                TechnicianScheduleChangeRequest.builder()
                        .id(UUID.randomUUID())
                        .technicianId(tech.getId())
                        .organizationId(tech.getProviderId())
                        .requestType(input.requestType())
                        .fromDate(fromDate)
                        .toDate(toDate)
                        .requestedStartTime(input.requestedStartTime())
                        .requestedEndTime(input.requestedEndTime())
                        .reason(trimOrNull(input.reason()))
                        .status(ScheduleChangeRequestStatus.PENDING)
                        .createdAt(now)
                        .updatedAt(now)
                        .build());

        enqueueRequestCreatedEvent(saved, tech, now);
        return toResponse(saved, tech.getFullName());
    }

    @Transactional(readOnly = true)
    public List<ScheduleChangeRequestResponse> listMyRequests(ActorContext actor, String rawStatus) {
        Technician tech = resolveMyActiveTechnician(actor);
        ScheduleChangeRequestStatus status = parseStatusFilter(rawStatus);
        List<TechnicianScheduleChangeRequest> list = status == null
                ? requestRepository.findByTechnicianIdOrderByCreatedAtDesc(tech.getId())
                : requestRepository.findByTechnicianIdAndStatusOrderByCreatedAtDesc(tech.getId(), status);
        return list.stream()
                .map(r -> toResponse(r, tech.getFullName()))
                .toList();
    }

    @Transactional
    public ScheduleChangeRequestResponse cancelMyRequest(ActorContext actor, UUID requestId) {
        Technician tech = resolveMyActiveTechnician(actor);
        TechnicianScheduleChangeRequest req = requestRepository.findById(requestId)
                .orElseThrow(() -> new DomainException(
                        HttpStatus.NOT_FOUND, ERROR_REQUEST_NOT_FOUND, "Không tìm thấy yêu cầu điều chỉnh lịch."));
        if (!req.getTechnicianId().equals(tech.getId())) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        if (req.getStatus() != ScheduleChangeRequestStatus.PENDING) {
            throw new DomainException(
                    HttpStatus.CONFLICT,
                    ERROR_REQUEST_NOT_PENDING,
                    "Chỉ có thể hủy yêu cầu đang ở trạng thái chờ duyệt (PENDING).");
        }
        Instant now = reconciliationService.now();
        req.setStatus(ScheduleChangeRequestStatus.CANCELLED);
        req.setUpdatedAt(now);
        TechnicianScheduleChangeRequest saved = requestRepository.saveAndFlush(req);
        return toResponse(saved, tech.getFullName());
    }

    @Transactional(readOnly = true)
    public List<ScheduleChangeRequestResponse> listOrganizationRequests(
            UUID organizationId,
            String rawStatus,
            ActorContext actor) {
        accessPolicy.assertCanViewOrganizationCalendar(actor, organizationId);
        ScheduleChangeRequestStatus status = parseStatusFilter(rawStatus);
        List<TechnicianScheduleChangeRequest> items = status == null
                ? requestRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId)
                : requestRepository.findByOrganizationIdAndStatusOrderByCreatedAtDesc(organizationId, status);
        if (items.isEmpty()) {
            return List.of();
        }
        Set<UUID> techIds = items.stream()
                .map(TechnicianScheduleChangeRequest::getTechnicianId)
                .collect(Collectors.toSet());
        Map<UUID, String> namesById = technicianRepository.findAllById(techIds).stream()
                .collect(Collectors.toMap(Technician::getId, Technician::getFullName));
        return items.stream()
                .map(r -> toResponse(r, namesById.getOrDefault(r.getTechnicianId(), "")))
                .toList();
    }

    @Transactional
    public ScheduleChangeRequestResponse reviewRequest(
            UUID organizationId,
            UUID requestId,
            ReviewScheduleChangeRequest input,
            ActorContext actor) {
        TechnicianScheduleChangeRequest req = requestRepository.findById(requestId)
                .orElseThrow(() -> new DomainException(
                        HttpStatus.NOT_FOUND, ERROR_REQUEST_NOT_FOUND, "Không tìm thấy yêu cầu điều chỉnh lịch."));
        if (!organizationId.equals(req.getOrganizationId())) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        Technician tech = technicianRepository.lockById(req.getTechnicianId())
                .orElseThrow(() -> new DomainException(ProviderErrorCode.TECHNICIAN_NOT_FOUND));
        accessPolicy.assertCanManageTechnicianSchedule(actor, tech);

        if (req.getStatus() != ScheduleChangeRequestStatus.PENDING) {
            throw new DomainException(
                    HttpStatus.CONFLICT,
                    ERROR_REQUEST_NOT_PENDING,
                    "Yêu cầu này đã được xử lý trước đó.");
        }

        boolean approve = Boolean.TRUE.equals(input.approve());
        String note = trimOrNull(input.reviewNote());
        if (!approve && note == null) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Vui lòng nhập lý do khi từ chối yêu cầu của kỹ thuật viên.");
        }

        Instant now = reconciliationService.now();
        if (approve) {
            ScheduleExceptionType exceptionType = mapToExceptionType(req.getRequestType());
            List<LocalTimeIntervalDto> intervals =
                    (req.getRequestedStartTime() != null && req.getRequestedEndTime() != null)
                            ? List.of(new LocalTimeIntervalDto(req.getRequestedStartTime(), req.getRequestedEndTime()))
                            : List.of();
            String exceptionReason = note != null
                    ? note
                    : (req.getReason() != null ? req.getReason() : "Duyệt yêu cầu từ KTV");
            // Atomically validates booking conflicts (throws 409 SCHEDULE_ALLOCATION_CONFLICT) & reconciles windows
            recurringScheduleService.upsertExceptions(
                    tech.getId().toString(),
                    actor,
                    new UpsertScheduleExceptionsRequest(
                            req.getFromDate(),
                            req.getToDate(),
                            exceptionType,
                            exceptionReason,
                            intervals));
            req.setStatus(ScheduleChangeRequestStatus.APPROVED);
        } else {
            req.setStatus(ScheduleChangeRequestStatus.REJECTED);
            recurringScheduleService.recordAuditOnly(
                    tech,
                    actor,
                    "SCHEDULE_REQUEST_REJECTED",
                    req.getFromDate().equals(req.getToDate()) ? "THIS_DATE_ONLY" : "BULK_DATES",
                    req.getFromDate(),
                    req.getToDate(),
                    Map.of("requestId", req.getId().toString(), "reviewNote", note));
        }

        req.setReviewedBy(actor.userId());
        req.setReviewNote(note);
        req.setReviewedAt(now);
        req.setUpdatedAt(now);
        TechnicianScheduleChangeRequest saved = requestRepository.saveAndFlush(req);
        enqueueRequestReviewedEvent(saved, tech, now);
        return toResponse(saved, tech.getFullName());
    }

    private void validateRequestedTimes(CreateScheduleChangeRequest input) {
        boolean hasStart = input.requestedStartTime() != null;
        boolean hasEnd = input.requestedEndTime() != null;
        if (hasStart != hasEnd) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Cần nhập đầy đủ cả giờ bắt đầu và giờ kết thúc.");
        }
        if (input.requestType() != ScheduleChangeRequestType.LEAVE && !hasStart) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Yêu cầu đổi ca hoặc thêm giờ bắt buộc phải có khung giờ cụ thể.");
        }
        if (hasStart && !input.requestedEndTime().isAfter(input.requestedStartTime())) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Giờ kết thúc phải lớn hơn giờ bắt đầu.");
        }
    }

    private ScheduleExceptionType mapToExceptionType(ScheduleChangeRequestType type) {
        return switch (type) {
            case LEAVE -> ScheduleExceptionType.UNAVAILABLE;
            case SHIFT_CHANGE -> ScheduleExceptionType.CUSTOM_HOURS;
            case EXTRA_HOURS -> ScheduleExceptionType.EXTRA_HOURS;
        };
    }

    private Technician resolveMyActiveTechnician(ActorContext actor) {
        if (actor == null || actor.userId() == null || actor.userId().isBlank()) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        Technician tech = technicianRepository.findByProfileId(actor.userId())
                .orElseThrow(() -> new DomainException(ProviderErrorCode.TECHNICIAN_NOT_FOUND));
        if (!EntityStatus.ACTIVE.name().equalsIgnoreCase(tech.getStatus())) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN,
                    "Tài khoản kỹ thuật viên hiện không hoạt động.");
        }
        return tech;
    }

    private ScheduleChangeRequestStatus parseStatusFilter(String raw) {
        if (raw == null || raw.isBlank() || "ALL".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        try {
            return ScheduleChangeRequestStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Trạng thái yêu cầu không hợp lệ.");
        }
    }

    private void enqueueRequestCreatedEvent(
            TechnicianScheduleChangeRequest req,
            Technician tech,
            Instant now) {
        Set<String> recipients = new LinkedHashSet<>();
        List<OrganizationMemberEntity> members = organizationMemberRepository
                .findByOrganizationIdOrderByJoinedAtAsc(req.getOrganizationId());
        for (OrganizationMemberEntity m : members) {
            if (EntityStatus.ACTIVE.name().equalsIgnoreCase(m.getStatus())
                    && (m.getRole() == OrgRole.ORG_OWNER || m.getRole() == OrgRole.ORG_ADMIN)) {
                recipients.add(m.getUserId());
            }
        }
        providerRepository.findById(req.getOrganizationId())
                .map(ProviderEntity::getOwnerProfileId)
                .filter(id -> id != null && !id.isBlank())
                .ifPresent(recipients::add);

        if (recipients.isEmpty()) {
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", req.getId().toString());
        data.put("technicianId", tech.getId().toString());
        data.put("technicianName", tech.getFullName());
        data.put("organizationId", req.getOrganizationId().toString());
        data.put("requestType", req.getRequestType().name());
        data.put("fromDate", req.getFromDate().toString());
        data.put("toDate", req.getToDate().toString());
        data.put("recipientProfileIds", List.copyOf(recipients));
        saveOutbox(req.getId(), DomainEventContracts.PROVIDER_SCHEDULE_REQUEST_CREATED, data, now);
    }

    private void enqueueRequestReviewedEvent(
            TechnicianScheduleChangeRequest req,
            Technician tech,
            Instant now) {
        if (tech.getProfileId() == null || tech.getProfileId().isBlank()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", req.getId().toString());
        data.put("technicianId", tech.getId().toString());
        data.put("technicianProfileId", tech.getProfileId());
        data.put("organizationId", req.getOrganizationId().toString());
        data.put("requestType", req.getRequestType().name());
        data.put("fromDate", req.getFromDate().toString());
        data.put("toDate", req.getToDate().toString());
        data.put("status", req.getStatus().name());
        if (req.getReviewNote() != null) {
            data.put("reviewNote", req.getReviewNote());
        }
        saveOutbox(req.getId(), DomainEventContracts.PROVIDER_SCHEDULE_REQUEST_REVIEWED, data, now);
    }

    private void saveOutbox(UUID aggregateId, String eventType, Map<String, Object> data, Instant now) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(DomainEventContracts.FIELD_EVENT_ID, eventId.toString());
        envelope.put(DomainEventContracts.FIELD_EVENT_TYPE, eventType);
        envelope.put(DomainEventContracts.FIELD_OCCURRED_AT, now.toString());
        envelope.put(DomainEventContracts.FIELD_PRODUCER, "provider-service");
        envelope.put(DomainEventContracts.FIELD_DATA, data);
        try {
            outboxRepository.save(ProviderOutboxEventEntity.builder()
                    .id(eventId)
                    .aggregateType("TechnicianScheduleChangeRequest")
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .routingKey(eventType)
                    .payloadJson(objectMapper.writeValueAsString(envelope))
                    .status(EntityStatus.PENDING.name())
                    .retryCount(0)
                    .nextAttemptAt(now)
                    .createdAt(now)
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize schedule request outbox event.", ex);
        }
    }

    private ScheduleChangeRequestResponse toResponse(
            TechnicianScheduleChangeRequest r,
            String technicianName) {
        return new ScheduleChangeRequestResponse(
                r.getId(),
                r.getTechnicianId(),
                technicianName,
                r.getOrganizationId(),
                r.getRequestType(),
                r.getFromDate(),
                r.getToDate(),
                r.getRequestedStartTime(),
                r.getRequestedEndTime(),
                r.getReason(),
                r.getStatus(),
                r.getReviewedBy(),
                r.getReviewNote(),
                r.getReviewedAt(),
                r.getVersion(),
                r.getCreatedAt());
    }

    private String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
