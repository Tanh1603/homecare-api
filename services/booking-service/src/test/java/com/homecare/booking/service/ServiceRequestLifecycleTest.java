package com.homecare.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.homecare.booking.client.HouseholdVerificationClient;
import com.homecare.booking.client.HouseholdVerificationClient.WarrantyCheckStatus;
import com.homecare.booking.client.ProviderGrpcClientAdapter;
import com.homecare.booking.entity.BookingEntity;
import com.homecare.booking.repository.BookingRepository;
import com.homecare.booking.repository.ServiceRequestRepository;
import com.homecare.booking.service.QuotationService.AcceptQuotationInput;
import com.homecare.booking.service.QuotationService.CreateQuotationInput;
import com.homecare.booking.service.QuotationService.QuotationItemInput;
import com.homecare.booking.service.QuotationService.QuotationResponse;
import com.homecare.booking.service.ServiceRequestService.CreateServiceRequestInput;
import com.homecare.booking.service.ServiceRequestService.SelectFallbackProviderInput;
import com.homecare.booking.service.ServiceRequestService.ServiceRequestResponse;
import com.homecare.common.error.DomainException;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;

@SpringBootTest
@ActiveProfiles("test")
class ServiceRequestLifecycleTest {

    @Autowired
    private ServiceRequestService serviceRequestService;

    @Autowired
    private QuotationService quotationService;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private ServiceRequestRepository serviceRequestRepository;

    @MockitoBean
    private HouseholdVerificationClient householdClient;

    @MockitoBean
    private ProviderGrpcClientAdapter providerGrpcClient;

    @Test
    void replacementRequestPersistsOrderLineOnRequestAndLinkedBooking() {
        UUID deviceId = UUID.randomUUID();
        UUID orderLineId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID offeringId = UUID.randomUUID();

        when(householdClient.verifyDeviceOrFallback(deviceId, "replacement-customer", "AC"))
                .thenReturn("AC");

        ServiceRequestResponse request = serviceRequestService.createRequest(
                "replacement-customer",
                "Replacement Customer",
                new CreateServiceRequestInput(
                        "PAID_REPAIR", "AC", offeringId, "FIXED", false, 0L,
                        deviceId, orderLineId, null, providerId, null,
                        "1 Replacement Street", "Replace incompatible part", null, null));

        var savedRequest = serviceRequestRepository.findById(request.id()).orElseThrow();
        assertThat(savedRequest.getOrderLineId()).isEqualTo(orderLineId);

        BookingEntity booking = serviceRequestService.createLinkedBooking(
                savedRequest, providerId, com.homecare.booking.entity.BookingPurpose.REPLACEMENT,
                250_000L, Instant.now());
        BookingEntity savedBooking = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(savedBooking.getOrderLineId()).isEqualTo(orderLineId);
        assertThat(savedBooking.getDeviceId()).isEqualTo(deviceId);
    }

