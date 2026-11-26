package com.homecare.booking.grpc;

import java.util.UUID;

import com.homecare.booking.dto.BookingDtos.VerifyBookingReviewEligibilityResponse;
import com.homecare.booking.repository.BookingRepository;
import com.homecare.booking.service.BookingLifecycleService;
import com.homecare.booking.v1.BookingVerificationServiceGrpc;
import com.homecare.booking.v1.HasDeviceBookingsRequest;
import com.homecare.booking.v1.HasDeviceBookingsResponse;
import com.homecare.booking.v1.VerifyBookingEligibilityRequest;
import com.homecare.booking.v1.VerifyBookingEligibilityResponse;

import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
@RequiredArgsConstructor
public class BookingVerificationGrpcService extends BookingVerificationServiceGrpc.BookingVerificationServiceImplBase {

    private final BookingLifecycleService domainService;
    private final BookingRepository bookingRepository;

    @Override
    public void verifyBookingEligibility(
            VerifyBookingEligibilityRequest request,
            StreamObserver<VerifyBookingEligibilityResponse> responseObserver) {
        try {
            UUID bookingId = UUID.fromString(request.getBookingId());
            VerifyBookingReviewEligibilityResponse res =
                    domainService.verifyReviewEligibility(bookingId, request.getRequesterProfileId());

            VerifyBookingEligibilityResponse response =
                    VerifyBookingEligibilityResponse.newBuilder()
                            .setEligible(res.eligible())
                            .setBookingId(res.bookingId() != null ? res.bookingId().toString() : request.getBookingId())
                            .setProviderId(res.providerId() != null ? res.providerId().toString() : "")
                            .setServiceId(res.serviceId() != null ? res.serviceId().toString() : "")
                            .setStatus(res.status() != null ? res.status() : "")
                            .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            VerifyBookingEligibilityResponse response =
                    VerifyBookingEligibilityResponse.newBuilder()
                            .setEligible(false)
                            .setBookingId(request.getBookingId())
                            .setStatus("NOT_ELIGIBLE")
                            .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void hasDeviceBookings(
            HasDeviceBookingsRequest request,
            StreamObserver<HasDeviceBookingsResponse> responseObserver) {
        try {
            UUID deviceId = UUID.fromString(request.getDeviceId());
            boolean hasLinked = bookingRepository.existsByDeviceId(deviceId);
            responseObserver.onNext(HasDeviceBookingsResponse.newBuilder()
                    .setDeviceId(deviceId.toString())
                    .setHasLinkedBookings(hasLinked)
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            // Fail closed so malformed/errored lookups never allow accidental hard-delete (D-28).
            responseObserver.onNext(HasDeviceBookingsResponse.newBuilder()
                    .setDeviceId(request.getDeviceId())
                    .setHasLinkedBookings(true)
                    .build());
            responseObserver.onCompleted();
        }
    }
}
