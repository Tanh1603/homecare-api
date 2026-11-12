package com.homecare.commerce.grpc;

import java.util.UUID;

import com.homecare.commerce.dto.CommerceDtos.VerifyOrderLineReviewEligibilityResponse;
import com.homecare.commerce.service.CommerceDomainService;
import com.homecare.commerce.v1.CommerceVerificationServiceGrpc;
import com.homecare.commerce.v1.VerifyOrderLineEligibilityRequest;

import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
@RequiredArgsConstructor
public class CommerceVerificationGrpcService extends CommerceVerificationServiceGrpc.CommerceVerificationServiceImplBase {

    private final CommerceDomainService domainService;

    @Override
    public void verifyOrderLineEligibility(
            VerifyOrderLineEligibilityRequest request,
            StreamObserver<com.homecare.commerce.v1.VerifyOrderLineEligibilityResponse> responseObserver) {
        try {
            UUID lineId = UUID.fromString(request.getOrderLineId());
            VerifyOrderLineReviewEligibilityResponse res =
                    domainService.verifyOrderLineReviewEligibility(lineId, request.getRequesterProfileId());

            com.homecare.commerce.v1.VerifyOrderLineEligibilityResponse response =
                    com.homecare.commerce.v1.VerifyOrderLineEligibilityResponse.newBuilder()
                            .setEligible(res.eligible())
                            .setOrderLineId(res.orderLineId() != null ? res.orderLineId().toString() : request.getOrderLineId())
                            .setOrderId(res.orderId() != null ? res.orderId().toString() : "")
                            .setProductId(res.productId() != null ? res.productId().toString() : "")
                            .setFulfillmentStatus(res.fulfillmentStatus() != null ? res.fulfillmentStatus() : "")
                            .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            com.homecare.commerce.v1.VerifyOrderLineEligibilityResponse response =
                    com.homecare.commerce.v1.VerifyOrderLineEligibilityResponse.newBuilder()
                            .setEligible(false)
                            .setOrderLineId(request.getOrderLineId())
                            .setFulfillmentStatus("NOT_ELIGIBLE")
                            .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}
