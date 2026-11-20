package com.homecare.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.homecare.common.error.DomainException;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.web.EtagSupport;
import com.homecare.provider.dto.ProviderDtos.CoverageAreaItem;
import com.homecare.provider.dto.ProviderDtos.CreateScheduleWindowRequest;
import com.homecare.provider.dto.ProviderDtos.OfferingInput;
import com.homecare.provider.dto.ProviderDtos.ScheduleWindowResponse;
import com.homecare.provider.dto.ProviderDtos.ServiceOfferingResponse;
import com.homecare.provider.dto.ProviderDtos.UpdateScheduleWindowRequest;
import com.homecare.provider.dto.ProviderDtos.UpsertServiceCoverageRequest;
import com.homecare.provider.entity.ServicePriceType;
import com.homecare.provider.service.ProviderDomainService.AllocationOutcome;

@SpringBootTest
@ActiveProfiles("test")
class ProviderDomainServiceTest {

    @Autowired
    private ProviderDomainService service;

    @Test
    void seedsCatalogRanksHighestScoreTechnicianAndEnforcesIdempotentIntentAndOverlapGuard() {
        assertThat(service.offeringsPage(null, null, null, 1, 20).pagination().totalItems()).isGreaterThanOrEqualTo(6);
        assertThat(service.providersPage(null, null, 1, 20).pagination().totalItems()).isGreaterThanOrEqualTo(2);

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
                personalActor("demo-technician"),
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

    @Test
    void rejectsCrossTechnicianScheduleMutationAndMeDemoFallbackAndInvalidUpdate() {
        Instant s1 = Instant.now().plus(40, ChronoUnit.DAYS);
        Instant e1 = s1.plus(2, ChronoUnit.HOURS);

        assertThatThrownBy(() -> service.createScheduleWindow(
                "me", personalActor("unknown-user-sub"), new CreateScheduleWindowRequest(s1, e1)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("TECHNICIAN_NOT_FOUND");

        Instant s2 = Instant.now().plus(41, ChronoUnit.DAYS);
        Instant e2 = s2.plus(2, ChronoUnit.HOURS);
        assertThatThrownBy(() -> service.createScheduleWindow(
                ProviderDataInitializer.DEMO_TECH_ID.toString(),
                personalActor("stranger-tech-sub"),
                new CreateScheduleWindowRequest(s2, e2)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("RESOURCE_FORBIDDEN");

        Instant s3 = Instant.now().plus(42, ChronoUnit.DAYS);
        Instant e3 = s3.plus(2, ChronoUnit.HOURS);
        ScheduleWindowResponse win = service.createScheduleWindow(
                "me", personalActor("demo-technician"), new CreateScheduleWindowRequest(s3, e3));

        assertThatThrownBy(() -> service.updateScheduleWindow(
                "me",
                personalActor("demo-technician"),
                win.id(),
                EtagSupport.format(win.version()),
                new UpdateScheduleWindowRequest(win.endsAt(), win.startsAt(), null)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("VALIDATION_FAILED");

        assertThatThrownBy(() -> service.updateScheduleWindow(
                "me",
                personalActor("demo-technician"),
                win.id(),
                EtagSupport.format(win.version()),
                new UpdateScheduleWindowRequest(null, null, "HACKED_STATUS")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void eligibleServicesFiltersCoverageBeforeRankAndEnforcesInspectionFeeBounds() {
        ActorContext adminActor = new ActorContext(
                "admin-user",
                AccessMode.PERSONAL,
                null,
                java.util.Set.of(RbacConstants.ROLE_ADMIN),
                java.util.Set.of(),
                java.util.Set.of(),
                java.util.Set.of(RbacConstants.PERM_SERVICE_MANAGE));

        // 1. Out-of-bounds inspectionFee (> 500,000 VND) rejected
        assertThatThrownBy(() -> service.saveOffering(
                null,
                new OfferingInput(
                        ProviderDataInitializer.PROVIDER_1_ID,
                        "Khảo sát sửa ống nước",
                        "PLUMBING_TEST",
                        "Test fee bounds",
                        150_000L,
                        60,
                        true,
                        ServicePriceType.QUOTE_REQUIRED,
                        600_000L),
                adminActor))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("VALIDATION_FAILED");

        // 2. Provider 1 offering in Province 79 (HCM); Provider 2 offering in Ward 26734 (HCM)
        ServiceOfferingResponse p1Offering = service.saveOffering(
                null,
                new OfferingInput(
                        ProviderDataInitializer.PROVIDER_1_ID,
                        "Sửa máy lạnh HCM Tỉnh",
                        "AC_COVERAGE_TEST",
                        "Province coverage",
                        200_000L,
                        60,
                        true,
                        ServicePriceType.QUOTE_REQUIRED,
                        120_000L),
                adminActor);
        assertThat(p1Offering.priceType()).isEqualTo(ServicePriceType.QUOTE_REQUIRED);
        assertThat(p1Offering.inspectionFee()).isEqualTo(120_000L);
        assertThat(p1Offering.priceVersion()).isEqualTo(1);

        ServiceOfferingResponse p2Offering = service.saveOffering(
                null,
                new OfferingInput(
                        ProviderDataInitializer.PROVIDER_2_ID,
                        "Sửa máy lạnh Phường Bến Nghé",
                        "AC_COVERAGE_TEST",
                        "Ward coverage",
                        220_000L,
                        60,
                        true,
                        ServicePriceType.FIXED,
                        null),
                adminActor);

        service.replaceOfferingCoverage(
                p1Offering.id(),
                new UpsertServiceCoverageRequest(List.of(new CoverageAreaItem("79", "PROVINCE", true))),
                adminActor);
        service.replaceOfferingCoverage(
                p2Offering.id(),
                new UpsertServiceCoverageRequest(List.of(new CoverageAreaItem("26734", "WARD", true))),
                adminActor);

        // Querying HCM + Ward 26734 returns both, with exact WARD match ranked before PROVINCE match
        List<ServiceOfferingResponse> hcmEligible = service.listEligibleOfferings("AC_COVERAGE_TEST", "79", "26734");
        assertThat(hcmEligible).extracting(ServiceOfferingResponse::id)
                .containsExactly(p2Offering.id(), p1Offering.id());

        // Querying Hanoi (Province 01) excludes both HCM offerings
        assertThat(service.listEligibleOfferings("AC_COVERAGE_TEST", "01", "00001")).isEmpty();

        // Updating price increments priceVersion
        ServiceOfferingResponse updatedP1 = service.saveOffering(
                p1Offering.id(),
                new OfferingInput(null, null, null, null, 250_000L, null, null, null, 150_000L),
                adminActor);
        assertThat(updatedP1.priceVersion()).isEqualTo(2);
    }

    private static ActorContext personalActor(String userId) {
        return new ActorContext(userId, AccessMode.PERSONAL, null,
                java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), java.util.Set.of());
    }
}
