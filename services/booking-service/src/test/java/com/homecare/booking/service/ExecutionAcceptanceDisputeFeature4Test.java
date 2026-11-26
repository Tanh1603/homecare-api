package com.homecare.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.homecare.booking.client.ProviderGrpcClientAdapter;
import com.homecare.booking.dto.BookingDtos.CreateWorkUpdateRequest;
import com.homecare.booking.dto.BookingExecutionDtos.AcceptBookingRequest;
import com.homecare.booking.dto.BookingExecutionDtos.CustomerNoShowRequest;
import com.homecare.booking.dto.BookingExecutionDtos.RequestRevisionRequest;
import com.homecare.booking.dto.BookingExecutionDtos.ResumeFromPartsRequest;
import com.homecare.booking.dto.BookingExecutionDtos.StartReworkRequest;
import com.homecare.booking.dto.BookingExecutionDtos.SubmitCompletionRequest;
import com.homecare.booking.dto.BookingExecutionDtos.WaitingForPartsRequest;
import com.homecare.booking.dto.DisputeDtos.OpenDisputeRequest;
import com.homecare.booking.dto.DisputeDtos.ProviderDisputeResponseRequest;
import com.homecare.booking.dto.DisputeDtos.ResolveDisputeRequest;
import com.homecare.booking.entity.BookingEntity;
import com.homecare.booking.entity.BookingStatus;
import com.homecare.booking.entity.DisputeStatus;
import com.homecare.booking.repository.BookingRepository;
import com.homecare.booking.repository.DisputeRepository;
import com.homecare.common.error.DomainException;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.web.EtagSupport;

@SpringBootTest
@ActiveProfiles("test")
class ExecutionAcceptanceDisputeFeature4Test {

    @Autowired BookingLifecycleService lifecycleService;
    @Autowired BookingExecutionService executionService;
    @Autowired BookingAcceptanceService acceptanceService;
    @Autowired BookingAcceptanceTimerWorker acceptanceTimerWorker;
    @Autowired DisputeService disputeService;
    @Autowired DisputeSlaWorker disputeSlaWorker;
    @Autowired BookingRepository bookingRepository;
    @Autowired DisputeRepository disputeRepository;

    @MockitoBean HouseholdVerificationClient householdClient;
    @MockitoBean ProviderGrpcClientAdapter providerGrpcClient;
    @MockitoBean WorkEvidenceStoragePort storagePort;

    private ActorContext customerActor(String userId) {
        return new ActorContext(userId, AccessMode.PERSONAL, null, Set.of("CUSTOMER"), Set.of(), Set.of(), Set.of());
    }

    private ActorContext techActor(String userId) {
        return new ActorContext(userId, AccessMode.PERSONAL, null, Set.of(RbacConstants.ROLE_TECHNICIAN), Set.of(), Set.of(), Set.of());
    }

    private ActorContext orgActor(String userId, UUID orgId) {
        return new ActorContext(userId, AccessMode.ORGANIZATION, orgId, Set.of("CUSTOMER"),
                Set.of(), Set.of("ORG_OWNER"), Set.of(RbacConstants.PERM_BOOKING_DISPATCH, RbacConstants.PERM_BOOKING_READ));
    }

    private ActorContext adminActor(String userId) {
        return new ActorContext(userId, AccessMode.PERSONAL, null, Set.of(RbacConstants.ROLE_ADMIN), Set.of(), Set.of(), Set.of());
    }

