package com.homecare.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homecare.common.error.DomainException;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.web.EtagSupport;
import com.homecare.provider.dto.ProviderDtos.CreateScheduleWindowRequest;
import com.homecare.provider.dto.ProviderDtos.UpdateScheduleWindowRequest;
import com.homecare.provider.dto.RecurringScheduleDtos.DayTimeIntervalDto;
import com.homecare.provider.dto.RecurringScheduleDtos.LocalTimeIntervalDto;
import com.homecare.provider.dto.RecurringScheduleDtos.UpsertScheduleExceptionsRequest;
import com.homecare.provider.dto.RecurringScheduleDtos.UpsertWeeklyScheduleRequest;
import com.homecare.provider.entity.ScheduleException;
import com.homecare.provider.entity.ScheduleExceptionType;
import com.homecare.provider.entity.ScheduleWindow;
import com.homecare.provider.entity.Technician;
import com.homecare.provider.repository.ScheduleExceptionRepository;
import com.homecare.provider.repository.ScheduleWindowRepository;
import com.homecare.provider.repository.TechnicianRepository;
import com.homecare.provider.repository.TechnicianScheduleAuditRepository;
import com.homecare.provider.repository.WeeklyScheduleRuleRepository;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class TechnicianRecurringSchedulePostgresIT {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TechnicianRecurringScheduleService recurringService;

    @Autowired
    ScheduleReconciliationService reconciliationService;

    @Autowired
    ScheduleMaterializationWorker worker;

    @Autowired
    ProviderDomainService providerService;

    @Autowired
    TechnicianRepository technicianRepository;

    @Autowired
    ScheduleWindowRepository windowRepository;

    @Autowired
    WeeklyScheduleRuleRepository weeklyRuleRepository;

    @Autowired
    ScheduleExceptionRepository exceptionRepository;

    @Autowired
    TechnicianScheduleAuditRepository auditRepository;

    @BeforeAll
    static void migrate(@Autowired DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    private Technician createFreshTechnician(UUID providerId) {
        UUID id = UUID.randomUUID();
        return technicianRepository.saveAndFlush(Technician.builder()
                .id(id)
                .providerId(providerId)
                .profileId("tech-" + id)
                .fullName("KTV Test " + id.toString().substring(0, 6))
                .specialties("AC,ELECTRICAL")
                .experienceYears(5)
                .ratingAvg(4.9)
                .activeAllocations(0)
                .status("ACTIVE")
                .createdAt(Instant.now())
                .build());
    }

    private ActorContext ownerActor(UUID providerId) {
        return new ActorContext(
                "owner-" + providerId,
                AccessMode.ORGANIZATION,
                providerId,
                Set.of("ORGANIZATION"),
                Set.of(),
                Set.of("ORG_OWNER"),
                Set.of(RbacConstants.PERM_SCHEDULE_MANAGE));
    }

    @Test
    void materializes30DaysIdempotentlyPreservesManualWindowsAndRecordsAudit() {
        Technician tech = createFreshTechnician(ProviderDataInitializer.PROVIDER_1_ID);
        ActorContext actor = ownerActor(ProviderDataInitializer.PROVIDER_1_ID);
        LocalDate today = LocalDate.now(VN);

        // Add a MANUAL window on a Sunday 10 days out (AC-SCH-015)
        LocalDate manualDate = today.plusDays(10);
        while (manualDate.getDayOfWeek() != DayOfWeek.SUNDAY) {
            manualDate = manualDate.plusDays(1);
        }
        Instant manualStart = manualDate.atTime(9, 0).atZone(VN).toInstant();
        Instant manualEnd = manualDate.atTime(11, 0).atZone(VN).toInstant();
        providerService.createScheduleWindow(tech.getId().toString(), actor,
                new CreateScheduleWindowRequest(manualStart, manualEnd));

        // Create Mon-Fri split shift schedule (AC-SCH-001)
        List<DayTimeIntervalDto> monFri = List.of(
                new DayTimeIntervalDto(DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(12, 0)),
                new DayTimeIntervalDto(DayOfWeek.MONDAY, LocalTime.of(13, 0), LocalTime.of(17, 0)),
                new DayTimeIntervalDto(DayOfWeek.TUESDAY, LocalTime.of(8, 0), LocalTime.of(17, 0)),
                new DayTimeIntervalDto(DayOfWeek.WEDNESDAY, LocalTime.of(8, 0), LocalTime.of(17, 0)),
                new DayTimeIntervalDto(DayOfWeek.THURSDAY, LocalTime.of(8, 0), LocalTime.of(17, 0)),
                new DayTimeIntervalDto(DayOfWeek.FRIDAY, LocalTime.of(8, 0), LocalTime.of(17, 0)));

        var overview = recurringService.upsertWeeklySchedule(
                tech.getId().toString(), actor, null,
                new UpsertWeeklyScheduleRequest("Asia/Ho_Chi_Minh", today, null, monFri));
        assertThat(overview.currentRule()).isNotNull();
        assertThat(overview.horizonDays()).isEqualTo(30);

        List<ScheduleWindow> afterFirst = windowRepository.findByTechnicianIdOrderByStartsAtAsc(tech.getId());
        assertThat(afterFirst).isNotEmpty();
        assertThat(afterFirst).anyMatch(w -> "MANUAL".equals(w.getSource()) && w.getStartsAt().equals(manualStart));
        assertThat(afterFirst).anyMatch(w -> "RECURRING".equals(w.getSource()));

        // Re-run worker multiple times -> idempotent (AC-SCH-002)
        int countBefore = afterFirst.size();
        worker.replenishAllActiveTechnicians("TEST_REPEAT_1");
        worker.replenishAllActiveTechnicians("TEST_REPEAT_2");
        List<ScheduleWindow> afterRepeat = windowRepository.findByTechnicianIdOrderByStartsAtAsc(tech.getId());
        assertThat(afterRepeat).hasSize(countBefore);

        // Stale ETag rejected with 412 (AC-SCH-023)
        assertThatThrownBy(() -> recurringService.upsertWeeklySchedule(
                tech.getId().toString(), actor, "\"v999\"",
                new UpsertWeeklyScheduleRequest("Asia/Ho_Chi_Minh", today, null, monFri)))
                .isInstanceOf(EtagSupport.PreconditionException.class);

        // Audit log recorded (BR-SCH-047)
        assertThat(auditRepository.findByTechnicianIdOrderByCreatedAtDesc(tech.getId()))
                .anyMatch(a -> "WEEKLY_RULE_UPSERTED".equals(a.getAction()));

        // Cross-org IDOR blocked with 403 (AC-SCH-024)
        ActorContext otherOrg = ownerActor(ProviderDataInitializer.PROVIDER_2_ID);
        assertThatThrownBy(() -> recurringService.getWeeklyScheduleOverview(tech.getId().toString(), otherOrg))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void protectsBookedWindowsWith409DeletesRecurringWithoutRegenerationAndRecalculatesOnRelease() {
        Technician tech = createFreshTechnician(ProviderDataInitializer.PROVIDER_1_ID);
        ActorContext actor = ownerActor(ProviderDataInitializer.PROVIDER_1_ID);
        LocalDate today = LocalDate.now(VN);
        LocalDate candidateDay = today.plusDays(3);
        while (candidateDay.getDayOfWeek() == DayOfWeek.SATURDAY || candidateDay.getDayOfWeek() == DayOfWeek.SUNDAY) {
            candidateDay = candidateDay.plusDays(1);
        }
        final LocalDate targetDay = candidateDay;

        // Set weekly schedule 08:00-17:00
        List<DayTimeIntervalDto> fullWeekdays = List.of(
                new DayTimeIntervalDto(DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(17, 0)),
                new DayTimeIntervalDto(DayOfWeek.TUESDAY, LocalTime.of(8, 0), LocalTime.of(17, 0)),
                new DayTimeIntervalDto(DayOfWeek.WEDNESDAY, LocalTime.of(8, 0), LocalTime.of(17, 0)),
                new DayTimeIntervalDto(DayOfWeek.THURSDAY, LocalTime.of(8, 0), LocalTime.of(17, 0)),
                new DayTimeIntervalDto(DayOfWeek.FRIDAY, LocalTime.of(8, 0), LocalTime.of(17, 0)));
        var overview = recurringService.upsertWeeklySchedule(
                tech.getId().toString(), actor, null,
                new UpsertWeeklyScheduleRequest("Asia/Ho_Chi_Minh", today, null, fullWeekdays));

        // Allocate booking at 14:00-15:00 on targetDay (tests split under EXCLUDE USING gist!)
        Instant bookStart = targetDay.atTime(14, 0).atZone(VN).toInstant();
        Instant bookEnd = targetDay.atTime(15, 0).atZone(VN).toInstant();
        UUID bookingId = UUID.randomUUID();
        UUID opId = UUID.randomUUID();
        var alloc = providerService.allocateTechnicianForBooking(
                opId, "intent-" + bookingId, bookingId,
                ProviderDataInitializer.SERVICE_AC_CLEAN_ID, "AC",
                bookStart, bookEnd, ProviderDataInitializer.PROVIDER_1_ID, true, tech.getId());
        assertThat(alloc.allocated()).isTrue();

        // Attempt to shorten shift to 08:00-12:00 -> MUST fail 409 CONFLICT with affected booking (AC-SCH-013)
        List<DayTimeIntervalDto> shortened = List.of(
                new DayTimeIntervalDto(targetDay.getDayOfWeek(), LocalTime.of(8, 0), LocalTime.of(12, 0)));
        assertThatThrownBy(() -> recurringService.upsertWeeklySchedule(
                tech.getId().toString(), actor, EtagSupport.format(overview.currentRule().version()),
                new UpsertWeeklyScheduleRequest("Asia/Ho_Chi_Minh", today, null, shortened)))
                .isInstanceOf(ScheduleAllocationConflictException.class)
                .satisfies(ex -> {
                    var conflictEx = (ScheduleAllocationConflictException) ex;
                    assertThat(conflictEx.conflicts()).hasSize(1);
                    assertThat(conflictEx.conflicts().getFirst().bookingId()).isEqualTo(bookingId);
                });

        // Attempt to set UNAVAILABLE leave on targetDay -> MUST fail 409 CONFLICT (BR-SCH-031)
        assertThatThrownBy(() -> recurringService.upsertExceptions(
                tech.getId().toString(), actor,
                new UpsertScheduleExceptionsRequest(targetDay, targetDay, ScheduleExceptionType.UNAVAILABLE, "Leave", List.of())))
                .isInstanceOf(ScheduleAllocationConflictException.class);

        // Attempt to delete BOOKED window directly -> MUST fail 409 CONFLICT (AC-SCH-014)
        ScheduleWindow bookedWindow = windowRepository.findByAllocatedBookingId(bookingId).getFirst();
        assertThatThrownBy(() -> providerService.deleteSchedule(
                tech.getId().toString(), bookedWindow.getId(), actor, EtagSupport.format(bookedWindow.getVersion())))
                .isInstanceOf(ScheduleAllocationConflictException.class);

        // Delete a future RECURRING AVAILABLE slot on another weekday -> creates UNAVAILABLE exception & worker never regenerates (AC-SCH-007)
        LocalDate otherDay = targetDay.plusDays(1);
        while (otherDay.getDayOfWeek() == DayOfWeek.SATURDAY || otherDay.getDayOfWeek() == DayOfWeek.SUNDAY) {
            otherDay = otherDay.plusDays(1);
        }
        LocalDate finalOtherDay = otherDay;
        ScheduleWindow otherSlot = windowRepository.findByTechnicianIdOrderByStartsAtAsc(tech.getId()).stream()
                .filter(w -> "AVAILABLE".equals(w.getStatus())
                        && w.getStartsAt().atZone(VN).toLocalDate().equals(finalOtherDay))
                .findFirst().orElseThrow();
        providerService.deleteSchedule(tech.getId().toString(), otherSlot.getId(), actor, EtagSupport.format(otherSlot.getVersion()));
        worker.replenishAllActiveTechnicians("AFTER_DELETE");
        assertThat(windowRepository.findByTechnicianIdOrderByStartsAtAsc(tech.getId()))
                .noneMatch(w -> w.getStartsAt().atZone(VN).toLocalDate().equals(finalOtherDay));

        // Release booking on targetDay -> restores 14:00-15:00 and merges cleanly back into 08:00-17:00 (AC-SCH-016)
        providerService.releaseBooking(bookingId);
        List<ScheduleWindow> targetDayWindows = windowRepository.findByTechnicianIdOrderByStartsAtAsc(tech.getId()).stream()
                .filter(w -> w.getStartsAt().atZone(VN).toLocalDate().equals(targetDay))
                .toList();
        assertThat(targetDayWindows).hasSize(1);
        assertThat(targetDayWindows.getFirst().getStartsAt()).isEqualTo(targetDay.atTime(8, 0).atZone(VN).toInstant());
        assertThat(targetDayWindows.getFirst().getEndsAt()).isEqualTo(targetDay.atTime(17, 0).atZone(VN).toInstant());

        // Now mark targetDay UNAVAILABLE after booking is released -> 0 AVAILABLE windows even after worker runs (AC-SCH-010)
        recurringService.upsertExceptions(
                tech.getId().toString(), actor,
                new UpsertScheduleExceptionsRequest(targetDay, targetDay, ScheduleExceptionType.UNAVAILABLE, "Nghỉ phép", List.of()));
        worker.replenishAllActiveTechnicians("AFTER_LEAVE");
        assertThat(windowRepository.findByTechnicianIdOrderByStartsAtAsc(tech.getId()))
                .noneMatch(w -> w.getStartsAt().atZone(VN).toLocalDate().equals(targetDay));

        // Delete exception with stale If-Match -> 412 PreconditionException (BR-SCH-065)
        var targetExceptions = recurringService.listExceptions(tech.getId().toString(), actor, targetDay, targetDay);
        assertThat(targetExceptions).hasSize(1);
        assertThatThrownBy(() -> recurringService.deleteExceptionById(
                tech.getId().toString(), actor, targetExceptions.getFirst().id(), "\"v999\""))
                .isInstanceOf(EtagSupport.PreconditionException.class);

        // Delete exception with valid If-Match -> restores 08:00-17:00 on targetDay (AC-SCH-012)
        recurringService.deleteExceptionById(
                tech.getId().toString(), actor, targetExceptions.getFirst().id(),
                EtagSupport.format(targetExceptions.getFirst().version()));
        assertThat(windowRepository.findByTechnicianIdOrderByStartsAtAsc(tech.getId()))
                .anyMatch(w -> w.getStartsAt().atZone(VN).toLocalDate().equals(targetDay) && "AVAILABLE".equals(w.getStatus()));
    }

    @Test
    void handlesCustomHoursExtraHoursEndScheduleAndConcurrentAllocation() throws Exception {
        Technician tech = createFreshTechnician(ProviderDataInitializer.PROVIDER_1_ID);
        ActorContext actor = ownerActor(ProviderDataInitializer.PROVIDER_1_ID);
        LocalDate today = LocalDate.now(VN);

        recurringService.upsertWeeklySchedule(
                tech.getId().toString(), actor, null,
                new UpsertWeeklyScheduleRequest("Asia/Ho_Chi_Minh", today, null, List.of(
                        new DayTimeIntervalDto(DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(17, 0)))));

        // EXTRA_HOURS on a Sunday (AC-SCH-011)
        LocalDate sunday = today.plusDays(1);
        while (sunday.getDayOfWeek() != DayOfWeek.SUNDAY) {
            sunday = sunday.plusDays(1);
        }
        LocalDate finalSunday = sunday;
        recurringService.upsertExceptions(
                tech.getId().toString(), actor,
                new UpsertScheduleExceptionsRequest(finalSunday, finalSunday, ScheduleExceptionType.EXTRA_HOURS, "Tăng ca",
                        List.of(new LocalTimeIntervalDto(LocalTime.of(9, 0), LocalTime.of(12, 0)))));
        assertThat(windowRepository.findByTechnicianIdOrderByStartsAtAsc(tech.getId()))
                .anyMatch(w -> w.getStartsAt().equals(finalSunday.atTime(9, 0).atZone(VN).toInstant())
                        && w.getEndsAt().equals(finalSunday.atTime(12, 0).atZone(VN).toInstant()));

        // Concurrent double-booking on the same Sunday slot -> at most 1 succeeds (AC-SCH-018)
        Instant s = finalSunday.atTime(10, 0).atZone(VN).toInstant();
        Instant e = finalSunday.atTime(11, 0).atZone(VN).toInstant();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> attempt = () -> {
                UUID bId = UUID.randomUUID();
                UUID op = UUID.randomUUID();
                return providerService.allocateTechnicianForBooking(
                        op, "conc-" + bId, bId,
                        ProviderDataInitializer.SERVICE_AC_CLEAN_ID, "AC",
                        s, e, ProviderDataInitializer.PROVIDER_1_ID, true, tech.getId()).allocated();
            };
            Future<Boolean> f1 = pool.submit(attempt);
            Future<Boolean> f2 = pool.submit(attempt);
            int successes = (f1.get() ? 1 : 0) + (f2.get() ? 1 : 0);
            assertThat(successes).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void verifiesEffectiveVersionsEndScheduleMultiDayAtomicRollbackLegacyBlockAndReassignment() {
        Technician techA = createFreshTechnician(ProviderDataInitializer.PROVIDER_1_ID);
        Technician techB = createFreshTechnician(ProviderDataInitializer.PROVIDER_1_ID);
        ActorContext actor = ownerActor(ProviderDataInitializer.PROVIDER_1_ID);
        LocalDate today = LocalDate.now(VN);

        // 1. Initial rule for techA and techB (Mon-Fri 08:00-17:00)
        List<DayTimeIntervalDto> fullWeek = List.of(
                new DayTimeIntervalDto(DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(17, 0)),
                new DayTimeIntervalDto(DayOfWeek.TUESDAY, LocalTime.of(8, 0), LocalTime.of(17, 0)),
                new DayTimeIntervalDto(DayOfWeek.WEDNESDAY, LocalTime.of(8, 0), LocalTime.of(17, 0)),
                new DayTimeIntervalDto(DayOfWeek.THURSDAY, LocalTime.of(8, 0), LocalTime.of(17, 0)),
                new DayTimeIntervalDto(DayOfWeek.FRIDAY, LocalTime.of(8, 0), LocalTime.of(17, 0)));
        var v1 = recurringService.upsertWeeklySchedule(
                techA.getId().toString(), actor, null,
                new UpsertWeeklyScheduleRequest("Asia/Ho_Chi_Minh", today, null, fullWeek));
        recurringService.upsertWeeklySchedule(
                techB.getId().toString(), actor, null,
                new UpsertWeeklyScheduleRequest("Asia/Ho_Chi_Minh", today, null, fullWeek));

        // 2. THIS_AND_FUTURE from today+10 closes prior rule at today+9 (AC-SCH-005)
        LocalDate splitDate = today.plusDays(10);
        var v2 = recurringService.upsertWeeklySchedule(
                techA.getId().toString(), actor, EtagSupport.format(v1.currentRule().version()),
                new UpsertWeeklyScheduleRequest("Asia/Ho_Chi_Minh", splitDate, null, List.of(
                        new DayTimeIntervalDto(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(18, 0)))));
        assertThat(v2.currentRule().effectiveUntil()).isEqualTo(splitDate.minusDays(1));
        assertThat(v2.upcomingRules()).hasSize(1);
        assertThat(v2.upcomingRules().getFirst().effectiveFrom()).isEqualTo(splitDate);

        // 3. Allocate booking on techA at today+2 (weekday) and test atomic multi-day leave rollback (BR-SCH-025)
        LocalDate bookDay = today.plusDays(2);
        while (bookDay.getDayOfWeek() == DayOfWeek.SATURDAY || bookDay.getDayOfWeek() == DayOfWeek.SUNDAY) {
            bookDay = bookDay.plusDays(1);
        }
        final LocalDate bookedDate = bookDay;
        Instant bStart = bookedDate.atTime(10, 0).atZone(VN).toInstant();
        Instant bEnd = bookedDate.atTime(11, 0).atZone(VN).toInstant();
        UUID bookingId = UUID.randomUUID();
        assertThat(providerService.allocateTechnicianForBooking(
                UUID.randomUUID(), "reass-" + bookingId, bookingId,
                ProviderDataInitializer.SERVICE_AC_CLEAN_ID, "AC",
                bStart, bEnd, ProviderDataInitializer.PROVIDER_1_ID, true, techA.getId()).allocated()).isTrue();

        assertThatThrownBy(() -> recurringService.upsertExceptions(
                techA.getId().toString(), actor,
                new UpsertScheduleExceptionsRequest(bookedDate.minusDays(1), bookedDate.plusDays(1),
                        ScheduleExceptionType.UNAVAILABLE, "3-day leave", List.of())))
                .isInstanceOf(ScheduleAllocationConflictException.class);
        // Atomic rollback: zero exceptions persisted for any of the 3 days
        assertThat(exceptionRepository.findByTechnicianIdAndLocalDateBetweenOrderByLocalDateAscStartTimeAsc(
                techA.getId(), bookedDate.minusDays(1), bookedDate.plusDays(1))).isEmpty();

        // 4. Reassign booking from techA to techB under EXCLUDE USING gist (AC-SCH-017)
        var reassigned = providerService.reassignTechnicianForBooking(
                UUID.randomUUID(), bookingId, techB.getId(), ProviderDataInitializer.PROVIDER_1_ID, "Rebalance");
        assertThat(reassigned.allocated()).isTrue();
        assertThat(reassigned.technicianId()).isEqualTo(techB.getId());

        // 5. Mark bookedDate UNAVAILABLE on techA -> legacy POST /schedules MUST be rejected on leave day (Section 6)
        recurringService.upsertExceptions(
                techA.getId().toString(), actor,
                new UpsertScheduleExceptionsRequest(bookedDate, bookedDate, ScheduleExceptionType.UNAVAILABLE, "Off", List.of()));
        assertThatThrownBy(() -> providerService.createScheduleWindow(
                techA.getId().toString(), actor,
                new CreateScheduleWindowRequest(bStart, bEnd)))
                .isInstanceOf(DomainException.class);

        // 6. End weekly schedule on techA (AC-SCH-008) & verify future recurring windows after cutDate are removed
        Long latestVer = v2.upcomingRules().getLast().version();
        recurringService.endWeeklySchedule(techA.getId().toString(), actor, EtagSupport.format(latestVer), today);
        assertThat(windowRepository.findByTechnicianIdOrderByStartsAtAsc(techA.getId()))
                .noneMatch(w -> "RECURRING".equals(w.getSource())
                        && w.getStartsAt().atZone(VN).toLocalDate().isAfter(today));

        // 7. Direct PostgreSQL EXCLUDE USING gist proof (SQLSTATE 23P01)
        Instant overlapStart = today.plusDays(25).atTime(9, 0).atZone(VN).toInstant();
        Instant overlapEnd = today.plusDays(25).atTime(11, 0).atZone(VN).toInstant();
        windowRepository.saveAndFlush(ScheduleWindow.builder()
                .id(UUID.randomUUID()).technicianId(techA.getId())
                .startsAt(overlapStart).endsAt(overlapEnd)
                .status("AVAILABLE").source("MANUAL").createdAt(Instant.now()).build());
        assertThatThrownBy(() -> windowRepository.saveAndFlush(ScheduleWindow.builder()
                .id(UUID.randomUUID()).technicianId(techA.getId())
                .startsAt(overlapStart.plusSeconds(1800)).endsAt(overlapEnd.plusSeconds(1800))
                .status("AVAILABLE").source("MANUAL").createdAt(Instant.now()).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void restoresManualBookingWithoutWeeklyRulePreventsPatchedRecurringRegenerationAndAppliesCombinedPrecedence() {
        ActorContext actor = ownerActor(ProviderDataInitializer.PROVIDER_1_ID);
        LocalDate today = LocalDate.now(VN);
        LocalDate workDay = today.plusDays(4);
        while (workDay.getDayOfWeek() == DayOfWeek.SATURDAY || workDay.getDayOfWeek() == DayOfWeek.SUNDAY) {
            workDay = workDay.plusDays(1);
        }
        final LocalDate targetDay = workDay;

        // P1.1: Technician WITHOUT any weekly schedule, using MANUAL 08:00-17:00
        Technician manualTech = createFreshTechnician(ProviderDataInitializer.PROVIDER_1_ID);
        Instant dayStart = targetDay.atTime(8, 0).atZone(VN).toInstant();
        Instant dayEnd = targetDay.atTime(17, 0).atZone(VN).toInstant();
        providerService.createScheduleWindow(manualTech.getId().toString(), actor,
                new CreateScheduleWindowRequest(dayStart, dayEnd));

        UUID manualBookingId = UUID.randomUUID();
        assertThat(providerService.allocateTechnicianForBooking(
                UUID.randomUUID(), "man-" + manualBookingId, manualBookingId,
                ProviderDataInitializer.SERVICE_AC_CLEAN_ID, "AC",
                targetDay.atTime(10, 0).atZone(VN).toInstant(),
                targetDay.atTime(12, 0).atZone(VN).toInstant(),
                ProviderDataInitializer.PROVIDER_1_ID, true, manualTech.getId()).allocated()).isTrue();

        // Cancel/release booking -> MUST restore full merged 08:00-17:00 MANUAL window!
        providerService.releaseBooking(manualBookingId);
        List<ScheduleWindow> restoredManual = windowRepository.findByTechnicianIdOrderByStartsAtAsc(manualTech.getId());
        assertThat(restoredManual).hasSize(1);
        assertThat(restoredManual.getFirst().getStartsAt()).isEqualTo(dayStart);
        assertThat(restoredManual.getFirst().getEndsAt()).isEqualTo(dayEnd);
        assertThat(restoredManual.getFirst().getSource()).isEqualTo("MANUAL");

        // P1.2: Legacy PATCH shrinking a RECURRING slot 08:00-17:00 -> 09:00-16:00 must NOT regenerate 08-09 or 16-17
        Technician patchTech = createFreshTechnician(ProviderDataInitializer.PROVIDER_1_ID);
        recurringService.upsertWeeklySchedule(
                patchTech.getId().toString(), actor, null,
                new UpsertWeeklyScheduleRequest("Asia/Ho_Chi_Minh", today, null, List.of(
                        new DayTimeIntervalDto(targetDay.getDayOfWeek(), LocalTime.of(8, 0), LocalTime.of(17, 0)))));
        ScheduleWindow recurringSlot = windowRepository.findByTechnicianIdOrderByStartsAtAsc(patchTech.getId()).stream()
                .filter(w -> w.getStartsAt().atZone(VN).toLocalDate().equals(targetDay))
                .findFirst().orElseThrow();
        providerService.updateScheduleWindow(
                patchTech.getId().toString(), actor, recurringSlot.getId(),
                EtagSupport.format(recurringSlot.getVersion()),
                new UpdateScheduleWindowRequest(
                        targetDay.atTime(9, 0).atZone(VN).toInstant(),
                        targetDay.atTime(16, 0).atZone(VN).toInstant(),
                        null));
        worker.replenishAllActiveTechnicians("AFTER_PATCH");
        List<ScheduleWindow> afterPatchDay = windowRepository.findByTechnicianIdOrderByStartsAtAsc(patchTech.getId()).stream()
                .filter(w -> w.getStartsAt().atZone(VN).toLocalDate().equals(targetDay))
                .toList();
        assertThat(afterPatchDay).hasSize(1);
        assertThat(afterPatchDay.getFirst().getStartsAt()).isEqualTo(targetDay.atTime(9, 0).atZone(VN).toInstant());
        assertThat(afterPatchDay.getFirst().getEndsAt()).isEqualTo(targetDay.atTime(16, 0).atZone(VN).toInstant());

        // P1.3: Combined CUSTOM_HOURS (09-12) + EXTRA_HOURS (18-20) - UNAVAILABLE (10-11) => [09-10, 11-12, 18-20]
        LocalDate comboDay = targetDay.plusDays(7);
        Instant now = Instant.now();
        exceptionRepository.saveAllAndFlush(List.of(
                ScheduleException.builder().id(UUID.randomUUID()).technicianId(patchTech.getId())
                        .localDate(comboDay).exceptionType(ScheduleExceptionType.CUSTOM_HOURS)
                        .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(12, 0)).createdAt(now).updatedAt(now).build(),
                ScheduleException.builder().id(UUID.randomUUID()).technicianId(patchTech.getId())
                        .localDate(comboDay).exceptionType(ScheduleExceptionType.EXTRA_HOURS)
                        .startTime(LocalTime.of(18, 0)).endTime(LocalTime.of(20, 0)).createdAt(now).updatedAt(now).build(),
                ScheduleException.builder().id(UUID.randomUUID()).technicianId(patchTech.getId())
                        .localDate(comboDay).exceptionType(ScheduleExceptionType.UNAVAILABLE)
                        .startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(11, 0)).createdAt(now).updatedAt(now).build()));
        reconciliationService.reconcileHorizon(patchTech.getId());
        List<ScheduleWindow> comboWindows = windowRepository.findByTechnicianIdOrderByStartsAtAsc(patchTech.getId()).stream()
                .filter(w -> w.getStartsAt().atZone(VN).toLocalDate().equals(comboDay))
                .toList();
        assertThat(comboWindows).extracting(
                w -> w.getStartsAt().atZone(VN).toLocalTime() + "-" + w.getEndsAt().atZone(VN).toLocalTime())
                .containsExactly("09:00-10:00", "11:00-12:00", "18:00-20:00");
    }

    @Test
    void verifiesPre005UpgradeMigrationPreservesLegacyDataAndRejectsLegacyOverlaps(@Autowired DataSource dataSource) throws Exception {
        String migrationSql = new String(
                getClass().getClassLoader()
                        .getResourceAsStream("db/migration/V20261127_01__technician_recurring_schedule.sql")
                        .readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);

        // Case A: Clean upgrade from pre-005 schema with adjacent manual slots + BOOKED slot
        try (var conn = dataSource.getConnection(); var st = conn.createStatement()) {
            try {
                st.execute("CREATE SCHEMA IF NOT EXISTS pre005_ok");
                st.execute("SET search_path TO pre005_ok, public");
                st.execute("""
                        CREATE TABLE technicians (id UUID PRIMARY KEY);
                        CREATE TABLE schedule_windows (
                            id UUID PRIMARY KEY,
                            technician_id UUID NOT NULL,
                            starts_at TIMESTAMPTZ NOT NULL,
                            ends_at TIMESTAMPTZ NOT NULL,
                            status VARCHAR(32) NOT NULL,
                            allocated_booking_id UUID,
                            version BIGINT DEFAULT 0,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                        );
                        """);
                UUID tId = UUID.randomUUID();
                UUID bId = UUID.randomUUID();
                st.execute("INSERT INTO technicians (id) VALUES ('" + tId + "')");
                st.execute("INSERT INTO schedule_windows (id, technician_id, starts_at, ends_at, status) VALUES ('"
                        + UUID.randomUUID() + "', '" + tId + "', '2026-12-01T01:00:00Z', '2026-12-01T03:00:00Z', 'AVAILABLE')");
                st.execute("INSERT INTO schedule_windows (id, technician_id, starts_at, ends_at, status, allocated_booking_id) VALUES ('"
                        + UUID.randomUUID() + "', '" + tId + "', '2026-12-01T03:00:00Z', '2026-12-01T05:00:00Z', 'BOOKED', '" + bId + "')");

                st.execute(migrationSql);

                try (var rs = st.executeQuery("SELECT count(*), count(*) FILTER (WHERE source = 'MANUAL'), count(*) FILTER (WHERE allocated_booking_id IS NOT NULL) FROM schedule_windows")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(2);
                    assertThat(rs.getInt(2)).isEqualTo(2);
                    assertThat(rs.getInt(3)).isEqualTo(1);
                }
            } finally {
                st.execute("SET search_path TO public");
                st.execute("DROP SCHEMA IF EXISTS pre005_ok CASCADE");
            }
        }

        // Case B: Pre-005 schema with overlapping windows MUST fail loudly (never silently delete data)
        try (var conn = dataSource.getConnection(); var st = conn.createStatement()) {
            try {
                st.execute("CREATE SCHEMA IF NOT EXISTS pre005_bad");
                st.execute("SET search_path TO pre005_bad, public");
                st.execute("""
                        CREATE TABLE technicians (id UUID PRIMARY KEY);
                        CREATE TABLE schedule_windows (
                            id UUID PRIMARY KEY,
                            technician_id UUID NOT NULL,
                            starts_at TIMESTAMPTZ NOT NULL,
                            ends_at TIMESTAMPTZ NOT NULL,
                            status VARCHAR(32) NOT NULL,
                            allocated_booking_id UUID,
                            version BIGINT DEFAULT 0,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                        );
                        """);
                UUID tId = UUID.randomUUID();
                st.execute("INSERT INTO technicians (id) VALUES ('" + tId + "')");
                st.execute("INSERT INTO schedule_windows (id, technician_id, starts_at, ends_at, status) VALUES ('"
                        + UUID.randomUUID() + "', '" + tId + "', '2026-12-01T01:00:00Z', '2026-12-01T04:00:00Z', 'AVAILABLE')");
                st.execute("INSERT INTO schedule_windows (id, technician_id, starts_at, ends_at, status) VALUES ('"
                        + UUID.randomUUID() + "', '" + tId + "', '2026-12-01T03:00:00Z', '2026-12-01T05:00:00Z', 'AVAILABLE')");

                assertThatThrownBy(() -> st.execute(migrationSql))
                        .isInstanceOf(java.sql.SQLException.class)
                        .hasMessageContaining("ex_schedule_windows_non_overlapping");
            } finally {
                st.execute("SET search_path TO public");
                st.execute("DROP SCHEMA IF EXISTS pre005_bad CASCADE");
            }
        }
    }
}
