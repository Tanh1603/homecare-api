package com.homecare.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.homecare.booking.client.HouseholdVerificationClient;
import com.homecare.booking.client.HouseholdVerificationClient.WarrantyVerificationResult;
import com.homecare.booking.client.ProviderGrpcClientAdapter;
import com.homecare.booking.dto.BookingDtos.BookingResponse;
import com.homecare.booking.dto.BookingDtos.CancelBookingRequest;
import com.homecare.booking.dto.BookingDtos.CreateBookingRequest;
import com.homecare.booking.dto.BookingDtos.CreateWorkUpdateRequest;
import com.homecare.booking.entity.BookingAllocationSagaEntity;
import com.homecare.booking.repository.AssignmentAttemptRepository;
import com.homecare.booking.repository.BookingAllocationSagaRepository;
import com.homecare.booking.repository.BookingOutboxEventRepository;
import com.homecare.booking.repository.BookingRepository;
import com.homecare.common.error.DomainException;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.web.EtagSupport;
import java.util.Set;
import com.homecare.provider.v1.AllocateTechnicianResponse;
import com.homecare.provider.v1.ReleaseBookingResponse;
import com.homecare.provider.v1.VerifyOfferingResponse;
import io.grpc.Status;

@SpringBootTest
@ActiveProfiles("test")
class BookingLifecycleServiceTest {

    @Autowired
    private BookingLifecycleService service;

    @Autowired
    private BookingAllocationSagaRepository sagaRepository;

    @Autowired
    private AssignmentAttemptRepository attemptRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private AssignmentCompensationRecoveryWorker recoveryWorker;

    @Autowired
    private BookingOutboxEventRepository outboxEventRepository;

    @MockitoBean
    private ProviderGrpcClientAdapter providerGrpcClient;

    @MockitoBean
    private HouseholdVerificationClient householdVerificationClient;