    private BookingEntity seedBooking(String customer, UUID providerId, String techProfileId, String status) {
        Instant now = Instant.now();
        return bookingRepository.saveAndFlush(BookingEntity.builder()
                .id(UUID.randomUUID())
                .requesterProfileId(customer)
                .requesterName("Khách F4")
                .serviceId(UUID.randomUUID())
                .serviceName("Sửa máy lạnh")
                .providerId(providerId)
                .assignedTechnicianId(UUID.randomUUID())
                .assignedTechnicianProfileId(techProfileId)
                .assignedTechnicianName("Thợ A")
                .preferredStart(now.minus(1, ChronoUnit.HOURS))
                .preferredEnd(now.plus(1, ChronoUnit.HOURS))
                .address("100 Lê Lợi, Q1")
                .status(status)
                .allocationStatus("ALLOCATED")
                .quotedPrice(400_000L)
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    @Test
    void enforcesEvidenceGateNotificationProofTimersRevisionsAndDisputes() {
        when(storagePort.upload(any(), anyString(), anyString(), any()))
                .thenReturn(new WorkEvidenceStoragePort.StoredObject("evidence", "f4/proof.jpg"));
        when(storagePort.createSignedUrl(anyString(), anyString(), any(Integer.class)))
                .thenReturn("https://signed.example/f4/proof.jpg");

        String customer = "cust-f4-" + UUID.randomUUID();
        String tech = "tech-f4-" + UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        BookingEntity booking = seedBooking(customer, providerId, tech, BookingStatus.IN_PROGRESS.name());

        // FR-005 / SC-001: Completion without evidence is rejected
        assertThatThrownBy(() -> executionService.submitCompletionForAcceptance(
                techActor(tech), booking.getId(), EtagSupport.format(booking.getVersion()),
                new SubmitCompletionRequest("Đã thay tụ", List.of())))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("photo evidence");

        // FR-004 / SC-005: Lock-free evidence upload succeeds
        var uploaded = executionService.uploadWorkEvidence(
                techActor(tech), booking.getId(), "proof.jpg", "image/jpeg", new byte[]{1, 2, 3});
        assertThat(uploaded.id()).isNotNull();

        // FR-003: WAITING_FOR_PARTS pauses, releases slot, and resumes
        var waiting = executionService.markWaitingForParts(
                techActor(tech), booking.getId(), EtagSupport.format(booking.getVersion()),
                new WaitingForPartsRequest("Chờ bo mạch"));
        assertThat(waiting.status()).isEqualTo(BookingStatus.WAITING_FOR_PARTS.name());
        var resumed = executionService.resumeFromWaitingForParts(
                orgActor("mgr", providerId), booking.getId(), EtagSupport.format(waiting.version()),
                new ResumeFromPartsRequest(UUID.randomUUID(), tech, "Thợ A", "Đã có linh kiện"));
        assertThat(resumed.status()).isEqualTo(BookingStatus.IN_PROGRESS.name());

        // FR-005: Valid completion -> COMPLETED_PENDING_ACCEPTANCE (autoAcceptAt NULL until delivered!)
        var pending = executionService.submitCompletionForAcceptance(
                techActor(tech), booking.getId(), EtagSupport.format(resumed.version()),
                new SubmitCompletionRequest("Đã thay bo mạch & chạy thử ổn định", List.of(uploaded.id())));
        assertThat(pending.status()).isEqualTo(BookingStatus.COMPLETED_PENDING_ACCEPTANCE.name());
        assertThat(bookingRepository.findById(booking.getId()).orElseThrow().getAutoAcceptAt()).isNull();

        // FR-006: Notification delivery proof sets completionNotifiedAt + Asia/Ho_Chi_Minh Day 0 + 7d autoAcceptAt
        Instant notifiedAt = Instant.parse("2026-11-26T04:00:00Z"); // 11:00+07:00
        acceptanceService.recordCompletionNotificationDelivered(booking.getId(), notifiedAt);
        BookingEntity afterNotify = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(afterNotify.getAutoAcceptAt()).isEqualTo(Instant.parse("2026-12-03T16:59:59.999Z"));

        // FR-008: Revision 1 -> startRework -> complete again -> Customer accepts
        var rev1 = acceptanceService.requestRevision(
                customerActor(customer), booking.getId(), EtagSupport.format(afterNotify.getVersion()),
                new RequestRevisionRequest("Còn hơi ồn"));
        assertThat(rev1.status()).isEqualTo(BookingStatus.REVISION_REQUESTED.name());
        var rework = acceptanceService.startRework(
                techActor(tech), booking.getId(), EtagSupport.format(rev1.version()),
                new StartReworkRequest("Đang siết lại ốc"));
        assertThat(rework.status()).isEqualTo(BookingStatus.IN_PROGRESS.name());
        var pending2 = executionService.submitCompletionForAcceptance(
                techActor(tech), booking.getId(), EtagSupport.format(rework.version()),
                new SubmitCompletionRequest("Đã cố định lại dàn lạnh", List.of(uploaded.id())));
        var accepted = acceptanceService.acceptBooking(
                customerActor(customer), booking.getId(), EtagSupport.format(pending2.version()),
                new AcceptBookingRequest("Đã chạy êm"));
        assertThat(accepted.status()).isEqualTo(BookingStatus.ACCEPTED.name());
        assertThat(bookingRepository.findById(booking.getId()).orElseThrow().getAcceptanceSource()).isEqualTo("CUSTOMER");

        // FR-009 / D-11.4: Post-ACCEPTED dispute allowed within 30d without reverting ACCEPTED
        var dispute = disputeService.openDispute(
                customerActor(customer), booking.getId(),
                new OpenDisputeRequest("QUALITY_ISSUE", "Sau 2 ngày vẫn rỉ nước", List.of(uploaded.id())));
        assertThat(dispute.openedStage()).isEqualTo("POST_ACCEPTANCE");
        assertThat(bookingRepository.findById(booking.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.ACCEPTED.name());

        // Admin requests provider response (48h SLA), provider responds, admin resolves
        var awaitingProv = disputeService.adminRequestProviderResponse(
                adminActor("admin-1"), dispute.id(), EtagSupport.format(dispute.version()), null);
        assertThat(awaitingProv.status()).isEqualTo(DisputeStatus.AWAITING_PROVIDER_RESPONSE.name());
        var responded = disputeService.submitProviderResponse(
                orgActor("mgr", providerId), dispute.id(), EtagSupport.format(awaitingProv.version()),
                new ProviderDisputeResponseRequest("Sẽ kiểm tra ống thoát nước miễn phí", List.of()));
        assertThat(responded.status()).isEqualTo(DisputeStatus.UNDER_REVIEW.name());
        var resolved = disputeService.adminResolveDispute(
                adminActor("admin-1"), dispute.id(), EtagSupport.format(responded.version()),
                new ResolveDisputeRequest("RESOLVED", "PARTIAL_AGREEMENT", "Hai bên thống nhất xử lý"));
        assertThat(resolved.status()).isEqualTo(DisputeStatus.RESOLVED.name());
    }

    @Test
    void enforcesCustomerNoShow30MinGuardAutoAcceptTimerAndPreAcceptEscalation() {
        when(storagePort.upload(any(), anyString(), anyString(), any()))
                .thenReturn(new WorkEvidenceStoragePort.StoredObject("evidence", "f4/noshow.jpg"));

        String customer = "cust-ns-" + UUID.randomUUID();
        String tech = "tech-ns-" + UUID.randomUUID();
        UUID providerId = UUID.randomUUID();

        // 1. CUSTOMER_NO_SHOW 30-min guard
        BookingEntity nsBooking = seedBooking(customer, providerId, tech, BookingStatus.IN_PROGRESS.name());
        var photo = executionService.uploadWorkEvidence(
                techActor(tech), nsBooking.getId(), "door.jpg", "image/jpeg", new byte[]{9, 8, 7});
        nsBooking = bookingRepository.findById(nsBooking.getId()).orElseThrow();
        nsBooking.setArrivedAt(Instant.now().minus(10, ChronoUnit.MINUTES));
        nsBooking = bookingRepository.saveAndFlush(nsBooking);

        BookingEntity tooEarly = nsBooking;
        assertThatThrownBy(() -> executionService.reportCustomerNoShow(
                techActor(tech), tooEarly.getId(), EtagSupport.format(tooEarly.getVersion()),
                new CustomerNoShowRequest(photo.id(), "Gọi 3 lần không nghe")))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("30 minutes");

        nsBooking.setArrivedAt(Instant.now().minus(35, ChronoUnit.MINUTES));
        nsBooking = bookingRepository.saveAndFlush(nsBooking);
        var noShowRes = executionService.reportCustomerNoShow(
                techActor(tech), nsBooking.getId(), EtagSupport.format(nsBooking.getVersion()),
                new CustomerNoShowRequest(photo.id(), "Chờ 35 phút, gọi 4 lần không nghe"));
        assertThat(noShowRes.status()).isEqualTo(BookingStatus.CUSTOMER_NO_SHOW.name());
        assertThat(noShowRes.quotedPrice()).isEqualTo(0L);

        // 2. Day 7 SYSTEM_TIMEOUT auto-accept timer
        BookingEntity autoBooking = seedBooking(customer, providerId, tech, BookingStatus.IN_PROGRESS.name());
        var ev2 = executionService.uploadWorkEvidence(
                techActor(tech), autoBooking.getId(), "done.jpg", "image/jpeg", new byte[]{1, 1});
        autoBooking = bookingRepository.findById(autoBooking.getId()).orElseThrow();
        executionService.submitCompletionForAcceptance(
                techActor(tech), autoBooking.getId(), EtagSupport.format(autoBooking.getVersion()),
                new SubmitCompletionRequest("Hoàn tất vệ sinh", List.of(ev2.id())));
        Instant t0 = Instant.now().minus(8, ChronoUnit.DAYS);
        acceptanceService.recordCompletionNotificationDelivered(autoBooking.getId(), t0);

        var timerSummary = acceptanceTimerWorker.processTimers(Instant.now());
        assertThat(timerSummary.autoAccepted()).isGreaterThanOrEqualTo(1);
        BookingEntity afterAuto = bookingRepository.findById(autoBooking.getId()).orElseThrow();
        assertThat(afterAuto.getStatus()).isEqualTo(BookingStatus.ACCEPTED.name());
        assertThat(afterAuto.getAcceptanceSource()).isEqualTo("SYSTEM_TIMEOUT");

        // 3. Pre-acceptance Dispute ESCALATED -> Booking CLOSED_UNRESOLVED + 48h Provider SLA timeout
        BookingEntity dispBooking = seedBooking(customer, providerId, tech, BookingStatus.COMPLETED_PENDING_ACCEPTANCE.name());
        var d = disputeService.openDispute(
                customerActor(customer), dispBooking.getId(),
                new OpenDisputeRequest("DAMAGE", "Làm trầy vỏ máy", List.of()));
        assertThat(bookingRepository.findById(dispBooking.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.DISPUTED.name());

        var dAwait = disputeService.adminRequestProviderResponse(
                adminActor("admin-1"), d.id(), EtagSupport.format(d.version()), null);
        var slaSummary = disputeSlaWorker.processSla(dAwait.slaDueAt().plusSeconds(60));
        assertThat(slaSummary.providerNoResponses()).isGreaterThanOrEqualTo(1);
        var dNoResp = disputeRepository.findById(d.id()).orElseThrow();
        assertThat(dNoResp.isProviderNoResponse()).isTrue();
        assertThat(dNoResp.getStatus()).isEqualTo(DisputeStatus.UNDER_REVIEW.name());

        disputeService.adminResolveDispute(
                adminActor("admin-1"), d.id(), EtagSupport.format(dNoResp.getVersion()),
                new ResolveDisputeRequest("ESCALATED", "CLOSE_UNRESOLVED", "Chuyển xử lý ngoài nền tảng"));
        assertThat(bookingRepository.findById(dispBooking.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CLOSED_UNRESOLVED.name());
    }

    @Test
    void guardsLegacyCompletedBypassAdminListRbacAndReassignmentSlotPersistence() {
        String customer = "cust-g-" + UUID.randomUUID();
        String tech1 = "tech-g1-" + UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID newTechId = UUID.randomUUID();
        BookingEntity booking = seedBooking(customer, providerId, tech1, BookingStatus.IN_PROGRESS.name());

        // 1. Legacy appendWorkUpdate(kind="COMPLETED") is rejected with 400
        assertThatThrownBy(() -> lifecycleService.appendWorkUpdate(
                techActor(tech1), booking.getId(), EtagSupport.format(booking.getVersion()),
                new CreateWorkUpdateRequest("COMPLETED", "done", null, null, null, null)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("/complete");

        // 2. Non-admin calling adminListDisputes is rejected with 403
        assertThatThrownBy(() -> disputeService.adminListDisputes(customerActor(customer), null, 1, 20))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Admin");

        // 3. Reassign on REVISION_REQUESTED with UNALLOCATED calls assignTechnician and persists activeAllocationOperationId
        booking.setStatus(BookingStatus.REVISION_REQUESTED.name());
        booking.setAllocationStatus("UNALLOCATED");
        booking.setActiveAllocationOperationId(null);
        BookingEntity unallocated = bookingRepository.saveAndFlush(booking);

        when(providerGrpcClient.assignTechnician(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(com.homecare.provider.v1.AllocateTechnicianResponse.newBuilder()
                        .setAllocated(true)
                        .setTechnicianId(newTechId.toString())
                        .setTechnicianProfileId("tech-g2")
                        .setTechnicianName("Thợ B")
                        .build());

        var reassigned = executionService.reassignSameOrgTechnician(
                orgActor("mgr", providerId), unallocated.getId(), EtagSupport.format(unallocated.getVersion()),
                new ResumeFromPartsRequest(newTechId, "tech-g2", "Thợ B", "Chuyển thợ xử lý lại"));
        assertThat(reassigned.assignedTechnicianId()).isEqualTo(newTechId);
        BookingEntity persisted = bookingRepository.findById(unallocated.getId()).orElseThrow();
        assertThat(persisted.getAllocationStatus()).isEqualTo("ALLOCATED");
        assertThat(persisted.getActiveAllocationOperationId()).isNotNull();
    }
}
