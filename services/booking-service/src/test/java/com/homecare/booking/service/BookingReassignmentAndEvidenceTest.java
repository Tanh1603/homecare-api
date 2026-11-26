package com.homecare.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.homecare.booking.client.HouseholdVerificationClient;
import com.homecare.booking.client.ProviderGrpcClientAdapter;
import com.homecare.booking.dto.BookingDtos.ApproveExtraCostRequest;
import com.homecare.booking.dto.BookingDtos.BookingResponse;
import com.homecare.booking.dto.BookingDtos.CreateBookingRequest;
import com.homecare.booking.dto.BookingDtos.CreateWorkUpdateRequest;
import com.homecare.booking.dto.BookingDtos.WorkEvidenceResponse;
import com.homecare.booking.repository.BookingOutboxEventRepository;
import com.homecare.common.error.DomainException;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.common.security.OrgRolePermissionMapper;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.web.EtagSupport;
import com.homecare.provider.v1.AllocateTechnicianResponse;
import com.homecare.provider.v1.VerifyOfferingResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class BookingReassignmentAndEvidenceTest {

    @Autowired
    private BookingLifecycleService service;

    @Autowired
    private BookingExecutionService executionService;

    @Autowired
    private BookingOutboxEventRepository outboxRepository;

    @MockitoBean
    private ProviderGrpcClientAdapter providerGrpcClient;

    @MockitoBean
    private HouseholdVerificationClient householdVerificationClient;

    @Test
    void reassignsAssignedBookingAndRejectsSameTechnician() {
        UUID serviceId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID tech1 = UUID.randomUUID();
        UUID tech2 = UUID.randomUUID();
        String tech1Profile = "tech-1-" + UUID.randomUUID();
        String tech2Profile = "tech-2-" + UUID.randomUUID();

        when(providerGrpcClient.verifyOffering(serviceId)).thenReturn(
                VerifyOfferingResponse.newBuilder()
                        .setServiceId(serviceId.toString())
                        .setProviderId(providerId.toString())
                        .setName("Sửa điều hòa")
                        .setCategory("HVAC")
                        .setBasePrice(300_000L)
                        .setEstimatedDurationMinutes(60)
                        .setActive(true)
                        .build());

        when(providerGrpcClient.allocateTechnician(any(), any(), any(), eq(serviceId), any(), any(), any(), any(), eq(false)))
                .thenReturn(AllocateTechnicianResponse.newBuilder()
                        .setAllocated(true)
                        .setTechnicianId(tech1.toString())
                        .setTechnicianProfileId(tech1Profile)
                        .setTechnicianName("Thợ Một")
                        .setProviderId(providerId.toString())
                        .build());

        Instant start = Instant.now().plus(2, ChronoUnit.HOURS);
        BookingResponse created = service.createBooking(
                "cust-1",
                "Khách A",
                new CreateBookingRequest(serviceId, null, "AC", start, start.plus(1, ChronoUnit.HOURS), "123 Lê Lợi", "Test"));
        assertThat(created.status()).isEqualTo("ASSIGNED");

        ActorContext dispatcher = new ActorContext(
                "dispatcher-1",
                AccessMode.ORGANIZATION,
                providerId,
                Set.of(RbacConstants.ROLE_ORGANIZATION),
                Set.of(),
                Set.of(RbacConstants.ORG_ROLE_ADMIN),
                OrgRolePermissionMapper.permissionsFor(RbacConstants.ORG_ROLE_ADMIN));

        // Same technician -> 409 CONFLICT
        assertThatThrownBy(() -> service.assignTechnician(
                dispatcher, "Dispatcher", created.id(), tech1, "test", EtagSupport.format(created.version())))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).status()).isEqualTo(HttpStatus.CONFLICT));

        when(providerGrpcClient.reassignTechnician(any(), eq(created.id()), eq(tech2), eq(providerId), any()))
                .thenReturn(AllocateTechnicianResponse.newBuilder()
                        .setAllocated(true)
                        .setTechnicianId(tech2.toString())
                        .setTechnicianProfileId(tech2Profile)
                        .setTechnicianName("Thợ Hai")
                        .setProviderId(providerId.toString())
                        .build());

        BookingResponse reassigned = service.assignTechnician(
                dispatcher, "Dispatcher", created.id(), tech2, "Thợ 1 bận đột xuất", EtagSupport.format(created.version()));
        assertThat(reassigned.assignedTechnicianId()).isEqualTo(tech2);
        assertThat(reassigned.assignedTechnicianName()).isEqualTo("Thợ Hai");
        assertThat(outboxRepository.findAll())
                .anySatisfy(ev -> assertThat(ev.getEventType()).isEqualTo(DomainEventContracts.BOOKING_REASSIGNED));
    }

    @Test
    void workEvidenceAndExtraCostApprovalGateCompletion() {
        UUID serviceId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID tech1 = UUID.randomUUID();
        String techProfile = "tech-ev-" + UUID.randomUUID();

        when(providerGrpcClient.verifyOffering(serviceId)).thenReturn(
                VerifyOfferingResponse.newBuilder()
                        .setServiceId(serviceId.toString())
                        .setProviderId(providerId.toString())
                        .setName("Bảo trì máy giặt")
                        .setCategory("APPLIANCE")
                        .setBasePrice(200_000L)
                        .setEstimatedDurationMinutes(60)
                        .setActive(true)
                        .build());

        when(providerGrpcClient.allocateTechnician(any(), any(), any(), eq(serviceId), any(), any(), any(), any(), eq(false)))
                .thenReturn(AllocateTechnicianResponse.newBuilder()
                        .setAllocated(true)
                        .setTechnicianId(tech1.toString())
                        .setTechnicianProfileId(techProfile)
                        .setTechnicianName("Thợ Minh")
                        .setProviderId(providerId.toString())
                        .build());

        Instant start = Instant.now().plus(3, ChronoUnit.HOURS);
        BookingResponse created = service.createBooking(
                "cust-ev-1",
                "Khách B",
                new CreateBookingRequest(serviceId, null, "WASHER", start, start.plus(1, ChronoUnit.HOURS), "456 Nguyễn Huệ", null));

        ActorContext techActor = new ActorContext(
                techProfile, AccessMode.PERSONAL, null, Set.of(RbacConstants.ROLE_TECHNICIAN), Set.of(), Set.of(), Set.of());
        ActorContext custActor = new ActorContext(
                "cust-ev-1", AccessMode.PERSONAL, null, Set.of(RbacConstants.ROLE_CUSTOMER), Set.of(), Set.of(), Set.of());

        WorkEvidenceResponse ev = executionService.uploadWorkEvidence(
                techActor, created.id(), "photo.png", "image/png", new byte[]{1, 2, 3, 4});
        assertThat(ev.id()).isNotNull();

        BookingResponse diagnosed = service.appendWorkUpdate(
                techActor,
                created.id(),
                EtagSupport.format(created.version()),
                new CreateWorkUpdateRequest("DIAGNOSED", "Thay van cấp nước", 150_000L, List.of(ev.id())));
        UUID updateId = diagnosed.workUpdates().getFirst().id();
        assertThat(diagnosed.workUpdates().getFirst().extraCostStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(diagnosed.workUpdates().getFirst().evidence()).hasSize(1);
        assertThat(diagnosed.workUpdates().getFirst().evidence().getFirst().signedUrl()).isNotBlank();

        // Cannot complete while extraCost is pending approval
        assertThatThrownBy(() -> executionService.submitCompletionForAcceptance(
                techActor, created.id(), EtagSupport.format(diagnosed.version()),
                new com.homecare.booking.dto.BookingExecutionDtos.SubmitCompletionRequest("Xong", List.of(ev.id()))))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).status()).isEqualTo(HttpStatus.CONFLICT));

        BookingResponse approved = service.approveExtraCost(
                custActor, created.id(), updateId, new ApproveExtraCostRequest(true, "Đồng ý"),
                EtagSupport.format(diagnosed.version()));
        assertThat(approved.quotedPrice()).isEqualTo(350_000L);
        assertThat(approved.workUpdates().getFirst().extraCostStatus()).isEqualTo("APPROVED");

        BookingResponse completed = executionService.submitCompletionForAcceptance(
                techActor, created.id(), EtagSupport.format(approved.version()),
                new com.homecare.booking.dto.BookingExecutionDtos.SubmitCompletionRequest("Hoàn tất", List.of(ev.id())));
        assertThat(completed.status()).isEqualTo("COMPLETED_PENDING_ACCEPTANCE");
    }
}
