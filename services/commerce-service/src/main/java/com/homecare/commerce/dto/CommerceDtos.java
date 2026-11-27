package com.homecare.commerce.dto;

import com.homecare.commerce.entity.DeviceEligibility;
import com.homecare.commerce.entity.ProductType;
import com.homecare.commerce.entity.WarrantyStartTrigger;
import jakarta.validation.constraints.NotNull;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CommerceDtos {
    public record ProductInput(UUID merchantId,
            @Pattern(regexp = ".*\\S.*") @Size(max = 255) String sku,
            @Pattern(regexp = ".*\\S.*") @Size(max = 255) String name,
            @Pattern(regexp = ".*\\S.*") @Size(max = 255) String category,
            @Pattern(regexp = ".*\\S.*") @Size(max = 255) String brand,
            ProductType productType,
            @PositiveOrZero Integer warrantyMonths,
            @PositiveOrZero Integer returnWindowDays,
            @Size(max = 1500) String description,
            @Size(max = 255) String imageUrl,
            @Positive Long unitPrice,
            @Pattern(regexp = "ACTIVE|INACTIVE") String status,
            @Size(max = 255) String model,
            DeviceEligibility deviceEligibility,
            WarrantyStartTrigger warrantyStartTrigger,
            Boolean installable,
            @Size(max = 255) String defaultInstallCategory) {
        public ProductInput(UUID merchantId, String sku, String name, String category, String brand,
                ProductType productType, Integer warrantyMonths, Integer returnWindowDays,
                String description, String imageUrl, Long unitPrice, String status) {
            this(merchantId, sku, name, category, brand, productType, warrantyMonths, returnWindowDays,
                    description, imageUrl, unitPrice, status, null, null, null, false, null);
        }
    }
    public record InventoryInput(@NotNull @Min(0) Integer availableQty) {}

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
            String status,
            String model,
            String deviceEligibility,
            String warrantyStartTrigger,
            boolean installable,
            String defaultInstallCategory) {
        public ProductResponse(
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
            this(id, merchantId, merchantName, sku, name, category, brand, productType, warrantyMonths,
                    returnWindowDays, description, imageUrl, unitPrice, availableQty, reservedQty, status,
                    null, DeviceEligibility.NONE.name(), WarrantyStartTrigger.DELIVERY_DATE.name(), false, null);
        }
    }

    public record OrderLineRequest(
            @NotNull UUID productId,
            @Min(1) int quantity,
            UUID targetDeviceId,
            String partSource) {
        public OrderLineRequest(UUID productId, int quantity) {
            this(productId, quantity, null, null);
        }

        public OrderLineRequest(UUID productId, int quantity, UUID targetDeviceId) {
            this(productId, quantity, targetDeviceId, null);
        }
    }

    public record CreateOrderRequest(
            @NotEmpty List<@Valid OrderLineRequest> items,
            String shippingAddress,
            UUID shippingAddressId,
            UUID installOfferingId,
            UUID serviceAddressId,
            Instant preferredInstallStart) {
        public CreateOrderRequest(List<OrderLineRequest> items, String shippingAddress) {
            this(items, shippingAddress, null, null, null, null);
        }

        public CreateOrderRequest(List<OrderLineRequest> items, String shippingAddress, UUID shippingAddressId) {
            this(items, shippingAddress, shippingAddressId, null, null, null);
        }
    }

    public record ShippingAddressSnapshotResponse(
            UUID addressId,
            String recipientName,
            String phoneNumber,
            String addressLine,
            String wardCode,
            String wardName,
            String provinceCode,
            String provinceName,
            String formattedAddress,
            Double latitude,
            Double longitude) {
    }

    public record OrderLineResponse(
            UUID id,
            UUID productId,
            String productName,
            int quantity,
            long unitPrice,
            long lineTotal,
            String fulfillmentStatus,
            UUID targetDeviceId) {
        public OrderLineResponse(UUID id, UUID productId, String productName, int quantity,
                long unitPrice, long lineTotal, String fulfillmentStatus) {
            this(id, productId, productName, quantity, unitPrice, lineTotal, fulfillmentStatus, null);
        }
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
            String buyerName,
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
            List<PaymentAttemptResponse> paymentAttempts,
            ShippingAddressSnapshotResponse shippingAddressSnapshot) {
        public OrderResponse(UUID id, String buyerProfileId, String buyerName, UUID merchantId,
                String shippingAddress, long totalAmount, String status, String paymentStatus,
                Instant quoteExpiresAt, Long version, Instant createdAt, Instant updatedAt,
                List<OrderLineResponse> lines, List<PaymentAttemptResponse> paymentAttempts) {
            this(id, buyerProfileId, buyerName, merchantId, shippingAddress, totalAmount, status, paymentStatus,
                    quoteExpiresAt, version, createdAt, updatedAt, lines, paymentAttempts, null);
        }

        public OrderResponse(UUID id, String buyerProfileId, UUID merchantId, String shippingAddress,
                long totalAmount, String status, String paymentStatus, Instant quoteExpiresAt, Long version,
                Instant createdAt, Instant updatedAt, List<OrderLineResponse> lines,
                List<PaymentAttemptResponse> paymentAttempts) {
            this(id, buyerProfileId, null, merchantId, shippingAddress, totalAmount, status, paymentStatus,
                    quoteExpiresAt, version, createdAt, updatedAt, lines, paymentAttempts, null);
        }
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

    public record ConfirmManualRefundRequest(
            @NotBlank String reference,
            String note) {
    }

    public record OrderReturnResponse(
            UUID id,
            UUID orderId,
            UUID orderLineId,
            UUID productId,
            String productName,
            String buyerProfileId,
            String buyerName,
            UUID organizationId,
            String returnType,
            int quantity,
            String reason,
            String status,
            String resolutionNote,
            Instant createdAt,
            Instant resolvedAt,
            Long version) {
        public OrderReturnResponse(UUID id, UUID orderId, UUID orderLineId, UUID productId, String productName,
                String buyerProfileId, String buyerName, UUID organizationId, String returnType, int quantity,
                String reason, String status, String resolutionNote, Instant createdAt, Instant resolvedAt) {
            this(id, orderId, orderLineId, productId, productName, buyerProfileId, buyerName, organizationId,
                    returnType, quantity, reason, status, resolutionNote, createdAt, resolvedAt, 0L);
        }

        public OrderReturnResponse(UUID id, UUID orderId, UUID orderLineId, UUID productId, String productName,
                String buyerProfileId, UUID organizationId, String returnType, int quantity, String reason,
                String status, String resolutionNote, Instant createdAt, Instant resolvedAt) {
            this(id, orderId, orderLineId, productId, productName, buyerProfileId, null, organizationId,
                    returnType, quantity, reason, status, resolutionNote, createdAt, resolvedAt, 0L);
        }
    }

    public record VerifyOrderLineReviewEligibilityResponse(
            boolean eligible,
            UUID orderLineId,
            UUID orderId,
            UUID productId,
            String fulfillmentStatus) {
    }
}