    @Test
    void quoteRequiredCreatesDiagnosticBookingAppliesInspectionCreditAndEnforcesVersionedAccept() {
        String customer = "cust-sr-1";
        UUID providerId = UUID.randomUUID();
        UUID offeringId = UUID.randomUUID();

        // 1. High inspectionFee (> 500,000 VND) allowed with soft warning flag (D-04 / D-22)
        ServiceRequestResponse highFeeReq = serviceRequestService.createRequest(
                customer,
                "Nguyễn Văn A",
                new CreateServiceRequestInput(
                        "PAID_REPAIR",
                        "PLUMBING",
                        offeringId,
                        "QUOTE_REQUIRED",
                        true,
                        600_000L,
                        null,
                        null,
                        null,
                        providerId,
                        null,
                        "12 Lê Lợi, Phường Bến Nghé, TP. Hồ Chí Minh",
                        "Rò ống nước âm tường",
                        null,
                        null));
        assertThat(highFeeReq.inspectionFeeWarningFlag()).isTrue();

        // 2. Service-only QUOTE_REQUIRED with onsite survey (150,000 VND) and no deviceId
        ServiceRequestResponse created = serviceRequestService.createRequest(
                customer,
                "Nguyễn Văn A",
                new CreateServiceRequestInput(
                        "PAID_REPAIR",
                        "PLUMBING",
                        offeringId,
                        "QUOTE_REQUIRED",
                        true,
                        150_000L,
                        null,
                        null,
                        null,
                        providerId,
                        null,
                        "12 Lê Lợi, Phường Bến Nghé, TP. Hồ Chí Minh",
                        "Rò ống nước âm tường",
                        null,
                        null));
        assertThat(created.status()).isEqualTo("AWAITING_PROVIDER");
        assertThat(created.repairBookingId()).isNull();

        ActorContext providerActor = orgActor("provider-staff", providerId);
        ServiceRequestResponse acceptedByProvider = serviceRequestService.providerAccept(providerActor, created.id(), 0L);
        assertThat(acceptedByProvider.status()).isEqualTo("DIAGNOSTIC_BOOKED");
        assertThat(acceptedByProvider.diagnosticBookingId()).isNotNull();
        assertThat(acceptedByProvider.repairBookingId()).isNull();

        BookingEntity diagnosticBooking = bookingRepository.findById(acceptedByProvider.diagnosticBookingId()).orElseThrow();
        assertThat(diagnosticBooking.getPurpose()).isEqualTo("DIAGNOSTIC");
        assertThat(diagnosticBooking.getQuotedPrice()).isEqualTo(150_000L);

        // 3. Issue Quotation v1, then supersede with v2
        QuotationResponse q1 = quotationService.issueQuotation(
                providerActor,
                created.id(),
                new CreateQuotationInput(
                        List.of(new QuotationItemInput("LABOR", "Đục tường dò ống", 1, 300_000L)),
                        Instant.now().plus(2, ChronoUnit.DAYS),
                        true));
        assertThat(q1.version()).isEqualTo(1);

        QuotationResponse q2 = quotationService.issueQuotation(
                providerActor,
                created.id(),
                new CreateQuotationInput(
                        List.of(
                                new QuotationItemInput("LABOR", "Thay đoạn ống", 1, 400_000L),
                                new QuotationItemInput("MATERIAL", "Ống PPR", 2, 50_000L)),
                        Instant.now().plus(2, ChronoUnit.DAYS),
                        true,
                        "Cập nhật vật tư ống PPR"));
        assertThat(q2.version()).isEqualTo(2);
        assertThat(q2.itemsTotal()).isEqualTo(500_000L);
        assertThat(q2.inspectionFeeCredit()).isEqualTo(150_000L);
        assertThat(q2.totalDueAfterCredit()).isEqualTo(350_000L);

        // Accepting superseded v1 fails with CONFLICT
        assertThatThrownBy(() -> quotationService.acceptQuotation(
                customer, created.id(), q1.id(), new AcceptQuotationInput(1)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("CONFLICT");

        // Accepting v2 creates exactly 1 REPAIR Booking with totalDueAfterCredit = 350,000
        QuotationResponse acceptedQ2 = quotationService.acceptQuotation(
                customer, created.id(), q2.id(), new AcceptQuotationInput(2));
        assertThat(acceptedQ2.status()).isEqualTo("ACCEPTED");
        assertThat(acceptedQ2.repairBookingId()).isNotNull();

        BookingEntity repairBooking = bookingRepository.findById(acceptedQ2.repairBookingId()).orElseThrow();
        assertThat(repairBooking.getPurpose()).isEqualTo("REPAIR");
        assertThat(repairBooking.getQuotedPrice()).isEqualTo(350_000L);

        // Second accept fails with CONFLICT
        assertThatThrownBy(() -> quotationService.acceptQuotation(
                customer, created.id(), q2.id(), new AcceptQuotationInput(2)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("CONFLICT");
    }

    @Test
    void enforcesSixWarrantyFallbackRulesAndPaidRepairConsentForExpiredWarranty() {
        String customer = "cust-warranty-sr";
        UUID warrantyId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        UUID originalOrgId = UUID.randomUUID();
        UUID fallbackOrgId = UUID.randomUUID();

        // Active warranty -> Rule 1: only originalOrgId sees and can accept it
        when(householdClient.checkWarrantyStatus(warrantyId, customer))
                .thenReturn(new WarrantyCheckStatus(
                        true, false, warrantyId, deviceId, "AC", originalOrgId, "Original Store", "ACTIVE"));

        ServiceRequestResponse warrantyReq = serviceRequestService.createRequest(
                customer,
                "Trần Thị B",
                new CreateServiceRequestInput(
                        "WARRANTY_CLAIM",
                        "AC",
                        null,
                        "FIXED",
                        false,
                        0L,
                        deviceId,
                        null,
                        warrantyId,
                        null,
                        null,
                        "45 Nguyễn Huệ, TP. Hồ Chí Minh",
                        "Máy lạnh không mát",
                        null,
                        null));
        assertThat(warrantyReq.status()).isEqualTo("AWAITING_PROVIDER");
        assertThat(warrantyReq.assignedProviderId()).isEqualTo(originalOrgId);

        // Stranger org cannot see or accept it
        ActorContext strangerOrg = orgActor("stranger", fallbackOrgId);
        assertThat(serviceRequestService.listRequests(strangerOrg, 1, 20).items()).isEmpty();
        assertThatThrownBy(() -> serviceRequestService.providerAccept(strangerOrg, warrantyReq.id(), 0L))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("RESOURCE_FORBIDDEN");

        // Rule 2, 3, 5: Original provider rejects -> notifies customer, awaits customer fallback selection (no auto-transfer)
        ActorContext originalOrg = orgActor("orig-staff", originalOrgId);
        ServiceRequestResponse fallbackPending = serviceRequestService.triggerWarrantyFallback(
                originalOrg, warrantyReq.id(), "NO_TECHNICIAN_AVAILABLE");
        assertThat(fallbackPending.status()).isEqualTo("AWAITING_WARRANTY_FALLBACK_CONSENT");
        assertThat(fallbackPending.fallbackReason()).isEqualTo("NO_TECHNICIAN_AVAILABLE");
        assertThat(fallbackPending.repairBookingId()).isNull();

        // Rule 4: Customer chooses fallbackOrgId
        ServiceRequestResponse rerouted = serviceRequestService.customerSelectFallbackProvider(
                customer,
                warrantyReq.id(),
                new SelectFallbackProviderInput(fallbackOrgId, null, "FIXED", false, 0L));
        assertThat(rerouted.status()).isEqualTo("AWAITING_PROVIDER");
        assertThat(rerouted.assignedProviderId()).isEqualTo(fallbackOrgId);

        ServiceRequestResponse acceptedFallback = serviceRequestService.providerAccept(strangerOrg, warrantyReq.id(), 0L);
        assertThat(acceptedFallback.status()).isEqualTo("BOOKED");
        assertThat(acceptedFallback.repairBookingId()).isNotNull();

        // Rule 6: Expired warranty -> AWAITING_PAID_REPAIR_CONSENT (not free warranty, not silent paid booking)
        UUID expiredWarrantyId = UUID.randomUUID();
        when(householdClient.checkWarrantyStatus(expiredWarrantyId, customer))
                .thenReturn(new WarrantyCheckStatus(
                        false, true, expiredWarrantyId, deviceId, "AC", originalOrgId, "Original Store", "EXPIRED"));

        ServiceRequestResponse expiredReq = serviceRequestService.createRequest(
                customer,
                "Trần Thị B",
                new CreateServiceRequestInput(
                        "WARRANTY_CLAIM",
                        "AC",
                        null,
                        "QUOTE_REQUIRED",
                        true,
                        100_000L,
                        deviceId,
                        null,
                        expiredWarrantyId,
                        null,
                        null,
                        "45 Nguyễn Huệ, TP. Hồ Chí Minh",
                        "Hết hạn bảo hành",
                        null,
                        null));
        assertThat(expiredReq.status()).isEqualTo("AWAITING_PAID_REPAIR_CONSENT");
        assertThat(expiredReq.assignedProviderId()).isNull();

        ServiceRequestResponse consentedPaid = serviceRequestService.customerSelectFallbackProvider(
                customer,
                expiredReq.id(),
                new SelectFallbackProviderInput(fallbackOrgId, null, "QUOTE_REQUIRED", true, 100_000L));
        assertThat(consentedPaid.requestType()).isEqualTo("PAID_REPAIR");
        assertThat(consentedPaid.status()).isEqualTo("AWAITING_PROVIDER");
    }

    @Test
    void rejectsPersonalSelfBookingForOwnOrganization() {
        UUID providerId = UUID.randomUUID();
        String memberUserId = "org-member-user";
        when(providerGrpcClient.isUserMemberOfProvider(providerId, memberUserId)).thenReturn(true);

        assertThatThrownBy(() -> serviceRequestService.createRequest(
                memberUserId,
                "Member User",
                new CreateServiceRequestInput(
                        "PAID_REPAIR",
                        "AC",
                        null,
                        "FIXED",
                        false,
                        0L,
                        null,
                        null,
                        null,
                        providerId,
                        null,
                        "12 Lê Lợi, TP. Hồ Chí Minh",
                        "Tự đặt dịch vụ cho tổ chức của mình",
                        null,
                        null)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("OPERATION_FORBIDDEN");
    }

    private static ActorContext orgActor(String userId, UUID orgId) {
        return new ActorContext(userId, AccessMode.ORGANIZATION, orgId,
                Set.of(), Set.of("ORG_ADMIN"), Set.of(), Set.of());
    }
}
