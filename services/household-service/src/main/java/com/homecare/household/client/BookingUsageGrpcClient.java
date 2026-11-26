package com.homecare.household.client;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.homecare.booking.v1.BookingVerificationServiceGrpc;
import com.homecare.booking.v1.HasDeviceBookingsRequest;
import com.homecare.booking.v1.HasDeviceBookingsResponse;
import com.homecare.common.config.HomecareSecurityProperties;
import com.homecare.grpc.interceptor.WorkloadAuthClientInterceptor;

import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import net.devh.boot.grpc.client.inject.GrpcClient;

/**
 * Fail-closed gRPC client to check whether a Device has any linked Booking in booking-service
 * before allowing hard-delete (D-28). If booking-service is unavailable, returns true so the
 * device is safely soft-archived instead of hard-deleted.
 */
@Component
public class BookingUsageGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(BookingUsageGrpcClient.class);
    private static final Metadata.Key<String> WORKLOAD_TOKEN_KEY =
            WorkloadAuthClientInterceptor.WORKLOAD_TOKEN_KEY;

    @GrpcClient("bookingService")
    private BookingVerificationServiceGrpc.BookingVerificationServiceBlockingStub bookingStub;

    private final String workloadToken;

    public BookingUsageGrpcClient(HomecareSecurityProperties securityProperties) {
        this.workloadToken = securityProperties.getInternal().getWorkloadToken();
    }

    public boolean hasLinkedBookings(UUID deviceId) {
        if (deviceId == null) {
            return true;
        }
        if (bookingStub == null) {
            log.warn("Booking gRPC stub not initialized for device {}; failing closed -> archive", deviceId);
            return true;
        }
        try {
            Metadata metadata = new Metadata();
            metadata.put(WORKLOAD_TOKEN_KEY, workloadToken);
            HasDeviceBookingsResponse response = bookingStub
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .hasDeviceBookings(HasDeviceBookingsRequest.newBuilder()
                            .setDeviceId(deviceId.toString())
                            .build());
            return response.getHasLinkedBookings();
        } catch (Exception e) {
            log.warn("Booking usage gRPC lookup failed for device {} ({}); failing closed -> archive",
                    deviceId, e.getMessage());
            return true;
        }
    }
}
