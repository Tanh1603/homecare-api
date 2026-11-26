package com.homecare.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.homecare.booking.client.ProviderGrpcClientAdapter;
import com.homecare.booking.dto.BookingDtos.CancelBookingRequest;
import com.homecare.booking.dto.BookingDtos.ConfirmRescheduleRequest;
import com.homecare.booking.dto.BookingDtos.CreateWorkUpdateRequest;
import com.homecare.booking.dto.BookingDtos.ReportNoShowRequest;
import com.homecare.booking.dto.BookingDtos.RescheduleBookingRequest;
import com.homecare.booking.entity.BookingEntity;
import com.homecare.booking.entity.ServiceRequestEntity;
import com.homecare.booking.repository.BookingRepository;
import com.homecare.booking.repository.QuotationRepository;
import com.homecare.booking.repository.ServiceRequestRepository;
import com.homecare.booking.service.QuotationService.AcceptQuotationInput;
import com.homecare.booking.service.QuotationService.CreateQuotationInput;
import com.homecare.booking.service.QuotationService.QuotationItemInput;
import com.homecare.booking.service.QuotationService.QuotationResponse;
import com.homecare.booking.service.QuotationService.RejectQuotationInput;
import com.homecare.booking.service.ServiceRequestService.AdjustScopeInput;
import com.homecare.booking.service.ServiceRequestService.CreateServiceRequestInput;
import com.homecare.booking.service.ServiceRequestService.SelectFallbackProviderInput;
import com.homecare.booking.service.ServiceRequestService.ServiceRequestResponse;
import com.homecare.common.error.DomainException;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.common.web.EtagSupport;

@SpringBootTest
@ActiveProfiles("test")
class ServiceBookingCoreFeature3Test {

    @Autowired ServiceRequestService serviceRequestService;
    @Autowired QuotationService quotationService;
    @Autowired BookingLifecycleService bookingLifecycleService;
    @Autowired ServiceRequestTtlWorker ttlWorker;
    @Autowired ServiceRequestRepository serviceRequestRepository;
    @Autowired QuotationRepository quotationRepository;
    @Autowired BookingRepository bookingRepository;

    @MockitoBean HouseholdVerificationClient householdClient;
    @MockitoBean ProviderGrpcClientAdapter providerGrpcClient;

    private ActorContext customerActor(String userId) {
        return new ActorContext(userId, AccessMode.PERSONAL, null, Set.of("CUSTOMER"), Set.of(), Set.of(), Set.of());
    }

    private ActorContext orgActor(String userId, UUID orgId) {
        return new ActorContext(userId, AccessMode.ORGANIZATION, orgId, Set.of("CUSTOMER"), Set.of("ORG_OWNER"), Set.of(), Set.of());
    }

