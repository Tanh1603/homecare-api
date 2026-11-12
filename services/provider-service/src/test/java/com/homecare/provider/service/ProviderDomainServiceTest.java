package com.homecare.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.homecare.common.error.DomainException;
import com.homecare.provider.dto.ProviderDtos.CreateScheduleWindowRequest;
import com.homecare.provider.service.ProviderDomainService.AllocationOutcome;

@SpringBootTest
@ActiveProfiles("test")
class ProviderDomainServiceTest {

    @Autowired
    private ProviderDomainService service;

    @Test
    void seedsCatalogRanksHighestScoreTechnicianAndEnforcesIdempotentIntentAndOverlapGuard() {
        assertThat(service.listOfferings(null)).hasSizeGreaterThanOrEqualTo(6);
        assertThat(service.listProviders()).hasSizeGreaterThanOrEqualTo(2);

        Instant start = Instant.now().plus(2, ChronoUnit.HOURS);
        Instant end = start.plus(60, ChronoUnit.MINUTES);
        UUID bookingId = UUID.randomUUID();
        String intentId = "intent-" + bookingId;

        AllocationOutcome outcome1 = service.allocateTechnicianForBooking(
                intentId,
                bookingId,
                ProviderDataInitializer.SERVICE_AC_CLEAN_ID,
                "AC",
                start,
                end);
        assertThat(outcome1.allocated()).isTrue();
        assertThat(outcome1.technicianId()).isEqualTo(ProviderDataInitializer.DEMO_TECH_ID);

        AllocationOutcome outcome2 = service.allocateTechnicianForBooking(
                intentId,
                bookingId,
                ProviderDataInitializer.SERVICE_AC_CLEAN_ID,
                "AC",
                start,
                end);
        assertThat(outcome2.allocationId()).isEqualTo(outcome1.allocationId());

        assertThatThrownBy(() -> service.createScheduleWindow(
                "me",
                "demo-technician",
                new CreateScheduleWindowRequest(start, end)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("SCHEDULE_OVERLAP");
    }

    @Test
    void enforcesStrictProviderLockOnWarrantyClaimAndStoreAffinityOnStandardBooking() {
        Instant start = Instant.now().plus(8, ChronoUnit.HOURS);
        Instant end = start.plus(60, ChronoUnit.MINUTES);

        // Flow 1: Warranty claim locked to Organization 2 (PROVIDER_2_ID)
        UUID warrantyBookingId = UUID.randomUUID();
        AllocationOutcome strictOrg2 = service.allocateTechnicianForBooking(
                "intent-warranty-" + warrantyBookingId,
                warrantyBookingId,
                ProviderDataInitializer.SERVICE_AC_REPAIR_ID,
                "AC",
                start,
                end,
                ProviderDataInitializer.PROVIDER_2_ID,
                true);
        assertThat(strictOrg2.allocated()).isTrue();
        assertThat(strictOrg2.providerId()).isEqualTo(ProviderDataInitializer.PROVIDER_2_ID);

        // If Organization 2's only technician is already allocated in [start, end], strictProviderMatch=true must NOT fallback to Organization 1
        UUID secondWarrantyBookingId = UUID.randomUUID();
        AllocationOutcome strictBusy = service.allocateTechnicianForBooking(
                "intent-warranty-" + secondWarrantyBookingId,
                secondWarrantyBookingId,
                ProviderDataInitializer.SERVICE_AC_REPAIR_ID,
                "AC",
                start,
                end,
                ProviderDataInitializer.PROVIDER_2_ID,
                true);
        assertThat(strictBusy.allocated()).isFalse();
        assertThat(strictBusy.providerId()).isEqualTo(ProviderDataInitializer.PROVIDER_2_ID);
        assertThat(strictBusy.failureReason()).isEqualTo("NO_AVAILABLE_TECHNICIAN_IN_WARRANTY_ORGANIZATION");

        // Flow 2/3: Standard paid booking with preferredProviderId = PROVIDER_2_ID CAN fallback to Organization 1 when Organization 2 is busy
        UUID paidBookingId = UUID.randomUUID();
        AllocationOutcome fallbackPaid = service.allocateTechnicianForBooking(
                "intent-paid-" + paidBookingId,
                paidBookingId,
                ProviderDataInitializer.SERVICE_AC_REPAIR_ID,
                "AC",
                start,
                end,
                ProviderDataInitializer.PROVIDER_2_ID,
                false);
        assertThat(fallbackPaid.allocated()).isTrue();
        assertThat(fallbackPaid.providerId()).isEqualTo(ProviderDataInitializer.PROVIDER_1_ID);
    }
}
