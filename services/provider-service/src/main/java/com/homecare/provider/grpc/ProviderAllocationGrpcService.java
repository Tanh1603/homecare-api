package com.homecare.provider.grpc;

import com.homecare.provider.dto.ProviderDtos.ServiceOfferingResponse;
import com.homecare.provider.entity.ServiceCategory;
import com.homecare.provider.organization.service.OrganizationDomainService;
import com.homecare.provider.service.ProviderDomainService;
import com.homecare.provider.service.ProviderDomainService.AllocationOutcome;
import com.homecare.provider.v1.AllocateTechnicianRequest;
import com.homecare.provider.v1.AllocateTechnicianResponse;
import com.homecare.provider.v1.GetBookingAllocationRequest;
import com.homecare.provider.v1.GetBookingAllocationResponse;
import com.homecare.provider.v1.IsUserMemberOfProviderRequest;
import com.homecare.provider.v1.IsUserMemberOfProviderResponse;
import com.homecare.provider.v1.ProviderAllocationServiceGrpc;
import com.homecare.provider.v1.ReassignTechnicianRequest;
import com.homecare.provider.v1.ReleaseBookingRequest;
import com.homecare.provider.v1.ReleaseBookingResponse;
import com.homecare.provider.v1.VerifyOfferingRequest;
import com.homecare.provider.v1.VerifyOfferingResponse;
import com.homecare.provider.v1.VerifyOrganizationMembershipRequest;
import com.homecare.provider.v1.VerifyOrganizationMembershipResponse;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
@RequiredArgsConstructor
public class ProviderAllocationGrpcService extends ProviderAllocationServiceGrpc.ProviderAllocationServiceImplBase {

    private final ProviderDomainService domainService;
    private final OrganizationDomainService organizationService;

