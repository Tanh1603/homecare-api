package com.homecare.booking.client;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.homecare.grpc.interceptor.WorkloadAuthClientInterceptor;
import com.homecare.provider.v1.AllocateTechnicianRequest;
import com.homecare.provider.v1.AllocateTechnicianResponse;
import com.homecare.provider.v1.ProviderAllocationServiceGrpc;
import com.homecare.provider.v1.VerifyOfferingRequest;
import com.homecare.provider.v1.VerifyOfferingResponse;

import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import net.devh.boot.grpc.client.inject.GrpcClient;

@Component
public class ProviderGrpcClientAdapter {

    private static final Metadata.Key<String> WORKLOAD_TOKEN_KEY =
            WorkloadAuthClientInterceptor.WORKLOAD_TOKEN_KEY;

    @GrpcClient("providerService")
    private ProviderAllocationServiceGrpc.ProviderAllocationServiceBlockingStub providerStub;

    @Value("${homecare.internal.workload-token}")
    private String workloadToken;

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
        AllocateTechnicianRequest.Builder builder = AllocateTechnicianRequest.newBuilder()
                .setIntentId(intentId)
                .setBookingId(bookingId.toString())
                .setServiceId(serviceId.toString())
                .setDeviceType(deviceType == null ? "" : deviceType)
                .setPreferredStartIso(preferredStart.toString())
                .setPreferredEndIso(preferredEnd.toString())
                .setStrictProviderMatch(strictProviderMatch);
        if (providerId != null) {
            builder.setProviderId(providerId.toString());
        }
        return withInternalMetadata(providerStub)
                .withDeadlineAfter(3, TimeUnit.SECONDS)
                .allocateTechnician(builder.build());
    }

    private ProviderAllocationServiceGrpc.ProviderAllocationServiceBlockingStub withInternalMetadata(
            ProviderAllocationServiceGrpc.ProviderAllocationServiceBlockingStub stub) {
        Metadata metadata = new Metadata();
        metadata.put(WORKLOAD_TOKEN_KEY, workloadToken);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }
}
