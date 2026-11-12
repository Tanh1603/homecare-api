package com.homecare.review.client;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.homecare.booking.v1.BookingVerificationServiceGrpc;
import com.homecare.booking.v1.VerifyBookingEligibilityRequest;
import com.homecare.booking.v1.VerifyBookingEligibilityResponse;
import com.homecare.commerce.v1.CommerceVerificationServiceGrpc;
import com.homecare.commerce.v1.VerifyOrderLineEligibilityRequest;
import com.homecare.commerce.v1.VerifyOrderLineEligibilityResponse;
import com.homecare.grpc.interceptor.WorkloadAuthClientInterceptor;

import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import net.devh.boot.grpc.client.inject.GrpcClient;

@Component
public class EvidenceVerificationClient {

    private static final Logger log = LoggerFactory.getLogger(EvidenceVerificationClient.class);
    private static final Metadata.Key<String> WORKLOAD_TOKEN_KEY =
            WorkloadAuthClientInterceptor.WORKLOAD_TOKEN_KEY;

    @GrpcClient("bookingService")
    private BookingVerificationServiceGrpc.BookingVerificationServiceBlockingStub bookingStub;

    @GrpcClient("commerceService")
    private CommerceVerificationServiceGrpc.CommerceVerificationServiceBlockingStub commerceStub;

    @Value("${homecare.internal.workload-token}")
    private String workloadToken;

    public BookingEligibility verifyBookingEligible(UUID bookingId, String requesterProfileId) {
        try {
            VerifyBookingEligibilityResponse response = withInternalMetadata(bookingStub)
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .verifyBookingEligibility(VerifyBookingEligibilityRequest.newBuilder()
                            .setBookingId(bookingId.toString())
                            .setRequesterProfileId(requesterProfileId)
                            .build());

            UUID providerId = null;
            if (!response.getProviderId().isBlank()) {
                try {
                    providerId = UUID.fromString(response.getProviderId());
                } catch (Exception ignored) {
                }
            }
            return new BookingEligibility(response.getEligible(), providerId);
        } catch (Exception e) {
            log.warn("Booking gRPC verification failed ({}), defaulting to ineligible", e.getMessage());
            return new BookingEligibility(false, null);
        }
    }

    public boolean verifyOrderLineEligible(UUID orderLineId, String requesterProfileId) {
        try {
            VerifyOrderLineEligibilityResponse response = withInternalMetadata(commerceStub)
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .verifyOrderLineEligibility(VerifyOrderLineEligibilityRequest.newBuilder()
                            .setOrderLineId(orderLineId.toString())
                            .setRequesterProfileId(requesterProfileId)
                            .build());

            return response.getEligible();
        } catch (Exception e) {
            log.warn("Commerce gRPC verification failed ({}), defaulting to ineligible", e.getMessage());
            return false;
        }
    }

    private <T extends io.grpc.stub.AbstractStub<T>> T withInternalMetadata(T stub) {
        Metadata metadata = new Metadata();
        metadata.put(WORKLOAD_TOKEN_KEY, workloadToken);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    public record BookingEligibility(boolean eligible, UUID providerId) {
    }
}