    @Test
    void executesImmediateAutoDispatchSagaPersistsOutboxAndEnforcesStateMachine() {
        UUID serviceId = UUID.fromString("33333333-3333-3333-3333-333333333301");
        UUID providerId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID techId = UUID.fromString("44444444-4444-4444-4444-444444444401");

        when(householdVerificationClient.verifyDeviceOrFallback(any(), anyString(), any()))
                .thenReturn("AC");
        when(providerGrpcClient.verifyOffering(serviceId))
                .thenReturn(VerifyOfferingResponse.newBuilder()
                        .setServiceId(serviceId.toString())
                        .setProviderId(providerId.toString())
                        .setName("Vệ sinh Máy lạnh")
                        .setBasePrice(250_000L)
                        .setActive(true)
                        .setCategory("AC")
                        .setEstimatedDurationMinutes(60)
                        .build());
        when(providerGrpcClient.allocateTechnician(any(), anyString(), any(), any(), anyString(), any(), any(), any(), anyBoolean()))
                .thenReturn(AllocateTechnicianResponse.newBuilder()
                        .setAllocated(true)
                        .setTechnicianId(techId.toString())
                        .setTechnicianProfileId("demo-technician")
                        .setTechnicianName("Trần Văn Kỹ Thuật")
                        .setProviderId(providerId.toString())
                        .build());

        BookingResponse created = service.createBooking(
                "customer-sub-1",
                new CreateBookingRequest(
                        serviceId,
                        UUID.randomUUID(),
                        "AC",
                        Instant.now().plus(2, ChronoUnit.HOURS),
                        null,
                        "123 Lê Lợi, Q1",
                        "Máy lạnh chảy nước"));

        assertThat(created.status()).isEqualTo("ASSIGNED");
        assertThat(created.allocationStatus()).isEqualTo("ALLOCATED");
        assertThat(created.assignedTechnicianId()).isEqualTo(techId);
        assertThat(created.bookingType()).isEqualTo("STANDARD");
        assertThat(created.quotedPrice()).isEqualTo(250_000L);

        BookingAllocationSagaEntity saga = sagaRepository.findByBookingId(created.id()).orElseThrow();
        assertThat(saga.getSagaStatus()).isEqualTo("ALLOCATED");
        assertThat(saga.getCurrentStep()).isEqualTo("COMPLETED");
        assertThat(outboxEventRepository.findAll()).isNotEmpty();

        ActorContext customerActor = new ActorContext(
                "customer-sub-1", AccessMode.PERSONAL, null,
                Set.of(RbacConstants.ROLE_CUSTOMER), Set.of(), Set.of(), Set.of());
        ActorContext otherCustomerActor = new ActorContext(
                "other-customer-sub", AccessMode.PERSONAL, null,
                Set.of(RbacConstants.ROLE_CUSTOMER), Set.of(), Set.of(), Set.of());
        ActorContext techActor = new ActorContext(
                "demo-technician", AccessMode.PERSONAL, null,
                Set.of(RbacConstants.ROLE_TECHNICIAN), Set.of(), Set.of(), Set.of());

        assertThatThrownBy(() -> service.appendWorkUpdate(
                otherCustomerActor,
                created.id(),
                EtagSupport.format(created.version()),
                new CreateWorkUpdateRequest("COMPLETED", "Attempted hijack")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("RESOURCE_FORBIDDEN");

        assertThat(service.listBookingsPage(
                new ActorContext("org-operator-sub", AccessMode.PERSONAL, null,
                        Set.of(RbacConstants.ROLE_ORGANIZATION), Set.of(), Set.of(), Set.of()),
                1, 20, null, null).items())
                .isEmpty();
        assertThat(service.listBookingsPage(customerActor, 1, 20, null, null).items())
                .extracting(BookingResponse::id)
                .contains(created.id());

        BookingResponse inProgress = service.appendWorkUpdate(
                techActor,
                created.id(),
                EtagSupport.format(created.version()),
                new CreateWorkUpdateRequest("STARTED", "Đã tới nhà khách hàng"));
        assertThat(inProgress.status()).isEqualTo("IN_PROGRESS");

        assertThatThrownBy(() -> service.cancelBooking(
                customerActor,
                created.id(),
                EtagSupport.format(inProgress.version()),
                new CancelBookingRequest("Đổi ý")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("BOOKING_INVALID_STATE");

        BookingResponse completed = service.appendWorkUpdate(
                techActor,
                created.id(),
                EtagSupport.format(inProgress.version()),
                new CreateWorkUpdateRequest("COMPLETED", "Đã vệ sinh và nạp gas đầy đủ"));
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(service.verifyReviewEligibility(created.id(), "customer-sub-1").eligible()).isTrue();
    }

    @Test
    void enforcesWarrantyClaimZeroPriceAndStrictOrganizationLockOrRejectsExpiredWarranty() {
        UUID serviceId = UUID.fromString("33333333-3333-3333-3333-333333333302");
        UUID orgId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID techId = UUID.fromString("44444444-4444-4444-4444-444444444401");
        UUID activeWarrantyId = UUID.randomUUID();
        UUID expiredWarrantyId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();

        when(householdVerificationClient.verifyWarranty(eq(activeWarrantyId), eq("customer-sub-w")))
                .thenReturn(new WarrantyVerificationResult(
                        activeWarrantyId, deviceId, "AC", orgId, "Điện Lạnh Bách Khoa Sài Gòn"));
        when(householdVerificationClient.verifyWarranty(eq(expiredWarrantyId), eq("customer-sub-w")))
                .thenThrow(new DomainException(
                        HttpStatus.CONFLICT,
                        "WARRANTY_EXPIRED",
                        "Warranty has expired; please book a paid repair service instead."));

        when(providerGrpcClient.verifyOffering(serviceId))
                .thenReturn(VerifyOfferingResponse.newBuilder()
                        .setServiceId(serviceId.toString())
                        .setProviderId(orgId.toString())
                        .setName("Sửa chữa Máy lạnh Inverter")
                        .setBasePrice(450_000L)
                        .setActive(true)
                        .setCategory("AC")
                        .setEstimatedDurationMinutes(90)
                        .build());
        when(providerGrpcClient.allocateTechnician(
                any(), anyString(), any(), eq(serviceId), eq("AC"), any(), any(), eq(orgId), eq(true)))
                .thenReturn(AllocateTechnicianResponse.newBuilder()
                        .setAllocated(true)
                        .setTechnicianId(techId.toString())
                        .setTechnicianProfileId("demo-technician")
                        .setTechnicianName("Trần Văn Kỹ Thuật")
                        .setProviderId(orgId.toString())
                        .build());

        BookingResponse warrantyBooking = service.createBooking(
                "customer-sub-w",
                new CreateBookingRequest(
                        serviceId,
                        deviceId,
                        "AC",
                        Instant.now().plus(3, ChronoUnit.HOURS),
                        null,
                        "12 Nguyễn Huệ, Q1",
                        "Bảo hành bo mạch máy lạnh",
                        "WARRANTY_CLAIM",
                        activeWarrantyId,
                        orgId));

        assertThat(warrantyBooking.bookingType()).isEqualTo("WARRANTY_CLAIM");
        assertThat(warrantyBooking.warrantyId()).isEqualTo(activeWarrantyId);
        assertThat(warrantyBooking.quotedPrice()).isZero();
        assertThat(warrantyBooking.providerId()).isEqualTo(orgId);
        assertThat(warrantyBooking.status()).isEqualTo("ASSIGNED");

        assertThatThrownBy(() -> service.createBooking(
                "customer-sub-w",
                new CreateBookingRequest(
                        serviceId,
                        deviceId,
                        "AC",
                        Instant.now().plus(3, ChronoUnit.HOURS),
                        null,
                        "12 Nguyễn Huệ, Q1",
                        "Bảo hành thẻ đã hết hạn",
                        "WARRANTY_CLAIM",
                        expiredWarrantyId,
                        orgId)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("WARRANTY_EXPIRED");
    }

    @Test
    void degradesGracefullyToRequestedWhenNoTechnicianAvailable() {
        UUID serviceId = UUID.fromString("33333333-3333-3333-3333-333333333302");
        when(householdVerificationClient.verifyDeviceOrFallback(any(), anyString(), any()))
                .thenReturn("AC");
        when(providerGrpcClient.verifyOffering(serviceId))
                .thenReturn(VerifyOfferingResponse.newBuilder()
                        .setServiceId(serviceId.toString())
                        .setName("Sửa Máy lạnh")
                        .setBasePrice(450_000L)
                        .setActive(true)
                        .setCategory("AC")
                        .setEstimatedDurationMinutes(90)
                        .build());
        when(providerGrpcClient.allocateTechnician(any(), anyString(), any(), any(), anyString(), any(), any(), any(), anyBoolean()))
                .thenReturn(AllocateTechnicianResponse.newBuilder()
                        .setAllocated(false)
                        .setFailureReason("NO_AVAILABLE_TECHNICIAN")
                        .build());

        BookingResponse created = service.createBooking(
                "customer-sub-2",
                new CreateBookingRequest(
                        serviceId,
                        null,
                        "AC",
                        Instant.now().plus(4, ChronoUnit.HOURS),
                        null,
                        "45 Nguyễn Huệ, Q1",
                        null));

        assertThat(created.status()).isEqualTo("REQUESTED");
        assertThat(created.allocationStatus()).isEqualTo("UNALLOCATED");

        BookingAllocationSagaEntity saga = sagaRepository.findByBookingId(created.id()).orElseThrow();
        assertThat(saga.getSagaStatus()).isEqualTo("FAILED");
        assertThat(saga.getFailureReason()).isEqualTo("NO_AVAILABLE_TECHNICIAN");
    }

    @Test
    void enforcesFailClosedOfferingInvalidScheduleStateJumpsIfMatchAndOrgMembership() {
        UUID serviceId = UUID.fromString("33333333-3333-3333-3333-333333333301");
        UUID providerId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID techId = UUID.fromString("44444444-4444-4444-4444-444444444401");
        Instant start = Instant.now().plus(2, ChronoUnit.HOURS);

        when(householdVerificationClient.verifyDeviceOrFallback(any(), anyString(), any()))
                .thenReturn("AC");
        when(providerGrpcClient.verifyOffering(serviceId))
                .thenThrow(new RuntimeException("provider gRPC down"))
                .thenReturn(VerifyOfferingResponse.newBuilder()
                        .setServiceId(serviceId.toString())
                        .setProviderId(providerId.toString())
                        .setName("Ve sinh May lanh")
                        .setBasePrice(250_000L)
                        .setActive(true)
                        .setCategory("AC")
                        .setEstimatedDurationMinutes(60)
                        .build());

        // (a) VerifyOffering unreachable must fail closed instead of falling back to 250k price
        assertThatThrownBy(() -> service.createBooking(
                "customer-fail-closed",
                new CreateBookingRequest(
                        serviceId, null, "AC", start, start.plus(1, ChronoUnit.HOURS), "123 Le Loi", null)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("SERVICE_NOT_AVAILABLE");

        // (b) preferredEnd <= preferredStart must be rejected
        assertThatThrownBy(() -> service.createBooking(
                "customer-bad-window",
                new CreateBookingRequest(
                        serviceId, null, "AC", start, start, "123 Le Loi", null)))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("VALIDATION_FAILED");

        // Create a valid ASSIGNED booking to verify state machine, If-Match, and org membership checks
        when(providerGrpcClient.allocateTechnician(any(), anyString(), any(), any(), anyString(), any(), any(), any(), anyBoolean()))
                .thenReturn(AllocateTechnicianResponse.newBuilder()
                        .setAllocated(true)
                        .setTechnicianId(techId.toString())
                        .setTechnicianProfileId("demo-technician")
                        .setTechnicianName("Tran Van Ky Thuat")
                        .setProviderId(providerId.toString())
                        .build());

        BookingResponse assigned = service.createBooking(
                "customer-sec-1",
                new CreateBookingRequest(
                        serviceId, null, "AC", start, start.plus(1, ChronoUnit.HOURS), "123 Le Loi", null));

        ActorContext demoTech = new ActorContext(
                "demo-technician", AccessMode.PERSONAL, null,
                Set.of(RbacConstants.ROLE_TECHNICIAN), Set.of(), Set.of(), Set.of());

        // (c) Missing If-Match must be rejected; jumping ASSIGNED -> COMPLETED must be rejected
        assertThatThrownBy(() -> service.appendWorkUpdate(
                demoTech,
                assigned.id(),
                null,
                new CreateWorkUpdateRequest("STARTED", "Arrived")))
                .isInstanceOf(EtagSupport.PreconditionException.class)
                .extracting("errorCode")
                .isEqualTo("PRECONDITION_REQUIRED");

        assertThatThrownBy(() -> service.appendWorkUpdate(
                demoTech,
                assigned.id(),
                EtagSupport.format(assigned.version()),
                new CreateWorkUpdateRequest("COMPLETED", "Skipped STARTED")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("BOOKING_INVALID_STATE");

        // (d) Technician in same org without org-wide permission must NOT see another tech's booking (#7 -> 404)
        ActorContext otherTechInOrg = new ActorContext(
                "other-tech-in-org", AccessMode.ORGANIZATION, providerId,
                Set.of(RbacConstants.ROLE_TECHNICIAN), Set.of(RbacConstants.PERM_BOOKING_READ),
                Set.of("ORG_TECHNICIAN"), Set.of());
        assertThat(service.listBookingsPage(otherTechInOrg, 1, 20, null, null).items()).isEmpty();
        assertThatThrownBy(() -> service.getBooking(otherTechInOrg, assigned.id()))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("BOOKING_NOT_FOUND");

        ActorContext validOrgOperator = new ActorContext(
                "valid-org-operator", AccessMode.ORGANIZATION, providerId,
                Set.of(RbacConstants.ROLE_ORGANIZATION), Set.of(),
                Set.of("ORG_OWNER"), Set.of(RbacConstants.PERM_BOOKING_READ, RbacConstants.PERM_BOOKING_DISPATCH));
        assertThat(service.getBooking(validOrgOperator, assigned.id()).id())
                .isEqualTo(assigned.id());

        // REQUESTED -> IN_PROGRESS must be rejected
        when(providerGrpcClient.allocateTechnician(any(), anyString(), any(), any(), anyString(), any(), any(), any(), anyBoolean()))
                .thenReturn(AllocateTechnicianResponse.newBuilder()
                        .setAllocated(false)
                        .setFailureReason("NO_AVAILABLE_TECHNICIAN")
                        .build());
        BookingResponse requested = service.createBooking(
                "customer-sec-2",
                new CreateBookingRequest(
                        serviceId, null, "AC", start.plus(3, ChronoUnit.HOURS), null, "123 Le Loi", null));
        assertThatThrownBy(() -> service.appendWorkUpdate(
                validOrgOperator, requested.id(),
                EtagSupport.format(requested.version()),
                new CreateWorkUpdateRequest("STARTED", "Trying without assigned tech")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("BOOKING_INVALID_STATE");
    }

    @Test
    void handlesSagaTimeoutReconciliationCancelledCompensationAndManualIntervention() {
        UUID serviceId = UUID.fromString("33333333-3333-3333-3333-333333333301");
        UUID providerId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID techId = UUID.fromString("44444444-4444-4444-4444-444444444401");
        Instant start = Instant.now().plus(6, ChronoUnit.HOURS);

        when(householdVerificationClient.verifyDeviceOrFallback(any(), anyString(), anyString())).thenReturn("AC");
        when(providerGrpcClient.verifyOffering(serviceId)).thenReturn(VerifyOfferingResponse.newBuilder()
                .setServiceId(serviceId.toString()).setName("Sửa Máy lạnh").setBasePrice(450_000L)
                .setActive(true).setCategory("AC").setEstimatedDurationMinutes(90).build());

        // 1. createBooking gRPC timeout -> RECONCILIATION_PENDING + AssignmentAttemptEntity
        when(providerGrpcClient.allocateTechnician(any(), anyString(), any(), any(), anyString(), any(), any(), any(), anyBoolean()))
                .thenThrow(Status.DEADLINE_EXCEEDED.asRuntimeException());
        BookingResponse pending = service.createBooking("timeout-cust-1",
                new CreateBookingRequest(serviceId, null, "AC", start, null, "Addr", null));
        assertThat(pending.allocationStatus()).isEqualTo("RECONCILIATION_PENDING");
        var pendingAttempts = attemptRepository.findByBookingIdOrderByCreatedAtDesc(pending.id());
        assertThat(pendingAttempts).hasSize(1);
        assertThat(pendingAttempts.getFirst().getStatus()).isEqualTo("RECONCILIATION_PENDING");

        // Strict allowlist blocks manual assignTechnician while RECONCILIATION_PENDING
        ActorContext admin = new ActorContext("admin", AccessMode.PERSONAL, null,
                Set.of(RbacConstants.ROLE_ADMIN), Set.of(RbacConstants.PERM_ADMIN_ALL), Set.of(), Set.of());
        assertThatThrownBy(() -> service.assignTechnician(
                admin, pending.id(), techId, EtagSupport.format(pending.version())))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("BOOKING_INVALID_STATE");

        // Recovery worker reconciles RECONCILIATION_PENDING -> ASSIGNED + ALLOCATED
        var attempt = pendingAttempts.getFirst();
        attempt.setNextAttemptAt(Instant.now().minusSeconds(1));
        attemptRepository.saveAndFlush(attempt);
        doReturn(AllocateTechnicianResponse.newBuilder()
                        .setAllocated(true).setTechnicianId(techId.toString())
                        .setTechnicianProfileId("tech-1").setTechnicianName("Tech 1")
                        .setProviderId(providerId.toString()).build())
                .when(providerGrpcClient).allocateTechnician(
                        eq(attempt.getOperationId()), anyString(), eq(pending.id()), any(), anyString(), any(), any(), any(), anyBoolean());
        recoveryWorker.recoverPendingAttempts();
        var reconciledBooking = bookingRepository.findById(pending.id()).orElseThrow();
        assertThat(reconciledBooking.getStatus()).isEqualTo("ASSIGNED");
        assertThat(reconciledBooking.getAllocationStatus()).isEqualTo("ALLOCATED");

        // 2. assignTechnician timeout -> 503 + RECONCILIATION_PENDING; if booking cancelled before recovery -> COMPENSATED
        doReturn(AllocateTechnicianResponse.newBuilder().setAllocated(false).setFailureReason("BUSY").build())
                .when(providerGrpcClient).allocateTechnician(any(), anyString(), any(), any(), anyString(), any(), any(), any(), anyBoolean());
        BookingResponse req2 = service.createBooking("timeout-cust-2",
                new CreateBookingRequest(serviceId, null, "AC", start.plus(4, ChronoUnit.HOURS), null, "Addr 2", null));
        assertThat(req2.allocationStatus()).isEqualTo("UNALLOCATED");

        when(providerGrpcClient.assignTechnician(any(), eq(req2.id()), any(), anyString(), any(), any(), any(), eq(techId)))
                .thenThrow(Status.UNAVAILABLE.asRuntimeException());
        assertThatThrownBy(() -> service.assignTechnician(
                admin, req2.id(), techId, EtagSupport.format(req2.version())))
                .isInstanceOf(DomainException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        var attempt2 = attemptRepository.findByBookingIdOrderByCreatedAtDesc(req2.id()).getFirst();
        assertThat(attempt2.getStatus()).isEqualTo("RECONCILIATION_PENDING");
        // Cancel booking before recovery worker completes; recovery sees allocated=true + CANCELLED -> ReleaseBooking -> COMPENSATED
        var b2 = bookingRepository.findById(req2.id()).orElseThrow();
        b2.setStatus("CANCELLED");
        bookingRepository.saveAndFlush(b2);
        attempt2.setNextAttemptAt(Instant.now().minusSeconds(1));
        attempt2.setRetryCount(AssignmentCompensationRecoveryWorker.MAX_RETRIES - 2);
        attemptRepository.saveAndFlush(attempt2);

        doReturn(AllocateTechnicianResponse.newBuilder()
                        .setAllocated(true).setTechnicianId(techId.toString())
                        .setTechnicianProfileId("tech-1").setTechnicianName("Tech 1")
                        .setProviderId(providerId.toString()).build())
                .when(providerGrpcClient).assignTechnician(eq(attempt2.getOperationId()), eq(req2.id()), any(), anyString(), any(), any(), any(), eq(techId));
        doReturn(ReleaseBookingResponse.newBuilder().setReleased(true).setStatus("RELEASED").build())
                .when(providerGrpcClient).releaseBooking(eq(req2.id()), eq(attempt2.getOperationId()), anyString());
        recoveryWorker.recoverPendingAttempts();
        assertThat(attemptRepository.findById(attempt2.getOperationId()).orElseThrow().getStatus()).isEqualTo("COMPENSATED");

        // 3. Exhausted MAX_RETRIES -> MANUAL_INTERVENTION (never FAILED)
        var attempt3 = attemptRepository.saveAndFlush(com.homecare.booking.entity.AssignmentAttemptEntity.builder()
                .operationId(UUID.randomUUID())
                .bookingId(req2.id())
                .serviceId(serviceId)
                .deviceType("AC")
                .preferredStart(start)
                .preferredEnd(start.plus(90, ChronoUnit.MINUTES))
                .strictProviderMatch(false)
                .status("COMPENSATION_PENDING")
                .retryCount(AssignmentCompensationRecoveryWorker.MAX_RETRIES - 1)
                .nextAttemptAt(Instant.now().minusSeconds(1))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        doThrow(Status.UNAVAILABLE.asRuntimeException())
                .when(providerGrpcClient).releaseBooking(eq(req2.id()), eq(attempt3.getOperationId()), anyString());
        recoveryWorker.recoverPendingAttempts();
        assertThat(attemptRepository.findById(attempt3.getOperationId()).orElseThrow().getStatus())
                .isEqualTo("MANUAL_INTERVENTION");
    }

    @Test
    void outboxEventTransitionsToDeadAfterMaxRetries() {
        var rabbit = org.mockito.Mockito.mock(org.springframework.amqp.rabbit.core.RabbitTemplate.class);
        doThrow(new RuntimeException("broker down")).when(rabbit).convertAndSend(anyString(), anyString(), anyString());
        var worker = new BookingOutboxRelayWorker(outboxEventRepository, rabbit);
        var event = outboxEventRepository.saveAndFlush(com.homecare.booking.entity.BookingOutboxEventEntity.builder()
                .id(UUID.randomUUID())
                .aggregateType("Booking")
                .aggregateId(UUID.randomUUID())
                .eventType("booking.requested.v1")
                .routingKey("booking.requested.v1")
                .payloadJson("{}")
                .status("PENDING")
                .retryCount(BookingOutboxRelayWorker.MAX_RETRIES - 1)
                .nextAttemptAt(Instant.now().minusSeconds(1))
                .createdAt(Instant.now())
                .build());
        worker.relayPendingEvents();
        var updated = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo("DEAD");
        assertThat(updated.getRetryCount()).isEqualTo(BookingOutboxRelayWorker.MAX_RETRIES);
    }
}
