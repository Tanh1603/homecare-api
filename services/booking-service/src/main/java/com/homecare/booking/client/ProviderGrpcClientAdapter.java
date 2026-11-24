package com.homecare.booking.client;

import com.homecare.common.config.HomecareSecurityProperties;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.security.OrganizationMembershipVerifier;
import com.homecare.common.security.VerifiedMembership;
import com.homecare.grpc.interceptor.WorkloadAuthClientInterceptor;
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
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ProviderGrpcClientAdapter implements OrganizationMembershipVerifier {

    private static final Logger log = LoggerFactory.getLogger(ProviderGrpcClientAdapter.class);
    private static final Metadata.Key<String> WORKLOAD_TOKEN_KEY =
            WorkloadAuthClientInterceptor.WORKLOAD_TOKEN_KEY;

    @GrpcClient("providerService")
    private ProviderAllocationServiceGrpc.ProviderAllocationServiceBlockingStub providerStub;

    private final String workloadToken;

    public ProviderGrpcClientAdapter(HomecareSecurityProperties securityProperties) {
        this.workloadToken = securityProperties.getInternal().getWorkloadToken();
    }

    public VerifyOfferingResponse verifyOffering(UUID serviceId) {
        return withInternalMetadata(providerStub)
                .withDeadlineAfter(3, TimeUnit.SECONDS)
                .verifyOffering(VerifyOfferingRequest.newBuilder()
                        .setServiceId(serviceId.toString())
                        .build());
    }

    public AllocateTechnicianResponse allocateTechnician(
            String intentId,
            UUID bookingId,
            UUID serviceId,
            String deviceType,
            Instant preferredStart,
            Instant preferredEnd) {
        return allocateTechnician(
                intentId, bookingId, serviceId, deviceType, preferredStart, preferredEnd, null, false);
    }

    public AllocateTechnicianResponse allocateTechnician(
            String intentId,
            UUID bookingId,
            UUID serviceId,
            String deviceType,
            Instant preferredStart,
            Instant preferredEnd,
            UUID providerId,
            boolean strictProviderMatch) {
        return allocateTechnician(null, intentId, bookingId, serviceId, deviceType,
                preferredStart, preferredEnd, providerId, strictProviderMatch);
    }

    public AllocateTechnicianResponse allocateTechnician(
            UUID operationId,
            String intentId,
            UUID bookingId,
            UUID serviceId,
            String deviceType,
            Instant preferredStart,
            Instant preferredEnd,
            UUID providerId,
            boolean strictProviderMatch) {
        AllocateTechnicianRequest.Builder builder = AllocateTechnicianRequest.newBuilder()
                .setIntentId(intentId)
                .setBookingId(bookingId.toString())
                .setServiceId(serviceId.toString())
                .setDeviceType(deviceType == null ? "" : deviceType)
                .setPreferredStartIso(preferredStart.toString())
                .setPreferredEndIso(preferredEnd.toString())
                .setStrictProviderMatch(strictProviderMatch);
        if (operationId != null) {
            builder.setOperationId(operationId.toString());
        }
        if (providerId != null) {
            builder.setProviderId(providerId.toString());
        }
        return withInternalMetadata(providerStub)
                .withDeadlineAfter(3, TimeUnit.SECONDS)
                .allocateTechnician(builder.build());
    }

    @Override
    public VerifiedMembership verify(UUID organizationId, String userId) {
        if (organizationId == null || userId == null || userId.isBlank()) {
            return VerifiedMembership.of(organizationId, userId, null, false);
        }
        try {
            VerifyOrganizationMembershipResponse response = withInternalMetadata(providerStub)
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .verifyOrganizationMembership(VerifyOrganizationMembershipRequest.newBuilder()
                            .setOrganizationId(organizationId.toString())
                            .setUserId(userId)
                            .build());
            return VerifiedMembership.of(organizationId, userId, response.getRole(), response.getValid());
        } catch (StatusRuntimeException e) {
            throw new DomainException(CommonErrorCode.MEMBERSHIP_UNAVAILABLE);
        } catch (Exception e) {
            log.warn("Provider org membership gRPC verification failed for org {} user {}: {}",
                    organizationId, userId, e.getMessage());
            return VerifiedMembership.of(organizationId, userId, null, false);
        }
    }

    public boolean verifyOrganizationMembership(UUID organizationId, String userId) {
        return verify(organizationId, userId).active();
    }

    public AllocateTechnicianResponse assignTechnician(UUID bookingId, UUID serviceId, String deviceType,
            Instant start, Instant end, UUID providerId, UUID technicianId) {
        return assignTechnician(UUID.randomUUID(), bookingId, serviceId, deviceType, start, end, providerId, technicianId);
    }

    public AllocateTechnicianResponse assignTechnician(UUID operationId, UUID bookingId, UUID serviceId, String deviceType,
            Instant start, Instant end, UUID providerId, UUID technicianId) {
        AllocateTechnicianRequest.Builder builder = AllocateTechnicianRequest.newBuilder()
                .setOperationId(operationId.toString())
                .setIntentId("op-" + operationId)
                .setBookingId(bookingId.toString()).setServiceId(serviceId.toString())
                .setDeviceType(deviceType == null ? "" : deviceType)
                .setPreferredStartIso(start.toString()).setPreferredEndIso(end.toString())
                .setStrictProviderMatch(providerId != null).setTechnicianId(technicianId.toString());
        if (providerId != null) {
            builder.setProviderId(providerId.toString());
        }
        return withInternalMetadata(providerStub).withDeadlineAfter(3, TimeUnit.SECONDS)
                .allocateTechnician(builder.build());
    }

    public ReleaseBookingResponse releaseBooking(UUID bookingId, UUID operationId, String reason) {
        ReleaseBookingRequest.Builder builder = ReleaseBookingRequest.newBuilder()
                .setBookingId(bookingId.toString())
                .setReason(reason == null ? "" : reason);
        if (operationId != null) {
            builder.setOperationId(operationId.toString());
        }
        return withInternalMetadata(providerStub)
                .withDeadlineAfter(3, TimeUnit.SECONDS)
                .releaseBooking(builder.build());
    }

    public AllocateTechnicianResponse reassignTechnician(
            UUID operationId,
            UUID bookingId,
            UUID newTechnicianId,
            UUID providerId,
            String reason) {
        ReassignTechnicianRequest.Builder builder = ReassignTechnicianRequest.newBuilder()
                .setBookingId(bookingId.toString())
                .setNewTechnicianId(newTechnicianId.toString())
                .setReason(reason == null ? "" : reason);
        if (operationId != null) {
            builder.setOperationId(operationId.toString());
        }
        if (providerId != null) {
            builder.setProviderId(providerId.toString());
        }
        return withInternalMetadata(providerStub)
                .withDeadlineAfter(3, TimeUnit.SECONDS)
                .reassignTechnician(builder.build());
    }

    public GetBookingAllocationResponse getBookingAllocation(UUID bookingId) {
        return withInternalMetadata(providerStub)
                .withDeadlineAfter(3, TimeUnit.SECONDS)
                .getBookingAllocation(GetBookingAllocationRequest.newBuilder()
                        .setBookingId(bookingId.toString())
                        .build());
    }

    public boolean isUserMemberOfProvider(UUID providerId, String userId) {
        if (providerId == null || userId == null || userId.isBlank()) {
            return false;
        }
        IsUserMemberOfProviderResponse resp = withInternalMetadata(providerStub)
                .withDeadlineAfter(3, TimeUnit.SECONDS)
                .isUserMemberOfProvider(IsUserMemberOfProviderRequest.newBuilder()
                        .setProviderId(providerId.toString())
                        .setUserId(userId)
                        .build());
        return resp.getMember();
    }

    private ProviderAllocationServiceGrpc.ProviderAllocationServiceBlockingStub withInternalMetadata(
            ProviderAllocationServiceGrpc.ProviderAllocationServiceBlockingStub stub) {
        Metadata metadata = new Metadata();
        metadata.put(WORKLOAD_TOKEN_KEY, workloadToken);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }
}
