package com.homecare.provider.grpc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.homecare.provider.dto.ProviderDtos.ServiceOfferingResponse;
import com.homecare.provider.service.ProviderDomainService;
import com.homecare.provider.service.ProviderDomainService.AllocationOutcome;
import com.homecare.provider.v1.AllocateTechnicianRequest;
import com.homecare.provider.v1.AllocateTechnicianResponse;
import com.homecare.provider.v1.ProviderAllocationServiceGrpc;
import com.homecare.provider.v1.VerifyOfferingRequest;
import com.homecare.provider.v1.VerifyOfferingResponse;

import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
@RequiredArgsConstructor
public class ProviderAllocationGrpcService extends ProviderAllocationServiceGrpc.ProviderAllocationServiceImplBase {

    private final ProviderDomainService domainService;

    @Override
    public void verifyOffering(
            VerifyOfferingRequest request,
            StreamObserver<VerifyOfferingResponse> responseObserver) {
        try {
            UUID serviceId = UUID.fromString(request.getServiceId());
            ServiceOfferingResponse offering = domainService.getOffering(serviceId);
            VerifyOfferingResponse response = VerifyOfferingResponse.newBuilder()
                    .setServiceId(offering.id().toString())
                    .setProviderId(offering.providerId().toString())
                    .setName(offering.name())
                    .setBasePrice(offering.basePrice())
                    .setActive(offering.active())
                    .setCategory(offering.category())
                    .setEstimatedDurationMinutes(offering.estimatedDurationMinutes())
                    .setProviderName(offering.providerName())
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
                request.getIntentId(),
                UUID.fromString(request.getBookingId()),
                UUID.fromString(request.getServiceId()),
                request.getDeviceType(),
                start,
                end,
                preferredProviderId,
                request.getStrictProviderMatch());

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
