package com.homecare.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homecare.common.error.DomainException;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.web.EtagSupport;
import com.homecare.provider.dto.ProviderDtos.CreateScheduleWindowRequest;
import com.homecare.provider.dto.ProviderDtos.TechnicianResponse;
import com.homecare.provider.dto.ProviderDtos.UpdateScheduleWindowRequest;
import com.homecare.provider.dto.RecurringScheduleDtos.CalendarDayDto;
import com.homecare.provider.dto.RecurringScheduleDtos.CreateScheduleChangeRequest;
import com.homecare.provider.dto.RecurringScheduleDtos.DayTimeIntervalDto;
import com.homecare.provider.dto.RecurringScheduleDtos.LocalTimeIntervalDto;
import com.homecare.provider.dto.RecurringScheduleDtos.OrganizationCalendarResponse;
import com.homecare.provider.dto.RecurringScheduleDtos.ReviewScheduleChangeRequest;
import com.homecare.provider.dto.RecurringScheduleDtos.TechnicianCalendarRowDto;
import com.homecare.provider.dto.RecurringScheduleDtos.UpsertScheduleExceptionsRequest;
import com.homecare.provider.dto.RecurringScheduleDtos.UpsertWeeklyScheduleRequest;
import com.homecare.provider.entity.ProviderEntity;
import com.homecare.provider.entity.ScheduleChangeRequestType;
import com.homecare.provider.entity.ScheduleExceptionType;
import com.homecare.provider.entity.ScheduleWindow;
import com.homecare.provider.entity.ScheduleWindowStatus;
import com.homecare.provider.entity.ServiceOffering;
import com.homecare.provider.entity.Technician;
import com.homecare.provider.organization.repository.ProviderOutboxEventRepository;
import com.homecare.provider.repository.ProviderRepository;
import com.homecare.provider.repository.ScheduleWindowRepository;
import com.homecare.provider.repository.ServiceOfferingRepository;
import com.homecare.provider.repository.TechnicianRepository;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class TechnicianCalendarAndPolicyTest {

    @Autowired
    private TechnicianRecurringScheduleService recurringService;

    @Autowired
    private TechnicianCalendarService calendarService;

    @Autowired
    private ProviderDomainService providerDomainService;

    @Autowired
    private ProviderRepository providerRepository;

    @Autowired
    private TechnicianRepository technicianRepository;

    @Autowired
    private ServiceOfferingRepository offeringRepository;

    @Autowired
    private ScheduleWindowRepository scheduleWindowRepository;

    @Autowired
    private ProviderOutboxEventRepository outboxRepository;

    @Autowired
    private TechnicianScheduleRequestService requestService;

    @Autowired
    private ScheduleMaterializationWorker materializationWorker;

    private ActorContext orgAdmin(UUID orgId, String userId) {
        return new ActorContext(
                userId,
                AccessMode.ORGANIZATION,
                orgId,
                Set.of(RbacConstants.ROLE_ORGANIZATION),
                Set.of(),
                Set.of("ORG_ADMIN"),
                Set.of(RbacConstants.PERM_SCHEDULE_MANAGE));
    }

    private ActorContext orgStaff(UUID orgId, String userId) {
        return new ActorContext(
                userId,
                AccessMode.ORGANIZATION,
                orgId,
                Set.of(RbacConstants.ROLE_ORGANIZATION),
                Set.of(),
                Set.of("ORG_STAFF"),
                Set.of());
    }

    private ActorContext personalTech(String profileId) {
        return new ActorContext(
                profileId,
                AccessMode.PERSONAL,
                null,
                Set.of(RbacConstants.ROLE_TECHNICIAN),
                Set.of(),
                Set.of(),
                Set.of());
    }

    private Technician seedOrgAndTech(String nameSuffix) {
        UUID orgId = UUID.randomUUID();
        providerRepository.saveAndFlush(ProviderEntity.builder()
                .id(orgId)
                .organizationId(orgId)
                .ownerProfileId("owner-" + orgId)
                .name("Org " + nameSuffix)
                .city("HCM")
                .phone("0901234567")
                .ratingAvg(4.8)
                .status("ACTIVE")
                .createdAt(Instant.now())
                .build());
        return technicianRepository.saveAndFlush(Technician.builder()
                .id(UUID.randomUUID())
                .profileId("tech-profile-" + UUID.randomUUID())
                .providerId(orgId)
                .fullName("KTV " + nameSuffix)
                .specialties("AC")
                .experienceYears(3)
                .ratingAvg(4.9)
                .activeAllocations(0)
                .status("ACTIVE")
                .isOnline(true)
                .createdAt(Instant.now())
                .build());
    }

    @Test
    void blocksTechnicianSelfEditAndOrgStaffMutationAndCrossOrgMutation() {
        Technician tech = seedOrgAndTech("RBAC");
        ActorContext techSelf = personalTech(tech.getProfileId());
        ActorContext staff = orgStaff(tech.getProviderId(), "staff-1");
        ActorContext otherOrgAdmin = orgAdmin(UUID.randomUUID(), "other-admin");

        UpsertWeeklyScheduleRequest req = new UpsertWeeklyScheduleRequest(
                "Asia/Ho_Chi_Minh",
                LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(7),
                List.of(new DayTimeIntervalDto(DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(17, 0))));

        // AC-08: Technician calling mutation API directly receives 403 RESOURCE_FORBIDDEN
        assertThatThrownBy(() -> recurringService.upsertWeeklySchedule("me", techSelf, null, req))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("RESOURCE_FORBIDDEN");
        assertThatThrownBy(() -> providerDomainService.createScheduleWindow(
                "me", techSelf, new CreateScheduleWindowRequest(Instant.now().plus(2, ChronoUnit.HOURS), Instant.now().plus(4, ChronoUnit.HOURS))))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("RESOURCE_FORBIDDEN");

        // ORG_STAFF is read-only: cannot mutate, CAN view calendar
        assertThatThrownBy(() -> recurringService.upsertWeeklySchedule(tech.getId().toString(), staff, null, req))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("RESOURCE_FORBIDDEN");
        OrganizationCalendarResponse staffCalendar = calendarService.getOrganizationCalendar(
                tech.getProviderId(), LocalDate.now(), LocalDate.now().plusDays(6), null, null, null, staff);
        assertThat(staffCalendar.technicians()).hasSize(1);

        // AC-09: Other Org Admin cannot mutate or view this Org's technician schedule
        assertThatThrownBy(() -> recurringService.upsertWeeklySchedule(tech.getId().toString(), otherOrgAdmin, null, req))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("RESOURCE_FORBIDDEN");
        assertThatThrownBy(() -> calendarService.getOrganizationCalendar(
                tech.getProviderId(), LocalDate.now(), LocalDate.now().plusDays(6), null, null, null, otherOrgAdmin))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("RESOURCE_FORBIDDEN");
    }

    @Test
    void effectiveRangeAndWeekdaysBeyondHorizonProjectExactWorkingDaysConsistently() {
        Technician tech = seedOrgAndTech("DecRange");
        ActorContext admin = orgAdmin(tech.getProviderId(), "admin-dec");
        ActorContext techSelf = personalTech(tech.getProfileId());

        // AC-01, AC-03, AC-04, AC-14: Set 2026-12-27 (Sun) .. 2026-12-31 (Thu) with Mon-Sat -> exactly 4 working days
        LocalDate from = LocalDate.of(2026, 12, 27);
        LocalDate to = LocalDate.of(2026, 12, 31);
        List<DayTimeIntervalDto> monToSat = List.of(
                new DayTimeIntervalDto(DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(17, 0)),
                new DayTimeIntervalDto(DayOfWeek.TUESDAY, LocalTime.of(8, 0), LocalTime.of(17, 0)),
                new DayTimeIntervalDto(DayOfWeek.WEDNESDAY, LocalTime.of(8, 0), LocalTime.of(17, 0)),
                new DayTimeIntervalDto(DayOfWeek.THURSDAY, LocalTime.of(8, 0), LocalTime.of(17, 0)),
                new DayTimeIntervalDto(DayOfWeek.FRIDAY, LocalTime.of(8, 0), LocalTime.of(17, 0)),
                new DayTimeIntervalDto(DayOfWeek.SATURDAY, LocalTime.of(8, 0), LocalTime.of(17, 0)));

        var initialOverview = recurringService.upsertWeeklySchedule(
                tech.getId().toString(), admin, null,
                new UpsertWeeklyScheduleRequest("Asia/Ho_Chi_Minh", from, to, monToSat));

        LocalDate queryFrom = LocalDate.of(2026, 12, 21);
        LocalDate queryTo = LocalDate.of(2027, 1, 3);

        OrganizationCalendarResponse orgCal = calendarService.getOrganizationCalendar(
                tech.getProviderId(), queryFrom, queryTo, null, "DecRange", "HAS_SHIFT", admin);
        TechnicianCalendarRowDto techCal = calendarService.getTechnicianCalendar("me", queryFrom, queryTo, techSelf);

        assertThat(orgCal.technicians()).hasSize(1);
        List<CalendarDayDto> orgDays = orgCal.technicians().getFirst().days();
        // AC-12: Org Calendar and Mobile Technician Calendar return identical day statuses and slots
        assertThat(techCal.days()).isEqualTo(orgDays);

        List<CalendarDayDto> workingDays = orgDays.stream()
                .filter(d -> "WORKING".equals(d.dayStatus()))
                .toList();
        assertThat(workingDays).extracting(CalendarDayDto::date)
                .containsExactly(
                        LocalDate.of(2026, 12, 28),
                        LocalDate.of(2026, 12, 29),
                        LocalDate.of(2026, 12, 30),
                        LocalDate.of(2026, 12, 31));
        assertThat(orgDays.stream().filter(d -> d.date().equals(LocalDate.of(2026, 12, 27))).findFirst().orElseThrow().dayStatus())
                .isEqualTo("OFF");
        assertThat(orgDays.stream().filter(d -> d.date().equals(LocalDate.of(2026, 12, 26))).findFirst().orElseThrow().dayStatus())
                .isEqualTo("UNSCHEDULED");
        assertThat(orgDays.stream().filter(d -> d.date().equals(LocalDate.of(2027, 1, 1))).findFirst().orElseThrow().dayStatus())
                .isEqualTo("UNSCHEDULED");

        // AC-02: Add Sunday -> now 5 working days (27/12..31/12)
        List<DayTimeIntervalDto> allDays = new ArrayList<>(monToSat);
        allDays.add(new DayTimeIntervalDto(DayOfWeek.SUNDAY, LocalTime.of(8, 0), LocalTime.of(17, 0)));
        long initialVersion = initialOverview.currentRule() != null
                ? initialOverview.currentRule().version()
                : initialOverview.upcomingRules().getFirst().version();
        recurringService.upsertWeeklySchedule(
                tech.getId().toString(), admin, EtagSupport.format(initialVersion),
                new UpsertWeeklyScheduleRequest("Asia/Ho_Chi_Minh", from, to, allDays));

        TechnicianCalendarRowDto updatedCal = calendarService.getTechnicianCalendar("me", from, to, techSelf);
        assertThat(updatedCal.days().stream().filter(d -> "WORKING".equals(d.dayStatus())).count()).isEqualTo(5);

        // AC-15: Schedule audits recorded and retrievable
        assertThat(recurringService.listScheduleAudits(tech.getId().toString(), admin)).isNotEmpty();
    }

    @Test
    void immediateBookingRequiresOnlineWhileFutureBookingIgnoresOfflineAndOfflineKeepsAssignedBookings() {
        Technician tech = seedOrgAndTech("OnlinePolicy");
        ServiceOffering offering = offeringRepository.saveAndFlush(ServiceOffering.builder()
                .id(UUID.randomUUID())
                .providerId(tech.getProviderId())
                .name("AC Check")
                .category("AC")
                .basePrice(200_000L)
                .estimatedDurationMinutes(60)
                .active(true)
                .createdAt(Instant.now())
                .build());

        Instant now = Instant.now();
        // Immediate window: starts in 30 mins (within 120m threshold)
        Instant immStart = now.plus(30, ChronoUnit.MINUTES);
        Instant immEnd = immStart.plus(60, ChronoUnit.MINUTES);
        scheduleWindowRepository.saveAndFlush(ScheduleWindow.builder()
                .id(UUID.randomUUID())
                .technicianId(tech.getId())
                .startsAt(immStart.minus(15, ChronoUnit.MINUTES))
                .endsAt(immEnd.plus(15, ChronoUnit.MINUTES))
                .status(ScheduleWindowStatus.AVAILABLE.name())
                .source(ScheduleReconciliationService.SOURCE_MANUAL)
                .createdAt(now)
                .build());

        // Future window: starts in 24 hours (beyond 120m threshold)
        Instant futStart = now.plus(24, ChronoUnit.HOURS);
        Instant futEnd = futStart.plus(60, ChronoUnit.MINUTES);
        scheduleWindowRepository.saveAndFlush(ScheduleWindow.builder()
                .id(UUID.randomUUID())
                .technicianId(tech.getId())
                .startsAt(futStart.minus(15, ChronoUnit.MINUTES))
                .endsAt(futEnd.plus(15, ChronoUnit.MINUTES))
                .status(ScheduleWindowStatus.AVAILABLE.name())
                .source(ScheduleReconciliationService.SOURCE_MANUAL)
                .createdAt(now)
                .build());

        // Technician toggles OFFLINE
        TechnicianResponse offlineResp = providerDomainService.updateMyOnlineStatus(tech.getProfileId(), false);
        assertThat(offlineResp.isOnline()).isFalse();

        // Immediate booking must NOT allocate offline technician
        var immOutcome = providerDomainService.allocateTechnicianForBooking(
                "intent-imm-" + UUID.randomUUID(), UUID.randomUUID(), offering.getId(), "AC",
                immStart, immEnd, tech.getProviderId(), true);
        assertThat(immOutcome.allocated()).isFalse();

        // Scheduled future booking (>120 min ahead) MUST still allocate offline technician
        UUID futureBookingId = UUID.randomUUID();
        var futOutcome = providerDomainService.allocateTechnicianForBooking(
                "intent-fut-" + futureBookingId, futureBookingId, offering.getId(), "AC",
                futStart, futEnd, tech.getProviderId(), true);
        assertThat(futOutcome.allocated()).isTrue();
        assertThat(futOutcome.technicianId()).isEqualTo(tech.getId());

        // Toggling online -> immediate booking succeeds; toggling offline again does NOT cancel assigned bookings
        TechnicianResponse onlineResp = providerDomainService.updateMyOnlineStatus(tech.getProfileId(), true);
        assertThat(onlineResp.isOnline()).isTrue();
        var immOutcomeOnline = providerDomainService.allocateTechnicianForBooking(
                "intent-imm-ok-" + UUID.randomUUID(), UUID.randomUUID(), offering.getId(), "AC",
                immStart, immEnd, tech.getProviderId(), true);
        assertThat(immOutcomeOnline.allocated()).isTrue();

        providerDomainService.updateMyOnlineStatus(tech.getProfileId(), false);
        Technician reloaded = technicianRepository.findById(tech.getId()).orElseThrow();
        assertThat(reloaded.getActiveAllocations()).isEqualTo(2);

        // AC-13: Online without schedule still cannot allocate
        Instant unscheduledStart = now.plus(48, ChronoUnit.HOURS);
        providerDomainService.updateMyOnlineStatus(tech.getProfileId(), true);
        var noShiftOutcome = providerDomainService.allocateTechnicianForBooking(
                "intent-noshift-" + UUID.randomUUID(), UUID.randomUUID(), offering.getId(), "AC",
                unscheduledStart, unscheduledStart.plus(60, ChronoUnit.MINUTES), tech.getProviderId(), true);
        assertThat(noShiftOutcome.allocated()).isFalse();
    }

    @Test
    void unavailableExceptionOnFutureDateReflectsOffOnCalendar() {
        Technician tech = seedOrgAndTech("ExOff");
        ActorContext admin = orgAdmin(tech.getProviderId(), "admin-ex");
        LocalDate target = LocalDate.now().plusDays(40); // beyond 30-day horizon
        recurringService.upsertWeeklySchedule(
                tech.getId().toString(), admin, null,
                new UpsertWeeklyScheduleRequest(
                        "Asia/Ho_Chi_Minh",
                        target,
                        target.plusDays(2),
                        List.of(
                                new DayTimeIntervalDto(target.getDayOfWeek(), LocalTime.of(8, 0), LocalTime.of(17, 0)),
                                new DayTimeIntervalDto(target.plusDays(1).getDayOfWeek(), LocalTime.of(8, 0), LocalTime.of(17, 0)))));
        recurringService.upsertExceptions(
                tech.getId().toString(), admin,
                new UpsertScheduleExceptionsRequest(target, target, ScheduleExceptionType.UNAVAILABLE, "Nghỉ phép", List.of()));

        TechnicianCalendarRowDto cal = calendarService.getTechnicianCalendar(
                tech.getId().toString(), target, target.plusDays(1), admin);
        assertThat(cal.days().get(0).dayStatus()).isEqualTo("OFF");
        assertThat(cal.days().get(0).exceptionType()).isEqualTo("UNAVAILABLE");
        assertThat(cal.days().get(1).dayStatus()).isEqualTo("WORKING");
        assertThat(cal.days().get(1).projected()).isTrue();
    }

    @Test
    void manualSlotRejectsPastCreationAndEmitsScheduleUpdatedNotification() {
        Technician tech = seedOrgAndTech("ManualNotify");
        ActorContext admin = orgAdmin(tech.getProviderId(), "admin-manual");

        Instant pastStart = Instant.now().minus(4, ChronoUnit.HOURS);
        Instant pastEnd = Instant.now().minus(2, ChronoUnit.HOURS);
        assertThatThrownBy(() -> providerDomainService.createScheduleWindow(
                tech.getId().toString(), admin, new CreateScheduleWindowRequest(pastStart, pastEnd)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("VALIDATION_FAILED");

        Instant futStart = Instant.now().plus(3, ChronoUnit.HOURS);
        Instant futEnd = futStart.plus(2, ChronoUnit.HOURS);
        providerDomainService.createScheduleWindow(
                tech.getId().toString(), admin, new CreateScheduleWindowRequest(futStart, futEnd));

        assertThat(outboxRepository.findAll())
                .anyMatch(e -> DomainEventContracts.PROVIDER_SCHEDULE_UPDATED.equals(e.getEventType())
                        && e.getPayloadJson().contains(tech.getProfileId())
                        && e.getPayloadJson().contains("MANUAL_SLOT_CREATED"));
    }

    @Test
    void convergenceGuardsProtectBookedExceptionsPastPatchRejectNotifyAndWorkerExceptions() {
        Technician tech = seedOrgAndTech("Converge");
        ActorContext admin = orgAdmin(tech.getProviderId(), "admin-conv");
        ActorContext globalAdmin = new ActorContext(
                "global-admin-conv",
                AccessMode.PERSONAL,
                null,
                Set.of(RbacConstants.ROLE_ADMIN),
                Set.of(),
                Set.of(),
                Set.of());
        ActorContext techActor = personalTech(tech.getProfileId());

        // T035: Global ADMIN in PERSONAL mode can read org calendar & schedule requests
        LocalDate extraDay = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(5);
        assertThat(calendarService.getOrganizationCalendar(
                tech.getProviderId(), extraDay, extraDay, null, null, null, globalAdmin).technicians())
                .hasSize(1);
        assertThat(requestService.listOrganizationRequests(tech.getProviderId(), null, globalAdmin))
                .isEmpty();

        // T036 + T038: EXTRA_HOURS without weekly rule is materialized by worker, and deleting while BOOKED throws 409
        var createdEx = recurringService.upsertExceptions(
                tech.getId().toString(),
                admin,
                new UpsertScheduleExceptionsRequest(
                        extraDay,
                        extraDay,
                        ScheduleExceptionType.EXTRA_HOURS,
                        "Tăng ca",
                        List.of(new LocalTimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(12, 0)))));
        assertThat(materializationWorker.replenishAllActiveTechnicians("TEST")).isGreaterThanOrEqualTo(1);

        ZoneId vn = ZoneId.of("Asia/Ho_Chi_Minh");
        Instant bookStart = extraDay.atTime(10, 0).atZone(vn).toInstant();
        Instant bookEnd = extraDay.atTime(11, 0).atZone(vn).toInstant();
        scheduleWindowRepository.deleteAll(scheduleWindowRepository.findByTechnicianIdOrderByStartsAtAsc(tech.getId()));
        scheduleWindowRepository.saveAndFlush(ScheduleWindow.builder()
                .id(UUID.randomUUID())
                .technicianId(tech.getId())
                .startsAt(bookStart)
                .endsAt(bookEnd)
                .status(ScheduleWindowStatus.BOOKED.name())
                .source(ScheduleReconciliationService.SOURCE_RECURRING)
                .allocatedBookingId(UUID.randomUUID())
                .createdAt(Instant.now())
                .build());

        var exItem = createdEx.getFirst();
        assertThatThrownBy(() -> recurringService.deleteExceptionById(
                tech.getId().toString(), admin, exItem.id(), EtagSupport.format(exItem.version())))
                .isInstanceOf(ScheduleAllocationConflictException.class);
        assertThatThrownBy(() -> recurringService.deleteExceptionsByDate(
                tech.getId().toString(), admin, extraDay))
                .isInstanceOf(ScheduleAllocationConflictException.class);

        // T036: PATCH moving a future manual slot into the past is rejected with VALIDATION_FAILED
        Instant futStart = Instant.now().plus(4, ChronoUnit.HOURS);
        var manual = providerDomainService.createScheduleWindow(
                tech.getId().toString(), admin, new CreateScheduleWindowRequest(futStart, futStart.plus(2, ChronoUnit.HOURS)));
        assertThatThrownBy(() -> providerDomainService.updateScheduleWindow(
                tech.getId().toString(),
                admin,
                manual.id(),
                EtagSupport.format(manual.version()),
                new UpdateScheduleWindowRequest(
                        Instant.now().minus(5, ChronoUnit.HOURS),
                        Instant.now().minus(3, ChronoUnit.HOURS),
                        null)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("VALIDATION_FAILED");

        // T037: Rejecting a technician schedule request does NOT emit provider.schedule.updated.v1
        long updatedBeforeReject = outboxRepository.findAll().stream()
                .filter(e -> DomainEventContracts.PROVIDER_SCHEDULE_UPDATED.equals(e.getEventType())
                        && e.getPayloadJson().contains(tech.getProfileId()))
                .count();
        var req = requestService.submitMyRequest(
                techActor,
                new CreateScheduleChangeRequest(
                        ScheduleChangeRequestType.LEAVE,
                        extraDay.plusDays(2),
                        extraDay.plusDays(2),
                        null,
                        null,
                        "Việc gia đình"));
        requestService.reviewRequest(
                tech.getProviderId(),
                req.id(),
                new ReviewScheduleChangeRequest(false, "Thiếu nhân sự trực"),
                admin);
        long updatedAfterReject = outboxRepository.findAll().stream()
                .filter(e -> DomainEventContracts.PROVIDER_SCHEDULE_UPDATED.equals(e.getEventType())
                        && e.getPayloadJson().contains(tech.getProfileId()))
                .count();
        assertThat(updatedAfterReject).isEqualTo(updatedBeforeReject);
    }
}
