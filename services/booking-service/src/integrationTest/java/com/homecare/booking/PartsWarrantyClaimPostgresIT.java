package com.homecare.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.homecare.booking.client.HouseholdVerificationClient;
import com.homecare.booking.client.ProviderGrpcClientAdapter;
import com.homecare.booking.dto.BookingExecutionDtos.AcceptBookingRequest;
import com.homecare.booking.dto.DisputeDtos.OpenDisputeRequest;
import com.homecare.booking.dto.PartsDtos.CreatePartsRequest;
import com.homecare.booking.dto.PartsDtos.RecordPartsUsageRequest;
import com.homecare.booking.dto.PartsDtos.RefuseCustomerPartRequest;
import com.homecare.booking.dto.PartsDtos.ReviewPartsRequest;
import com.homecare.booking.dto.WarrantyClaimDtos.AdminVerifyWarrantyClaimRequest;
import com.homecare.booking.dto.WarrantyClaimDtos.RespondWarrantyClaimRequest;
import com.homecare.booking.dto.WarrantyClaimDtos.SubmitWarrantyClaimRequest;
import com.homecare.booking.entity.BookingEntity;
import com.homecare.booking.entity.BookingStatus;
import com.homecare.booking.entity.WarrantyClaimEntity;
import com.homecare.booking.repository.BookingRepository;
import com.homecare.booking.repository.WarrantyClaimRepository;
import com.homecare.booking.service.BookingAcceptanceService;
import com.homecare.booking.service.BookingLifecycleService;
import com.homecare.booking.service.BookingPartsService;
import com.homecare.booking.service.DisputeService;
import com.homecare.booking.service.WarrantyClaimService;
import com.homecare.booking.service.WarrantyClaimSlaWorker;
import com.homecare.booking.service.WarrantySnapshotService;
import com.homecare.booking.service.WorkEvidenceStoragePort;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.common.security.RbacConstants;

