package com.homecare.commerce.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public final class CommerceDtos {

    private CommerceDtos() {
    }

    public record ProductResponse(
            UUID id,
            UUID merchantId,
            String merchantName,
            String sku,
            String name,
            String category,
            String brand,
            String productType,
            int warrantyMonths,
            int returnWindowDays,
            String description,
            String imageUrl,
            long unitPrice,
            int availableQty,
            int reservedQty,
            String status) {
    }

    public record OrderLineRequest(
            @NotNull UUID productId,
            @Min(1) int quantity) {
    }

    public record CreateOrderRequest(
            @NotEmpty @Valid List<OrderLineRequest> items,
            @NotBlank String shippingAddress) {
    }

    public record OrderLineResponse(
            UUID id,
            UUID productId,
            String productName,
            int quantity,
            long unitPrice,
            long lineTotal,
            String fulfillmentStatus) {
    }

    public record CreatePaymentAttemptRequest(
            String returnUrl) {
    }

    public record PaymentAttemptResponse(
            UUID id,
            UUID orderId,
            String txnRef,
            String provider,
            long amount,
            String status,
            String paymentUrl,
            Instant createdAt) {
    }

    public record OrderResponse(
            UUID id,
            String buyerProfileId,
            UUID merchantId,
            String shippingAddress,
            long totalAmount,
            String status,
            String paymentStatus,
            Instant quoteExpiresAt,
            Long version,
            Instant createdAt,
            Instant updatedAt,
            List<OrderLineResponse> lines,
            List<PaymentAttemptResponse> paymentAttempts) {
    }

    public record CreateOrderReturnRequest(
            @NotBlank String returnType,
            @Min(1) int quantity,
            @NotBlank String reason) {
    }

    public record ResolveOrderReturnRequest(
            @NotBlank String action,
            String resolutionNote) {
    }

    public record OrderReturnResponse(
            UUID id,
            UUID orderId,
            UUID orderLineId,
            UUID productId,
            String productName,
            String buyerProfileId,
            UUID organizationId,
            String returnType,
            int quantity,
            String reason,
            String status,
            String resolutionNote,
            Instant createdAt,
            Instant resolvedAt) {
    }

    public record VerifyOrderLineReviewEligibilityResponse(
            boolean eligible,
            UUID orderLineId,
            UUID orderId,
            UUID productId,
            String fulfillmentStatus) {
    }
}