    @Override
    public void verifyOffering(
            VerifyOfferingRequest request,
            StreamObserver<VerifyOfferingResponse> responseObserver) {
        try {
            UUID serviceId = UUID.fromString(request.getServiceId());
            ServiceOfferingResponse offering = domainService.getOffering(serviceId);
            boolean providerActive = domainService.isProviderActive(offering.providerId());
            ServiceCategory cat = ServiceCategory.fromCode(offering.category()).orElse(null);
            boolean requiresQual = cat != null && cat.requiresQualification();
            boolean qualVerified = !requiresQual || domainService.isCategoryQualificationVerified(offering.providerId(), cat);
            boolean identityVerified = domainService.isProviderIdentityVerified(offering.providerId());
            VerifyOfferingResponse response = VerifyOfferingResponse.newBuilder()
                    .setServiceId(offering.id().toString())
                    .setProviderId(offering.providerId().toString())
                    .setName(offering.name())
                    .setBasePrice(offering.basePrice())
                    .setActive(offering.active() && providerActive && qualVerified)
                    .setCategory(offering.category())
                    .setEstimatedDurationMinutes(offering.estimatedDurationMinutes())
                    .setProviderName(offering.providerName())
                    .setRequiresQualification(requiresQual)
                    .setQualificationVerified(qualVerified)
                    .setInspectionFeePolicy(offering.inspectionFeePolicy().name())
                    .setWorkmanshipDurationDays(offering.workmanshipDurationDays())
                    .setProviderIdentityVerified(identityVerified)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            VerifyOfferingResponse response = VerifyOfferingResponse.newBuilder()
                    .setServiceId(request.getServiceId())
                    .setActive(false)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void allocateTechnician(
            AllocateTechnicianRequest request,
            StreamObserver<AllocateTechnicianResponse> responseObserver) {
        Instant start = parseInstantOrDefault(request.getPreferredStartIso(), Instant.now().plus(2, ChronoUnit.HOURS));
        Instant end = parseInstantOrDefault(request.getPreferredEndIso(), start.plus(90, ChronoUnit.MINUTES));
        UUID preferredProviderId = parseUuidOrNull(request.getProviderId());

        AllocationOutcome outcome = domainService.allocateTechnicianForBooking(
                parseUuidOrNull(request.getOperationId()),
                request.getIntentId(),
                UUID.fromString(request.getBookingId()),
                UUID.fromString(request.getServiceId()),
                request.getDeviceType(),
                start,
                end,
                preferredProviderId,
                request.getStrictProviderMatch(),
                request.getTechnicianId().isBlank() ? null : UUID.fromString(request.getTechnicianId()));

        AllocateTechnicianResponse.Builder builder = AllocateTechnicianResponse.newBuilder()
                .setIntentId(request.getIntentId())
                .setAllocated(outcome.allocated())
                .setFailureReason(outcome.failureReason() == null ? "" : outcome.failureReason());

        if (outcome.technicianId() != null) {
            builder.setTechnicianId(outcome.technicianId().toString());
        }
        if (outcome.technicianProfileId() != null) {
            builder.setTechnicianProfileId(outcome.technicianProfileId());
        }
        if (outcome.technicianName() != null) {
            builder.setTechnicianName(outcome.technicianName());
        }
        if (outcome.providerId() != null) {
            builder.setProviderId(outcome.providerId().toString());
        }
        if (outcome.allocationId() != null) {
            builder.setAllocationId(outcome.allocationId().toString());
        }

        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void reassignTechnician(
            ReassignTechnicianRequest request,
            StreamObserver<AllocateTechnicianResponse> responseObserver) {
        AllocationOutcome outcome = domainService.reassignTechnicianForBooking(
                parseUuidOrNull(request.getOperationId()),
                UUID.fromString(request.getBookingId()),
                UUID.fromString(request.getNewTechnicianId()),
                parseUuidOrNull(request.getProviderId()),
                request.getReason());

        AllocateTechnicianResponse.Builder builder = AllocateTechnicianResponse.newBuilder()
                .setIntentId("")
                .setAllocated(outcome.allocated())
                .setFailureReason(outcome.failureReason() == null ? "" : outcome.failureReason());
        if (outcome.technicianId() != null) builder.setTechnicianId(outcome.technicianId().toString());
        if (outcome.technicianProfileId() != null) builder.setTechnicianProfileId(outcome.technicianProfileId());
        if (outcome.technicianName() != null) builder.setTechnicianName(outcome.technicianName());
        if (outcome.providerId() != null) builder.setProviderId(outcome.providerId().toString());
        if (outcome.allocationId() != null) builder.setAllocationId(outcome.allocationId().toString());

        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void getBookingAllocation(
            GetBookingAllocationRequest request,
            StreamObserver<GetBookingAllocationResponse> responseObserver) {
        var snap = domainService.getBookingAllocation(UUID.fromString(request.getBookingId()));
        GetBookingAllocationResponse.Builder builder = GetBookingAllocationResponse.newBuilder()
                .setFound(snap.found())
                .setBookingId(snap.bookingId().toString())
                .setTechnicianProfileId(snap.technicianProfileId() == null ? "" : snap.technicianProfileId())
                .setTechnicianName(snap.technicianName() == null ? "" : snap.technicianName())
                .setStatus(snap.status() == null ? "" : snap.status());
        if (snap.technicianId() != null) builder.setTechnicianId(snap.technicianId().toString());
        if (snap.providerId() != null) builder.setProviderId(snap.providerId().toString());
        if (snap.allocationId() != null) builder.setAllocationId(snap.allocationId().toString());
        if (snap.operationId() != null) builder.setOperationId(snap.operationId().toString());
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void releaseBooking(
            ReleaseBookingRequest request,
            StreamObserver<ReleaseBookingResponse> responseObserver) {
        UUID bookingId = UUID.fromString(request.getBookingId());
        UUID operationId = parseUuidOrNull(request.getOperationId());
        var outcome = domainService.releaseBookingByOperation(bookingId, operationId, request.getReason());
        responseObserver.onNext(ReleaseBookingResponse.newBuilder()
                .setReleased(outcome.released())
                .setStatus(outcome.status())
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void verifyOrganizationMembership(
            VerifyOrganizationMembershipRequest request,
            StreamObserver<VerifyOrganizationMembershipResponse> responseObserver) {
        UUID orgId = parseUuidOrNull(request.getOrganizationId());
        var role = domainService.resolveMembershipRole(orgId, request.getUserId());
        VerifyOrganizationMembershipResponse response = VerifyOrganizationMembershipResponse.newBuilder()
                .setValid(role.isPresent())
                .setRole(role.orElse(""))
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void isUserMemberOfProvider(
            IsUserMemberOfProviderRequest request,
            StreamObserver<IsUserMemberOfProviderResponse> responseObserver) {
        UUID providerId = parseUuidOrNull(request.getProviderId());
        UUID orgId = providerId != null
                ? organizationService.findOrganizationIdIfUserIsMemberOfProvider(providerId, request.getUserId())
                : null;
        responseObserver.onNext(IsUserMemberOfProviderResponse.newBuilder()
                .setMember(orgId != null)
                .setOrganizationId(orgId != null ? orgId.toString() : "")
                .build());
        responseObserver.onCompleted();
    }

    private Instant parseInstantOrDefault(String iso, Instant fallback) {
        if (iso == null || iso.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(iso);
        } catch (Exception e) {
            return fallback;
        }
    }

    private UUID parseUuidOrNull(String val) {
        if (val == null || val.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(val.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
