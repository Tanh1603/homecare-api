package com.homecare.provider.service;

import static java.time.temporal.ChronoUnit.DAYS;
import static java.time.temporal.ChronoUnit.SECONDS;
import static org.assertj.core.api.Assertions.*;

import com.homecare.provider.dto.ProviderDtos.CreateScheduleWindowRequest;
import com.homecare.provider.repository.ProviderRepository;
import com.homecare.provider.repository.ScheduleWindowRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AllocationWindowTest {
    @Autowired ProviderDomainService service;
    @Autowired ScheduleWindowRepository windows;
    @Autowired ProviderRepository providers;
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

    @Test void terminalEventReleasesBookingOnlyOnce() throws Exception {
        Instant start = Instant.now().plusSeconds(10800);
        UUID booking = UUID.randomUUID();
        var allocated = service.allocateTechnicianForBooking("release-" + booking, booking,
                ProviderDataInitializer.SERVICE_AC_CLEAN_ID, "AC", start, start.plusSeconds(1800));
        assertThat(allocated.allocated()).isTrue();
        int load = service.getTechnician(allocated.technicianId()).activeAllocations();
        assertThat(context.containsBean("providerBookingConsumer")).isTrue();
        Object consumer = context.getBean("providerBookingConsumer");
        var receive = consumer.getClass().getMethod("onEvent", String.class);
        String event = "{\"eventType\":\"booking.cancelled.v1\",\"data\":{\"bookingId\":\"" + booking + "\"}}";
        receive.invoke(consumer, event); receive.invoke(consumer, event);
        assertThat(service.getTechnician(allocated.technicianId()).activeAllocations()).isEqualTo(load - 1);
        assertThat(windows.findByTechnicianIdOrderByStartsAtAsc(allocated.technicianId()))
                .noneMatch(w -> booking.equals(w.getAllocatedBookingId()));
    }

    @Test void releasedIntervalCanBeBookedTogetherWithItsAdjacentFreeTime() {
        Instant start = Instant.now().truncatedTo(SECONDS).plus(45, DAYS);
        service.createScheduleWindow("me", "demo-technician",
                new CreateScheduleWindowRequest(start, start.plusSeconds(3600)));
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
        provider.setStatus("INACTIVE"); providers.saveAndFlush(provider);
        Instant start = Instant.now().plusSeconds(21600);
        UUID booking = UUID.randomUUID();
        var result = service.allocateTechnicianForBooking("suspended-" + booking, booking,
                ProviderDataInitializer.SERVICE_AC_CLEAN_ID, "AC", start, start.plusSeconds(1800),
                provider.getId(), true, ProviderDataInitializer.DEMO_TECH_ID);
        assertThat(result.allocated()).isFalse();
    }
}
