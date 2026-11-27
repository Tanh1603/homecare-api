package com.homecare.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homecare.common.error.DomainException;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.common.security.RbacConstants;
import com.homecare.provider.dto.RecurringScheduleDtos.CreateScheduleChangeRequest;
import com.homecare.provider.dto.RecurringScheduleDtos.DayTimeIntervalDto;
import com.homecare.provider.dto.RecurringScheduleDtos.ReviewScheduleChangeRequest;
import com.homecare.provider.dto.RecurringScheduleDtos.ScheduleChangeRequestResponse;
import com.homecare.provider.dto.RecurringScheduleDtos.UpsertWeeklyScheduleRequest;
import com.homecare.provider.entity.ProviderEntity;
import com.homecare.provider.entity.ScheduleChangeRequestStatus;
import com.homecare.provider.entity.ScheduleChangeRequestType;
import com.homecare.provider.entity.ScheduleWindow;
import com.homecare.provider.entity.ScheduleWindowStatus;
import com.homecare.provider.entity.Technician;
import com.homecare.provider.organization.entity.OrgRole;
import com.homecare.provider.organization.repository.ProviderOutboxEventRepository;
import com.homecare.provider.repository.ProviderRepository;
import com.homecare.provider.repository.ScheduleWindowRepository;
import com.homecare.provider.repository.TechnicianRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class TechnicianScheduleRequestServiceTest {

    @Autowired
    private TechnicianScheduleRequestService requestService;

    @Autowired
    private TechnicianRecurringScheduleService recurringService;

    @Autowired
    private TechnicianCalendarService calendarService;

    @Autowired
    private ProviderRepository providerRepository;

    @Autowired
    private TechnicianRepository technicianRepository;

    @Autowired
    private ScheduleWindowRepository scheduleWindowRepository;

    @Autowired
    private ProviderOutboxEventRepository outboxRepository;

    private Technician seedOrgAndTech(String tag) {
        UUID orgId = UUID.randomUUID();
        providerRepository.saveAndFlush(ProviderEntity.builder()
                .id(orgId)
                .organizationId(orgId)
                .ownerProfileId("owner-" + tag)
                .name("Org " + tag)
                .city("HCM")
                .phone("0901234567")
                .ratingAvg(4.9)
                .status("ACTIVE")
                .createdAt(Instant.now())
                .build());
        return technicianRepository.saveAndFlush(Technician.builder()
                .id(UUID.randomUUID())
                .profileId("tech-" + tag + "-" + UUID.randomUUID())
                .providerId(orgId)
                .fullName("KTV " + tag)
                .specialties("AC")
                .experienceYears(2)
                .ratingAvg(4.8)
                .activeAllocations(0)
                .status("ACTIVE")
                .isOnline(true)
                .createdAt(Instant.now())
                .build());
    }

    private ActorContext techActor(Technician tech) {
        return new ActorContext(
                tech.getProfileId(),
                AccessMode.PERSONAL,
                null,
                Set.of(RbacConstants.ROLE_TECHNICIAN),
                Set.of(),
                Set.of(),
                Set.of());
    }

    private ActorContext orgAdmin(UUID orgId) {
        return new ActorContext(
                "admin-" + orgId,
                AccessMode.ORGANIZATION,
                orgId,
                Set.of(RbacConstants.ROLE_ORGANIZATION),
                Set.of(),
                Set.of(OrgRole.ORG_ADMIN.name()),
                Set.of(RbacConstants.PERM_SCHEDULE_MANAGE));
    }

    private ActorContext orgStaff(UUID orgId) {
        return new ActorContext(
                "staff-" + orgId,
                AccessMode.ORGANIZATION,
                orgId,
                Set.of(RbacConstants.ROLE_ORGANIZATION),
                Set.of(),
                Set.of(OrgRole.ORG_STAFF.name()),
                Set.of());
    }

    @Test
    void submitApproveAppliesExceptionNotifiesAndBlocksOverlapOrStaffReview() {
        Technician tech = seedOrgAndTech("ApproveFlow");
        ActorContext techCtx = techActor(tech);
        ActorContext adminCtx = orgAdmin(tech.getProviderId());
        ActorContext staffCtx = orgStaff(tech.getProviderId());

        LocalDate target = LocalDate.now(ZoneId.of(ScheduleReconciliationService.DEFAULT_TIMEZONE)).plusDays(5);
        recurringService.upsertWeeklySchedule(
                tech.getId().toString(),
                adminCtx,
                null,
                new UpsertWeeklyScheduleRequest(
                        ScheduleReconciliationService.DEFAULT_TIMEZONE,
                        target,
                        target.plusDays(3),
                        List.of(new DayTimeIntervalDto(target.getDayOfWeek(), LocalTime.of(8, 0), LocalTime.of(17, 0)))));

        // Phase 6: Org schedule update emits PROVIDER_SCHEDULE_UPDATED outbox event
        assertThat(outboxRepository.findAll())
                .anyMatch(e -> DomainEventContracts.PROVIDER_SCHEDULE_UPDATED.equals(e.getEventType())
                        && e.getPayloadJson().contains(tech.getProfileId()));

        // Phase 7: Technician submits LEAVE request -> emits PROVIDER_SCHEDULE_REQUEST_CREATED
        ScheduleChangeRequestResponse pending = requestService.submitMyRequest(
                techCtx,
                new CreateScheduleChangeRequest(ScheduleChangeRequestType.LEAVE, target, target, null, null, "Bận việc gia đình"));
        assertThat(pending.status()).isEqualTo(ScheduleChangeRequestStatus.PENDING);
        assertThat(outboxRepository.findAll())
                .anyMatch(e -> DomainEventContracts.PROVIDER_SCHEDULE_REQUEST_CREATED.equals(e.getEventType())
                        && e.getPayloadJson().contains(pending.id().toString()));

        // Overlapping PENDING request on same date is rejected with 409
        assertThatThrownBy(() -> requestService.submitMyRequest(
                techCtx,
                new CreateScheduleChangeRequest(ScheduleChangeRequestType.LEAVE, target, target, null, null, "Trùng ngày")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("SCHEDULE_REQUEST_OVERLAP");

        // ORG_STAFF can view but cannot approve
        assertThat(requestService.listOrganizationRequests(tech.getProviderId(), "PENDING", staffCtx)).hasSize(1);
        assertThatThrownBy(() -> requestService.reviewRequest(
                tech.getProviderId(), pending.id(), new ReviewScheduleChangeRequest(true, "ok"), staffCtx))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("RESOURCE_FORBIDDEN");

        // ORG_ADMIN approves -> atomically applies UNAVAILABLE exception & emits PROVIDER_SCHEDULE_REQUEST_REVIEWED
        ScheduleChangeRequestResponse approved = requestService.reviewRequest(
                tech.getProviderId(), pending.id(), new ReviewScheduleChangeRequest(true, "Đã duyệt nghỉ"), adminCtx);
        assertThat(approved.status()).isEqualTo(ScheduleChangeRequestStatus.APPROVED);
        assertThat(calendarService.getTechnicianCalendar("me", target, target, techCtx).days().getFirst().dayStatus())
                .isEqualTo("OFF");
        assertThat(outboxRepository.findAll())
                .anyMatch(e -> DomainEventContracts.PROVIDER_SCHEDULE_REQUEST_REVIEWED.equals(e.getEventType())
                        && e.getPayloadJson().contains(approved.id().toString()));
    }

    @Test
    void approveRollsBackOnBookingConflictAndRejectRequiresReasonAndCancelWorksWhilePending() {
        Technician tech = seedOrgAndTech("ConflictRejectCancel");
        ActorContext techCtx = techActor(tech);
        ActorContext adminCtx = orgAdmin(tech.getProviderId());
        ZoneId zone = ZoneId.of(ScheduleReconciliationService.DEFAULT_TIMEZONE);
        LocalDate bookedDay = LocalDate.now(zone).plusDays(6);

        // Seed a BOOKED window on bookedDay
        Instant bStart = bookedDay.atTime(9, 0).atZone(zone).toInstant();
        Instant bEnd = bookedDay.atTime(11, 0).atZone(zone).toInstant();
        scheduleWindowRepository.saveAndFlush(ScheduleWindow.builder()
                .id(UUID.randomUUID())
                .technicianId(tech.getId())
                .startsAt(bStart)
                .endsAt(bEnd)
                .status(ScheduleWindowStatus.BOOKED.name())
                .allocatedBookingId(UUID.randomUUID())
                .source(ScheduleReconciliationService.SOURCE_RECURRING)
                .createdAt(Instant.now())
                .build());

        ScheduleChangeRequestResponse reqConflict = requestService.submitMyRequest(
                techCtx,
                new CreateScheduleChangeRequest(ScheduleChangeRequestType.LEAVE, bookedDay, bookedDay, null, null, "Xin nghỉ"));

        // Approving LEAVE over a BOOKED slot throws ScheduleAllocationConflictException & keeps request PENDING
        assertThatThrownBy(() -> requestService.reviewRequest(
                tech.getProviderId(), reqConflict.id(), new ReviewScheduleChangeRequest(true, null), adminCtx))
                .isInstanceOf(ScheduleAllocationConflictException.class);
        assertThat(requestService.listMyRequests(techCtx, "PENDING")).hasSize(1);

        // Reject without reason fails validation; reject with reason succeeds
        assertThatThrownBy(() -> requestService.reviewRequest(
                tech.getProviderId(), reqConflict.id(), new ReviewScheduleChangeRequest(false, "  "), adminCtx))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("VALIDATION_FAILED");

        ScheduleChangeRequestResponse rejected = requestService.reviewRequest(
                tech.getProviderId(), reqConflict.id(), new ReviewScheduleChangeRequest(false, "Đã có booking khách đặt"), adminCtx);
        assertThat(rejected.status()).isEqualTo(ScheduleChangeRequestStatus.REJECTED);
        assertThat(rejected.reviewNote()).isEqualTo("Đã có booking khách đặt");

        // Submit SHIFT_CHANGE and cancel while PENDING
        LocalDate shiftDay = bookedDay.plusDays(2);
        ScheduleChangeRequestResponse shiftReq = requestService.submitMyRequest(
                techCtx,
                new CreateScheduleChangeRequest(
                        ScheduleChangeRequestType.SHIFT_CHANGE,
                        shiftDay,
                        shiftDay,
                        LocalTime.of(13, 0),
                        LocalTime.of(18, 0),
                        "Xin chuyển sang ca chiều"));
        ScheduleChangeRequestResponse cancelled = requestService.cancelMyRequest(techCtx, shiftReq.id());
        assertThat(cancelled.status()).isEqualTo(ScheduleChangeRequestStatus.CANCELLED);
    }
}
