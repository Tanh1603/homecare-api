package com.homecare.provider.service;

import static java.time.temporal.ChronoUnit.DAYS;
import static java.time.temporal.ChronoUnit.SECONDS;
import static org.assertj.core.api.Assertions.*;

import com.homecare.provider.dto.ProviderDtos.CreateScheduleWindowRequest;
import com.homecare.provider.repository.ProviderRepository;
import com.homecare.provider.repository.ScheduleWindowRepository;
import com.homecare.provider.repository.TechnicianRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:allocation_window_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class AllocationWindowTest {
    @Autowired ProviderDomainService service;
    @Autowired ScheduleWindowRepository windows;
    @Autowired ProviderRepository providers;
    @MockitoSpyBean TechnicianRepository technicians;
    @Autowired ApplicationContext context;
    @Test void allocationMarksOnlyTheBookedInterval() {
        Instant start = Instant.now().truncatedTo(SECONDS).plusSeconds(7200);
        UUID booking = UUID.randomUUID();
        var allocated = service.allocateTechnicianForBooking("test-" + booking, booking,
                ProviderDataInitializer.SERVICE_AC_CLEAN_ID, "AC", start, start.plusSeconds(1800));
        assertThat(allocated.allocated()).isTrue();
        assertThat(windows.findByTechnicianIdOrderByStartsAtAsc(allocated.technicianId()))
                .anySatisfy(w -> {
                    assertThat(w.getAllocatedBookingId()).isEqualTo(booking);
                    assertThat(w.getStartsAt()).isEqualTo(start);
                    assertThat(w.getEndsAt()).isEqualTo(start.plusSeconds(1800));
                    assertThat(w.getStatus()).isEqualTo("BOOKED");
                });
        assertThat(windows.findAvailableCovering(allocated.technicianId(), start.plusSeconds(1800), start.plusSeconds(3600))).isNotEmpty();
    }

    @Test void terminalEventReleasesBookingOnlyOnce() {
        Instant start = Instant.now().plusSeconds(10800);
        UUID booking = UUID.randomUUID();
        var allocated = service.allocateTechnicianForBooking("release-" + booking, booking,
                ProviderDataInitializer.SERVICE_AC_CLEAN_ID, "AC", start, start.plusSeconds(1800));
        assertThat(allocated.allocated()).isTrue();
        int load = service.getTechnician(allocated.technicianId()).activeAllocations();
        var consumer = context.getBean(ProviderBookingConsumer.class);
        String event = "{\"eventType\":\"booking.cancelled.v1\",\"data\":{\"bookingId\":\"" + booking + "\"}}";
        consumer.onEvent(event); consumer.onEvent(event);
        assertThat(service.getTechnician(allocated.technicianId()).activeAllocations()).isEqualTo(load - 1);
        assertThat(windows.findByTechnicianIdOrderByStartsAtAsc(allocated.technicianId()))
                .noneMatch(w -> booking.equals(w.getAllocatedBookingId()));
    }

    @Test void completedBookingKeepsItsSlotBookedAndFreesTechnicianLoadOnce() {
        Instant start = Instant.now().plusSeconds(14400);
        UUID booking = UUID.randomUUID();
        var allocated = service.allocateTechnicianForBooking("complete-" + booking, booking,
                ProviderDataInitializer.SERVICE_AC_CLEAN_ID, "AC", start, start.plusSeconds(1800));
        assertThat(allocated.allocated()).isTrue();
        int load = service.getTechnician(allocated.technicianId()).activeAllocations();
        var consumer = context.getBean(ProviderBookingConsumer.class);
        String event = "{\"eventType\":\"booking.completed.v1\",\"data\":{\"bookingId\":\"" + booking + "\"}}";
        consumer.onEvent(event); consumer.onEvent(event);
        assertThat(service.getTechnician(allocated.technicianId()).activeAllocations()).isEqualTo(load - 1);
        assertThat(windows.findByTechnicianIdOrderByStartsAtAsc(allocated.technicianId()))
                .anySatisfy(w -> {
                    assertThat(w.getAllocatedBookingId()).isEqualTo(booking);
                    assertThat(w.getStatus()).isEqualTo("BOOKED");
                });
    }

    @Test void releasedIntervalCanBeBookedTogetherWithItsAdjacentFreeTime() {
        Instant start = Instant.now().truncatedTo(SECONDS).plus(45, DAYS);
        service.createScheduleWindow("me", "demo-technician",
                new CreateScheduleWindowRequest(start, start.plusSeconds(3600)), false);
        UUID booking = UUID.randomUUID();
        var allocated = service.allocateTechnicianForBooking("merge-" + booking, booking,
                ProviderDataInitializer.SERVICE_AC_CLEAN_ID, "AC", start, start.plusSeconds(1800),
                ProviderDataInitializer.PROVIDER_1_ID, true, ProviderDataInitializer.DEMO_TECH_ID);
        assertThat(allocated.allocated()).isTrue();
        service.releaseBooking(booking);
        assertThat(windows.findAvailableCovering(allocated.technicianId(), start, start.plusSeconds(3600))).isNotEmpty();
    }

    @Test void suspendedProviderCannotReceiveManualAllocation() {
        var provider = providers.findById(ProviderDataInitializer.PROVIDER_1_ID).orElseThrow();
        provider.setStatus("INACTIVE"); provider = providers.saveAndFlush(provider);
        try {
            Instant start = Instant.now().plusSeconds(21600);
            UUID booking = UUID.randomUUID();
            var result = service.allocateTechnicianForBooking("suspended-" + booking, booking,
                    ProviderDataInitializer.SERVICE_AC_CLEAN_ID, "AC", start, start.plusSeconds(1800),
                    provider.getId(), true, ProviderDataInitializer.DEMO_TECH_ID);
            assertThat(result.allocated()).isFalse();
        } finally {
            provider.setStatus("ACTIVE"); providers.saveAndFlush(provider);
        }
    }

    @Test void fallsBackToSecondTechnicianWhenFirstSlotIsTakenAfterFilter() {
        Instant start = Instant.now().plus(20, DAYS);
        Instant end = start.plusSeconds(1800);
        UUID stealingBookingId = UUID.randomUUID();
        var realTech1 = technicians.findById(ProviderDataInitializer.DEMO_TECH_ID).orElseThrow();
        Mockito.doAnswer(inv -> {
            for (var w : windows.findAvailableCovering(ProviderDataInitializer.DEMO_TECH_ID, start, end)) {
                w.setStatus("BOOKED");
                w.setAllocatedBookingId(stealingBookingId);
                windows.saveAndFlush(w);
            }
            return java.util.Optional.of(realTech1);
        }).when(technicians).lockByIdNoWait(ProviderDataInitializer.DEMO_TECH_ID);

        try {
            UUID bookingId = UUID.randomUUID();
            var result = service.allocateTechnicianForBooking("cand-race-" + bookingId, bookingId,
                    ProviderDataInitializer.SERVICE_AC_CLEAN_ID, "AC", start, end);
            assertThat(result.allocated()).isTrue();
            assertThat(result.technicianId()).isNotEqualTo(ProviderDataInitializer.DEMO_TECH_ID);
        } finally {
            Mockito.reset(technicians);
            for (var w : windows.findByTechnicianIdOrderByStartsAtAsc(ProviderDataInitializer.DEMO_TECH_ID)) {
                if (stealingBookingId.equals(w.getAllocatedBookingId())) {
                    w.setStatus("AVAILABLE");
                    w.setAllocatedBookingId(null);
                    windows.saveAndFlush(w);
                }
            }
        }
    }

    @Test void fallsBackToSecondTechnicianWhenFirstIsLockedNoWait() {
        Instant start = Instant.now().plus(25, DAYS);
        Instant end = start.plusSeconds(1800);
        assertThat(windows.findAvailableCovering(ProviderDataInitializer.DEMO_TECH_ID, start, end)).isNotEmpty();

        Mockito.doThrow(new CannotAcquireLockException("locked")).when(technicians)
                .lockByIdNoWait(ProviderDataInitializer.DEMO_TECH_ID);

        try {
            UUID bookingId = UUID.randomUUID();
            var result = service.allocateTechnicianForBooking("cand-nowait-" + bookingId, bookingId,
                    ProviderDataInitializer.SERVICE_AC_CLEAN_ID, "AC", start, end);
            assertThat(result.allocated()).isTrue();
            assertThat(result.technicianId()).isNotEqualTo(ProviderDataInitializer.DEMO_TECH_ID);
            Mockito.verify(technicians).lockByIdNoWait(ProviderDataInitializer.DEMO_TECH_ID);
        } finally {
            Mockito.reset(technicians);
        }
    }

    @Test void suspendedProviderOfferingCannotAllocateAnotherProviderWithoutStrictMatch() {
        var provider = providers.findById(ProviderDataInitializer.PROVIDER_1_ID).orElseThrow();
        provider.setStatus("INACTIVE");
        provider = providers.saveAndFlush(provider);
        try {
            Instant start = Instant.now().plus(26, DAYS);
            UUID booking = UUID.randomUUID();
            var result = service.allocateTechnicianForBooking("suspended-nostrict-" + booking, booking,
                    ProviderDataInitializer.SERVICE_AC_CLEAN_ID, "AC", start, start.plusSeconds(1800),
                    null, false, null);
            assertThat(result.allocated()).isFalse();
            assertThat(result.failureReason()).isEqualTo("SERVICE_NOT_AVAILABLE");
        } finally {
            provider.setStatus("ACTIVE");
            providers.saveAndFlush(provider);
        }
    }

    @Test void bookingConsumerIgnoresUnknownEventWithoutBookingId() {
        var consumer = context.getBean(ProviderBookingConsumer.class);
        assertThatCode(() -> consumer.onEvent("{\"eventType\":\"booking.created.v1\",\"data\":{}}"))
                .doesNotThrowAnyException();
        assertThatCode(() -> consumer.onEvent("{\"eventType\":\"booking.completed.v1\",\"data\":{\"bookingId\":\"not-a-uuid\"}}"))
                .doesNotThrowAnyException();
    }
}