    @Test
    void enforcesDirectedProviderSoftWarningLockedPolicyRejectFallbackCapAndTtl() {
        String customer = "f3-cust-" + UUID.randomUUID();
        UUID provider1 = UUID.randomUUID();
        UUID provider2 = UUID.randomUUID();
        UUID provider3 = UUID.randomUUID();
        UUID provider4 = UUID.randomUUID();

        // FR-001: Missing preferredProviderId rejected
        assertThatThrownBy(() -> serviceRequestService.createRequest(
                customer, "Khách A",
                new CreateServiceRequestInput("PAID_REPAIR", "AIR_CONDITIONER", null, "QUOTE_REQUIRED",
                        true, 100_000L, null, null, null, null, null, "123 Lê Lợi", "Không mát", null, null, "CREDIT_FULL")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode").isEqualTo("VALIDATION_FAILED");

        // FR-002: Fee > 500,000 sets warning flag without blocking; NON_CREDITABLE policy locked
        ServiceRequestResponse sr = serviceRequestService.createRequest(
                customer, "Khách A",
                new CreateServiceRequestInput("PAID_REPAIR", "AIR_CONDITIONER", null, "QUOTE_REQUIRED",
                        true, 650_000L, null, null, null, provider1, null, "123 Lê Lợi", "Không mát", null, null, "NON_CREDITABLE"));
        assertThat(sr.inspectionFeeWarningFlag()).isTrue();
        assertThat(sr.inspectionFeePolicy()).isEqualTo("NON_CREDITABLE");

        ActorContext p1Actor = orgActor("staff-1", provider1);
        serviceRequestService.providerAccept(p1Actor, sr.id(), 0L);

        // FR-003: Provider cannot override NON_CREDITABLE policy with applyInspectionFeeCredit=true
        QuotationResponse q1 = quotationService.issueQuotation(
                p1Actor, sr.id(),
                new CreateQuotationInput(
                        List.of(new QuotationItemInput("LABOR", "Sửa board", 1, 900_000L)),
                        Instant.now().plus(48, ChronoUnit.HOURS),
                        true,
                        null));
        assertThat(q1.inspectionFeeCredit()).isZero();
        assertThat(q1.totalDueAfterCredit()).isEqualTo(900_000L);

        // FR-005: Superseding active SENT quote without reason fails
        assertThatThrownBy(() -> quotationService.issueQuotation(
                p1Actor, sr.id(),
                new CreateQuotationInput(
                        List.of(new QuotationItemInput("LABOR", "Sửa board v2", 1, 850_000L)),
                        Instant.now().plus(48, ChronoUnit.HOURS),
                        true,
                        null)))
                .isInstanceOf(DomainException.class);

        // FR-007: Reject quotation preserves ServiceRequest in QUOTATION_REJECTED and blocks same provider
        QuotationResponse rejected = quotationService.rejectQuotation(
                customer, sr.id(), q1.id(), new RejectQuotationInput("Giá cao"));
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(serviceRequestRepository.findById(sr.id()).orElseThrow().getStatus()).isEqualTo("QUOTATION_REJECTED");

        assertThatThrownBy(() -> quotationService.issueQuotation(
                p1Actor, sr.id(),
                new CreateQuotationInput(
                        List.of(new QuotationItemInput("LABOR", "Báo lại", 1, 800_000L)),
                        Instant.now().plus(48, ChronoUnit.HOURS),
                        false,
                        null)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode").isEqualTo("RESOURCE_FORBIDDEN");

        // FR-008: Max 3 fallback / scope-adjust rounds
        ServiceRequestResponse fb1 = serviceRequestService.customerSelectFallbackProvider(
                customer, sr.id(), new SelectFallbackProviderInput(provider2, null, "QUOTE_REQUIRED", false, 0L, "NO_FEE"));
        assertThat(fb1.fallbackCount()).isEqualTo(1);
        serviceRequestService.providerReject(orgActor("staff-2", provider2), sr.id(), "Hết lịch");

        ServiceRequestResponse fb2 = serviceRequestService.customerSelectFallbackProvider(
                customer, sr.id(), new SelectFallbackProviderInput(provider3, null, "QUOTE_REQUIRED", false, 0L, "NO_FEE"));
        assertThat(fb2.fallbackCount()).isEqualTo(2);
        serviceRequestService.providerReject(orgActor("staff-3", provider3), sr.id(), "Hết lịch");

        ServiceRequestResponse fb3 = serviceRequestService.adjustScope(
                customer, sr.id(), new AdjustScopeInput("Đã cập nhật mô tả", null, null, provider1));
        assertThat(fb3.fallbackCount()).isEqualTo(3);

        // 4th fallback rejected with CONFLICT
        serviceRequestService.providerReject(p1Actor, sr.id(), "Bận");
        assertThatThrownBy(() -> serviceRequestService.customerSelectFallbackProvider(
                customer, sr.id(), new SelectFallbackProviderInput(provider4, null, "FIXED", false, 0L, "NO_FEE")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode").isEqualTo("CONFLICT");

        // Cancel remaining open request succeeds
        ServiceRequestResponse cancelled = serviceRequestService.cancelServiceRequest(customer, sr.id(), "Không cần nữa");
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
    }

    @Test
    void unifiedWorkUpdateExtraCostCreatesRevisedQuotationAndBlocksCompletionUntilApproved() {
        String customer = "f3-rev-" + UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        String techId = "tech-" + UUID.randomUUID();

        // Direct FIXED booking with serviceRequestId = NULL
        BookingEntity booking = bookingRepository.saveAndFlush(BookingEntity.builder()
                .id(UUID.randomUUID())
                .requesterProfileId(customer)
                .serviceId(UUID.randomUUID())
                .serviceName("Vệ sinh máy lạnh")
                .providerId(providerId)
                .preferredProviderId(providerId)
                .assignedTechnicianId(UUID.randomUUID())
                .assignedTechnicianProfileId(techId)
                .preferredStart(Instant.now().minus(1, ChronoUnit.HOURS))
                .preferredEnd(Instant.now().plus(1, ChronoUnit.HOURS))
                .address("10 Hai Bà Trưng")
                .status("IN_PROGRESS")
                .allocationStatus("ALLOCATED")
                .quotedPrice(250_000L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        ActorContext techActor = new ActorContext(techId, AccessMode.PERSONAL, null, Set.of("TECHNICIAN"), Set.of(), Set.of(), Set.of());
        // Submitting WorkUpdate with extraCost > 0 auto-creates REVISED Quotation
        var updated = bookingLifecycleService.appendWorkUpdate(
                techActor, booking.getId(), EtagSupport.format(booking.getVersion()),
                new CreateWorkUpdateRequest("DIAGNOSED", "Thay tụ đề", 180_000L, List.of()));

        var revisedList = quotationService.listRevisedQuotations(customerActor(customer), booking.getId());
        assertThat(revisedList).hasSize(1);
        assertThat(revisedList.getFirst().quoteKind()).isEqualTo("REVISED");
        assertThat(revisedList.getFirst().status()).isEqualTo("SENT");

        // Completion blocked while REVISED Quotation is SENT
        assertThatThrownBy(() -> bookingLifecycleService.appendWorkUpdate(
                techActor, booking.getId(), EtagSupport.format(updated.version()),
                new CreateWorkUpdateRequest("COMPLETED", "Xong")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode").isEqualTo("CONFLICT");

        // Customer accepts REVISED Quotation -> quotedPrice increases and completion succeeds
        quotationService.acceptRevisedQuotation(
                customer, booking.getId(), revisedList.getFirst().id(), new AcceptQuotationInput(1));
        BookingEntity refreshed = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(refreshed.getQuotedPrice()).isEqualTo(430_000L);

        var completed = bookingLifecycleService.appendWorkUpdate(
                techActor, booking.getId(), EtagSupport.format(refreshed.getVersion()),
                new CreateWorkUpdateRequest("COMPLETED", "Hoàn tất"));
        assertThat(completed.status()).isEqualTo("COMPLETED");
    }

    @Test
    void enforcesCancelGuardsMaxTwoReschedulesAndTechnicianNoShowReopen() {
        String customer = "f3-bk-" + UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        ServiceRequestResponse sr = serviceRequestService.createRequest(
                customer, "Khách B",
                new CreateServiceRequestInput("PAID_REPAIR", "PLUMBING", null, "FIXED",
                        false, 0L, null, null, null, providerId, null, "45 Lê Duẩn", "Rò nước", null, null, "NO_FEE"));
        ServiceRequestResponse bookedSr = serviceRequestService.providerAccept(orgActor("p-mgr", providerId), sr.id(), 200_000L);
        UUID bookingId = bookedSr.repairBookingId();

        // 2-party reschedule up to 2 times
        BookingEntity b = bookingRepository.findById(bookingId).orElseThrow();
        Instant s1 = Instant.now().plus(4, ChronoUnit.HOURS);
        Instant e1 = s1.plus(1, ChronoUnit.HOURS);
        var r1Req = bookingLifecycleService.requestReschedule(
                customerActor(customer), bookingId, EtagSupport.format(b.getVersion()),
                new RescheduleBookingRequest(s1, e1, "Bận đột xuất"));
        var r1Conf = bookingLifecycleService.confirmReschedule(
                orgActor("p-mgr", providerId), bookingId, EtagSupport.format(r1Req.version()),
                new ConfirmRescheduleRequest(true, null));

        Instant s2 = s1.plus(3, ChronoUnit.HOURS);
        Instant e2 = s2.plus(1, ChronoUnit.HOURS);
        var r2Req = bookingLifecycleService.requestReschedule(
                customerActor(customer), bookingId, EtagSupport.format(r1Conf.version()),
                new RescheduleBookingRequest(s2, e2, "Đổi lần 2"));
        var r2Conf = bookingLifecycleService.confirmReschedule(
                orgActor("p-mgr", providerId), bookingId, EtagSupport.format(r2Req.version()),
                new ConfirmRescheduleRequest(true, null));

        // 3rd reschedule rejected
        assertThatThrownBy(() -> bookingLifecycleService.requestReschedule(
                customerActor(customer), bookingId, EtagSupport.format(r2Conf.version()),
                new RescheduleBookingRequest(s2.plus(2, ChronoUnit.HOURS), e2.plus(2, ChronoUnit.HOURS), "Lần 3")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode").isEqualTo("CONFLICT");

        // Technician No-Show after preferredEnd reopens ServiceRequest -> AWAITING_ALTERNATIVE
        BookingEntity assigned = bookingRepository.findById(bookingId).orElseThrow();
        assigned.setStatus("ASSIGNED");
        assigned.setPreferredStart(Instant.now().minus(3, ChronoUnit.HOURS));
        assigned.setPreferredEnd(Instant.now().minus(1, ChronoUnit.HOURS));
        assigned = bookingRepository.saveAndFlush(assigned);

        var noShow = bookingLifecycleService.reportTechnicianNoShow(
                customerActor(customer), bookingId, EtagSupport.format(assigned.getVersion()),
                new ReportNoShowRequest("REOPEN_FALLBACK", "Thợ không đến"));
        assertThat(noShow.status()).isEqualTo("CANCELLED");
        assertThat(serviceRequestRepository.findById(sr.id()).orElseThrow().getStatus()).isEqualTo("AWAITING_ALTERNATIVE");

        // Customer unilateral cancel in IN_PROGRESS rejected
        BookingEntity inProgress = bookingRepository.saveAndFlush(BookingEntity.builder()
                .id(UUID.randomUUID())
                .requesterProfileId(customer)
                .serviceId(UUID.randomUUID())
                .serviceName("Sửa ống nước")
                .providerId(providerId)
                .preferredStart(Instant.now())
                .preferredEnd(Instant.now().plus(1, ChronoUnit.HOURS))
                .address("45 Lê Duẩn")
                .status("IN_PROGRESS")
                .allocationStatus("ALLOCATED")
                .quotedPrice(200_000L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        assertThatThrownBy(() -> bookingLifecycleService.cancelBooking(
                customerActor(customer), inProgress.getId(), EtagSupport.format(inProgress.getVersion()),
                new CancelBookingRequest("Đổi ý")))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void enforcesOrgOnlyAuthPersistsExpiredQuoteAndUnblocksTtlRevisedQuote() {
        String customer = "cust-conv-" + UUID.randomUUID();
        UUID providerId = UUID.randomUUID();

        var sr = serviceRequestService.createRequest(
                customer, "Khách Conv",
                new CreateServiceRequestInput("PAID_REPAIR", "AIR_CONDITIONER", null, "QUOTE_REQUIRED",
                        false, 0L, null, null, null, providerId, null, "99 Võ Văn Tần", "Kiểm tra",
                        null, null, "NO_FEE"));

        // PERSONAL actor MUST NOT bypass org RBAC on providerAccept / providerReject / issueQuotation
        assertThatThrownBy(() -> serviceRequestService.providerAccept(customerActor(customer), sr.id(), 0L))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Organization context");
        assertThatThrownBy(() -> serviceRequestService.providerReject(customerActor(customer), sr.id(), "Spoof"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Organization context");
        assertThatThrownBy(() -> quotationService.issueQuotation(
                customerActor(customer), sr.id(),
                new CreateQuotationInput(List.of(new QuotationItemInput("LABOR", "Fake", 1, 1000L)), null, false)))
                .isInstanceOf(DomainException.class);

        // Provider accepts properly and issues quote; force validUntil in the past
        serviceRequestService.providerAccept(orgActor("mgr", providerId), sr.id(), 0L);
        var quote = quotationService.issueQuotation(
                orgActor("mgr", providerId), sr.id(),
                new CreateQuotationInput(List.of(new QuotationItemInput("LABOR", "Sửa", 1, 300_000L)), null, false));
        var quoteEntity = quotationRepository.findById(quote.id()).orElseThrow();
        quoteEntity.setValidUntil(Instant.now().minus(1, ChronoUnit.HOURS));
        quotationRepository.saveAndFlush(quoteEntity);

        // Accepting expired quote throws 409 AND persists EXPIRED status + reconciles ServiceRequest -> AWAITING_ALTERNATIVE
        assertThatThrownBy(() -> quotationService.acceptQuotation(
                customer, sr.id(), quote.id(), new AcceptQuotationInput(1)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("expired");
        assertThat(quotationRepository.findById(quote.id()).orElseThrow().getStatus()).isEqualTo("EXPIRED");
        assertThat(serviceRequestRepository.findById(sr.id()).orElseThrow().getStatus()).isEqualTo("AWAITING_ALTERNATIVE");

        // Warranty providerReject must NOT overwrite an already BOOKED warranty request (T032)
        var bookedWarranty = serviceRequestRepository.saveAndFlush(ServiceRequestEntity.builder()
                .id(UUID.randomUUID())
                .requesterProfileId(customer)
                .requestType("WARRANTY_CLAIM")
                .serviceCategory("AIR_CONDITIONER")
                .priceType("FIXED")
                .assignedProviderId(providerId)
                .address("99 Võ Văn Tần")
                .preferredStart(Instant.now().plus(2, ChronoUnit.HOURS))
                .preferredEnd(Instant.now().plus(3, ChronoUnit.HOURS))
                .status("BOOKED")
                .statusChangedAt(Instant.now())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        assertThatThrownBy(() -> serviceRequestService.providerReject(
                orgActor("mgr", providerId), bookedWarranty.getId(), "Late reject"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("AWAITING_PROVIDER");
        assertThat(serviceRequestRepository.findById(bookedWarranty.getId()).orElseThrow().getStatus()).isEqualTo("BOOKED");
    }
}