/**
 * Verifies 006-warranty-and-claims on real PostgreSQL 16 with spring.flyway.enabled=true + ddl-auto=validate.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "homecare.booking.acceptance-timer-poll-ms=3600000",
        "homecare.booking.warranty-claim-sla-poll-ms=3600000",
        "homecare.dispute.sla-poll-ms=3600000"
})
class PartsWarrantyClaimPostgresIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired BookingRepository bookingRepository;
    @Autowired BookingPartsService partsService;
    @Autowired WarrantySnapshotService snapshotService;
    @Autowired BookingAcceptanceService acceptanceService;
    @Autowired BookingLifecycleService lifecycleService;
    @Autowired DisputeService disputeService;
    @Autowired WarrantyClaimService claimService;
    @Autowired WarrantyClaimSlaWorker slaWorker;
    @Autowired WarrantyClaimRepository claimRepository;

    @MockitoBean HouseholdVerificationClient householdClient;
    @MockitoBean ProviderGrpcClientAdapter providerGrpcClient;
    @MockitoBean WorkEvidenceStoragePort storagePort;

    @Test
    void validatesPartsApprovalCostHidingDualSnapshotsDiscrepancyAndZeroVndReworkOnPostgres16() {
        UUID providerId = UUID.randomUUID();
        String customerId = "cust-" + UUID.randomUUID();
        String techId = "tech-" + UUID.randomUUID();
        String orgAdminId = "org-admin-" + UUID.randomUUID();
        String globalAdminId = "admin-" + UUID.randomUUID();

        ActorContext customer = new ActorContext(customerId, AccessMode.PERSONAL, null, Set.of("CUSTOMER"), Set.of(), Set.of(), Set.of());
        ActorContext tech = new ActorContext(techId, AccessMode.ORGANIZATION, providerId, Set.of(RbacConstants.ROLE_TECHNICIAN), Set.of(RbacConstants.PERM_BOOKING_UPDATE_STATUS), Set.of("ORG_TECHNICIAN"), Set.of(RbacConstants.PERM_BOOKING_READ));
        ActorContext orgAdmin = new ActorContext(orgAdminId, AccessMode.ORGANIZATION, providerId, Set.of(), Set.of(), Set.of("ORG_ADMIN"), Set.of(RbacConstants.PERM_BOOKING_READ, RbacConstants.PERM_BOOKING_DISPATCH));
        ActorContext admin = new ActorContext(globalAdminId, AccessMode.PERSONAL, null, Set.of(RbacConstants.ROLE_ADMIN), Set.of(), Set.of(), Set.of());

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        BookingEntity booking = bookingRepository.saveAndFlush(BookingEntity.builder()
                .id(UUID.randomUUID())
                .requesterProfileId(customerId)
                .serviceId(UUID.randomUUID())
                .serviceName("Sửa điều hòa")
                .providerId(providerId)
                .assignedTechnicianProfileId(techId)
                .purpose("REPAIR")
                .preferredStart(now.plus(1, ChronoUnit.HOURS))
                .preferredEnd(now.plus(3, ChronoUnit.HOURS))
                .address("123 Le Loi")
                .status(BookingStatus.IN_PROGRESS.name())
                .allocationStatus("ALLOCATED")
                .quotedPrice(500_000L)
                .createdAt(now)
                .updatedAt(now)
                .build());

        // 1. EXTERNAL_SUPPLIER parts request starts PENDING_PROVIDER_APPROVAL; tech cannot record usage yet
        var extReq = partsService.createPartsRequest(tech, booking.getId(), new CreatePartsRequest(
                "Relay Block", "RL-01", 1, "EXTERNAL_SUPPLIER", "External Supply Co",
                null, 200_000L, "Compatible", null));
        assertThat(extReq.status()).isEqualTo("PENDING_PROVIDER_APPROVAL");
        assertThatThrownBy(() -> partsService.recordPartsUsage(tech, booking.getId(), new RecordPartsUsageRequest(
                extReq.id(), "Relay Block", "RL-01", 1, "EXTERNAL_SUPPLIER", 200_000L,
                "AVAILABLE", "SUPPLIER", 90, "3 months", "https://evidence/1.jpg", null)))
                .isInstanceOf(DomainException.class);

        // 2. Org Admin approves with private costPrice; Customer list omits costPrice
        var approved = partsService.reviewPartsRequest(orgAdmin, booking.getId(), extReq.id(),
                "\"v" + extReq.version() + "\"", new ReviewPartsRequest(true, 120_000L, 200_000L, null));
        assertThat(approved.status()).isEqualTo("PROVIDER_APPROVED");
        assertThat(approved.costPrice()).isEqualTo(120_000L);
        var customerView = partsService.listPartsRequests(customer, booking.getId());
        assertThat(customerView).hasSize(1);
        assertThat(customerView.getFirst().costPrice()).isNull();

        // 3. Record usage with downgraded warranty status -> triggers materialDiscrepancy at ACCEPTED
        snapshotService.recordCommitmentSnapshot(
                booking.getId(), null, 1, "AC_REPAIR_INSTALL", 30,
                List.of(Map.of("partName", "Relay Block", "quantity", 1, "partsWarrantyStatus", "AVAILABLE")),
                "Committed 30d", customerId);
        var usage = partsService.recordPartsUsage(tech, booking.getId(), new RecordPartsUsageRequest(
                extReq.id(), "Relay Block", "RL-01", 1, "EXTERNAL_SUPPLIER", 200_000L,
                "NONE", null, null, null, null, null));
        assertThat(usage.partsWarrantyStatus()).isEqualTo("NONE");

        // 4. CUSTOMER_SUPPLIED refusal transitions IN_PROGRESS -> WAITING_FOR_PARTS
        var custPart = partsService.createPartsRequest(tech, booking.getId(), new CreatePartsRequest(
                "Customer Capacitor", null, 1, "CUSTOMER_SUPPLIED", null, null, 0L, null, null));
        var refused = partsService.refuseCustomerPart(tech, booking.getId(), custPart.id(),
                "\"v" + custPart.version() + "\"", new RefuseCustomerPartRequest("Wrong voltage rating", null));
        assertThat(refused.status()).isEqualTo("CUSTOMER_PART_REFUSED");
        assertThat(bookingRepository.findById(booking.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.WAITING_FOR_PARTS.name());

        // 5. Move booking to COMPLETED_PENDING_ACCEPTANCE and accept -> ActivationSnapshot created
        BookingEntity fresh = bookingRepository.findById(booking.getId()).orElseThrow();
        fresh.setStatus(BookingStatus.COMPLETED_PENDING_ACCEPTANCE.name());
        fresh.setCompletionNotifiedAt(now);
        fresh = bookingRepository.saveAndFlush(fresh);
        var accepted = acceptanceService.acceptBooking(customer, booking.getId(),
                "\"v" + fresh.getVersion() + "\"", new AcceptBookingRequest("OK"));
        assertThat(accepted.status()).isEqualTo(BookingStatus.ACCEPTED.name());

        var snapshots = claimService.getBookingWarrantySnapshots(customer, booking.getId());
        assertThat(snapshots.commitmentSnapshots()).hasSize(1);
        assertThat(snapshots.activationSnapshot()).isNotNull();
        assertThat(snapshots.activationSnapshot().workmanshipDurationDays()).isEqualTo(30);
        assertThat(snapshots.activationSnapshot().materialDiscrepancy()).isTrue();
        Instant originalEndsAt = snapshots.activationSnapshot().endsAt();

        // 6. Submit WORKMANSHIP claim -> Org Admin accepts -> atomic 0 VND WARRANTY_REWORK Booking created
        var claim = claimService.submitClaim(customer, new SubmitWarrantyClaimRequest(
                booking.getId(), null, null, null, "WORKMANSHIP", "PROVIDER",
                null, "Noise returned after 2 days", List.of("https://evidence/claim1.jpg")));
        assertThat(claim.claimStatus()).isEqualTo("SUBMITTED");
        assertThat(claim.providerResponseDeadlineAt()).isNotNull();

        // Non-admin cannot verify (403 D-14)
        assertThatThrownBy(() -> claimService.adminVerifyClaim(orgAdmin, claim.id(),
                "\"v" + claim.version() + "\"", new AdminVerifyWarrantyClaimRequest("VERIFIED", null, null)))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).errorCode())
                .isEqualTo(CommonErrorCode.RESOURCE_FORBIDDEN.name());

        // Global Admin can verify without auto-accepting claimStatus
        var verified = claimService.adminVerifyClaim(admin, claim.id(),
                "\"v" + claim.version() + "\"", new AdminVerifyWarrantyClaimRequest("VERIFIED", null, "Checked"));
        assertThat(verified.warrantyVerificationStatus()).isEqualTo("VERIFIED");
        assertThat(verified.claimStatus()).isEqualTo("SUBMITTED");

        // Org Admin accepts claim -> 0 VND rework booking created, original endsAt unchanged
        var responded = claimService.respondToClaim(orgAdmin, claim.id(),
                "\"v" + verified.version() + "\"", new RespondWarrantyClaimRequest(true, null, null, null));
        assertThat(responded.claimStatus()).isEqualTo("ACCEPTED");
        assertThat(responded.reworkBookingId()).isNotNull();

        BookingEntity rework = bookingRepository.findById(responded.reworkBookingId()).orElseThrow();
        assertThat(rework.getQuotedPrice()).isEqualTo(0L);
        assertThat(rework.isWarrantyRework()).isTrue();
        assertThat(rework.getReworkSourceClaimId()).isEqualTo(claim.id());
        assertThat(snapshotService.findActivation(booking.getId()).orElseThrow().getEndsAt()).isEqualTo(originalEndsAt);

        // F2: 0 VND rework booking is ineligible for Review and produces no ActivationSnapshot on acceptance
        rework.setStatus(BookingStatus.COMPLETED_PENDING_ACCEPTANCE.name());
        rework.setCompletionNotifiedAt(now);
        rework = bookingRepository.saveAndFlush(rework);
        acceptanceService.acceptBooking(customer, rework.getId(), "\"v" + rework.getVersion() + "\"", new AcceptBookingRequest("Rework OK"));
        assertThat(lifecycleService.verifyReviewEligibility(rework.getId(), customerId).eligible()).isFalse();
        assertThat(snapshotService.findActivation(rework.getId())).isEmpty();

        // F4: Unused approved PartsRequest can be CANCELLED cleanly
        BookingEntity activeForCancel = bookingRepository.saveAndFlush(BookingEntity.builder()
                .id(UUID.randomUUID())
                .requesterProfileId(customerId)
                .serviceId(UUID.randomUUID())
                .serviceName("Bảo trì máy lạnh")
                .providerId(providerId)
                .assignedTechnicianProfileId(techId)
                .purpose("MAINTENANCE")
                .preferredStart(now.plus(1, ChronoUnit.HOURS))
                .preferredEnd(now.plus(3, ChronoUnit.HOURS))
                .address("123 Le Loi")
                .status(BookingStatus.IN_PROGRESS.name())
                .allocationStatus("ALLOCATED")
                .quotedPrice(300_000L)
                .createdAt(now)
                .updatedAt(now)
                .build());
        var unusedReq = partsService.createPartsRequest(tech, activeForCancel.getId(), new CreatePartsRequest(
                "Filter Net", null, 1, "PROVIDER_INVENTORY", null, null, 50_000L, null, null));
        var cancelledReq = partsService.cancelPartsRequest(tech, activeForCancel.getId(), unusedReq.id(),
                "\"v" + unusedReq.version() + "\"", "Customer declined extra filter");
        assertThat(cancelledReq.status()).isEqualTo("CANCELLED");

        // 7. Verify 48h SLA escalation worker on an overdue WORKMANSHIP claim (F6)
        assertThat(slaWorker.processOverdueClaims(now.plus(49, ChronoUnit.HOURS))).isZero(); // already ACCEPTED
        WarrantyClaimEntity overdueClaim = claimRepository.saveAndFlush(WarrantyClaimEntity.builder()
                .id(UUID.randomUUID())
                .bookingId(activeForCancel.getId())
                .providerId(providerId)
                .requesterProfileId(customerId)
                .warrantyCategory("WORKMANSHIP")
                .warrantyIssuer("PROVIDER")
                .issueDescription("Overdue claim")
                .warrantyEvidenceRefs("[]")
                .authorizationEvidenceRefs("[]")
                .claimStatus("SUBMITTED")
                .warrantyVerificationStatus("UNVERIFIED")
                .authorizationVerificationStatus("UNVERIFIED")
                .validityStatus("ACTIVE")
                .reportedAt(now.minus(50, ChronoUnit.HOURS))
                .providerResponseDeadlineAt(now.minus(2, ChronoUnit.HOURS))
                .providerNoResponse(false)
                .createdAt(now.minus(50, ChronoUnit.HOURS))
                .updatedAt(now.minus(50, ChronoUnit.HOURS))
                .build());
        assertThat(slaWorker.processOverdueClaims(now)).isEqualTo(1);
        assertThat(claimRepository.findById(overdueClaim.getId()).orElseThrow().isProviderNoResponse()).isTrue();

        // F6: Post-ACCEPTED Dispute window extends to ActivationSnapshot endsAt when > 30 days
        BookingEntity extendedBooking = bookingRepository.saveAndFlush(BookingEntity.builder()
                .id(UUID.randomUUID())
                .requesterProfileId(customerId)
                .serviceId(UUID.randomUUID())
                .serviceName("Sửa bo mạch inverter")
                .providerId(providerId)
                .assignedTechnicianProfileId(techId)
                .purpose("REPAIR")
                .preferredStart(now.minus(36, ChronoUnit.DAYS))
                .preferredEnd(now.minus(35, ChronoUnit.DAYS))
                .address("123 Le Loi")
                .status(BookingStatus.COMPLETED_PENDING_ACCEPTANCE.name())
                .allocationStatus("ALLOCATED")
                .quotedPrice(800_000L)
                .createdAt(now.minus(36, ChronoUnit.DAYS))
                .updatedAt(now.minus(35, ChronoUnit.DAYS))
                .build());
        snapshotService.recordCommitmentSnapshot(
                extendedBooking.getId(), null, 1, "AC_REPAIR", 60, List.of(), "60d workmanship", customerId);
        acceptanceService.acceptBooking(customer, extendedBooking.getId(), "\"v0\"", new AcceptBookingRequest("OK"));
        BookingEntity acceptedExtended = bookingRepository.findById(extendedBooking.getId()).orElseThrow();
        acceptedExtended.setAcceptedAt(now.minus(35, ChronoUnit.DAYS));
        bookingRepository.saveAndFlush(acceptedExtended);
        var extDispute = disputeService.openDispute(customer, extendedBooking.getId(),
                new OpenDisputeRequest("QUALITY_ISSUE", "Issue at day 35 within 60d workmanship warranty", List.of()));
        assertThat(extDispute.openedStage()).isEqualTo("POST_ACCEPTANCE");
    }
}
