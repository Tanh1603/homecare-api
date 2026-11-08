package com.homecare.booking.grpc;

import java.util.UUID;

import com.homecare.booking.dto.BookingDtos.VerifyBookingReviewEligibilityResponse;
import com.homecare.booking.service.BookingLifecycleService;
import com.homecare.booking.v1.BookingVerificationServiceGrpc;
import com.homecare.booking.v1.VerifyBookingEligibilityRequest;

import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
@RequiredArgsConstructor
public class BookingVerificationGrpcService extends BookingVerificationServiceGrpc.BookingVerificationServiceImplBase {

    private final BookingLifecycleService domainService;

    @Override
    public void verifyBookingEligibility(
            VerifyBookingEligibilityRequest request,
            StreamObserver<com.homecare.booking.v1.VerifyBookingEligibilityResponse> responseObserver) {
        try {
            UUID bookingId = UUID.fromString(request.getBookingId());
            VerifyBookingReviewEligibilityResponse res =
                    domainService.verifyReviewEligibility(bookingId, request.getRequesterProfileId());

            com.homecare.booking.v1.VerifyBookingEligibilityResponse response =
                    com.homecare.booking.v1.VerifyBookingEligibilityResponse.newBuilder()
                            .setEligible(res.eligible())
                            .setBookingId(res.bookingId() != null ? res.bookingId().toString() : request.getBookingId())
                            .setProviderId(res.providerId() != null ? res.providerId().toString() : "")
                            .setServiceId(res.serviceId() != null ? res.serviceId().toString() : "")
                            .setStatus(res.status() != null ? res.status() : "")
                            .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            com.homecare.booking.v1.VerifyBookingEligibilityResponse response =
                    com.homecare.booking.v1.VerifyBookingEligibilityResponse.newBuilder()
                            .setEligible(false)
                            .setBookingId(request.getBookingId())
                            .setStatus("NOT_ELIGIBLE")
                            .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}
