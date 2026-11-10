package com.homecare.commerce.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.commerce.dto.CommerceDtos.CreateOrderRequest;
import com.homecare.commerce.dto.CommerceDtos.CreateOrderReturnRequest;
import com.homecare.commerce.dto.CommerceDtos.OrderLineRequest;
import com.homecare.commerce.dto.CommerceDtos.OrderLineResponse;
import com.homecare.commerce.dto.CommerceDtos.OrderResponse;
import com.homecare.commerce.dto.CommerceDtos.OrderReturnResponse;
import com.homecare.commerce.dto.CommerceDtos.PaymentAttemptResponse;
import com.homecare.commerce.dto.CommerceDtos.ProductResponse;
import com.homecare.commerce.dto.CommerceDtos.ResolveOrderReturnRequest;
import com.homecare.commerce.dto.CommerceDtos.VerifyOrderLineReviewEligibilityResponse;
import com.homecare.commerce.entity.CommerceOutboxEventEntity;
import com.homecare.commerce.entity.InventoryEntity;
import com.homecare.commerce.entity.InventoryReservationEntity;
import com.homecare.commerce.entity.MerchantEntity;
import com.homecare.commerce.entity.OrderEntity;
import com.homecare.commerce.entity.OrderLineEntity;
import com.homecare.commerce.entity.OrderReturnRequestEntity;
import com.homecare.commerce.entity.PaymentAttemptEntity;
import com.homecare.commerce.entity.ProductEntity;
import com.homecare.commerce.repository.CommerceOutboxEventRepository;
import com.homecare.commerce.repository.InventoryRepository;
import com.homecare.commerce.repository.InventoryReservationRepository;
import com.homecare.commerce.repository.MerchantRepository;
import com.homecare.commerce.repository.OrderLineRepository;
import com.homecare.commerce.repository.OrderRepository;
import com.homecare.commerce.repository.OrderReturnRequestRepository;
import com.homecare.commerce.repository.PaymentAttemptRepository;
import com.homecare.commerce.repository.ProductRepository;
import com.homecare.commerce.exception.CommerceErrorCode;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CommerceDomainService {

    private static final Logger log = LoggerFactory.getLogger(CommerceDomainService.class);
    public static final String EXCHANGE = "homecare.domain.events";

    private final MerchantRepository merchantRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryReservationRepository reservationRepository;
    private final OrderRepository orderRepository;
    private final OrderLineRepository orderLineRepository;
    private final OrderReturnRequestRepository returnRequestRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final CommerceOutboxEventRepository outboxEventRepository;
    private final VnpaySandboxStrategy vnpayStrategy;
    private final RabbitTemplate rabbitTemplate;

    @Transactional(readOnly = true)
    public List<ProductResponse> listProducts(String category) {
        return productRepository.findByStatusOrderByCreatedAtDesc("ACTIVE")
                .stream()
                .filter(p -> category == null || category.isBlank() || p.getCategory().equalsIgnoreCase(category.trim()))
                .map(this::toProductResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductResponse getProduct(UUID productId) {
        ProductEntity p = productRepository.findById(productId)
                .orElseThrow(() -> new DomainException(
                        HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Product not found."));
        return toProductResponse(p);
    }

    @Transactional
    public OrderResponse createOrder(String buyerProfileId, CreateOrderRequest request) {
        Set<UUID> merchantIds = new HashSet<>();
        long total = 0L;
        Instant now = Instant.now();
        Instant quoteExpiresAt = now.plus(15, ChronoUnit.MINUTES);
        UUID orderId = UUID.randomUUID();
        List<OrderLineEntity> linesToSave = new ArrayList<>();
        List<InventoryReservationEntity> reservationsToSave = new ArrayList<>();

        for (OrderLineRequest item : request.items()) {
            ProductEntity product = productRepository.findById(item.productId())
                    .orElseThrow(() -> new DomainException(
                            CommerceErrorCode.PRODUCT_NOT_FOUND, "Product not found: " + item.productId()));
            merchantIds.add(product.getMerchantId());
            if (merchantIds.size() > 1) {
                throw new DomainException(CommerceErrorCode.MIXED_MERCHANT_ORDER);
            }
            InventoryEntity inv = inventoryRepository.findByProductId(product.getId())
                    .orElseThrow(() -> new DomainException(
                            CommerceErrorCode.STOCK_INSUFFICIENT, "Inventory not found."));
            if (inv.getAvailableQty() < item.quantity()) {
                throw new DomainException(
                        CommerceErrorCode.STOCK_INSUFFICIENT,
                        "Insufficient stock for product " + product.getName());
            }
            inv.setAvailableQty(inv.getAvailableQty() - item.quantity());
            inv.setReservedQty(inv.getReservedQty() + item.quantity());
            inv.setUpdatedAt(now);
            inventoryRepository.save(inv);

            reservationsToSave.add(InventoryReservationEntity.builder()
                    .id(UUID.randomUUID())
                    .orderId(orderId)
                    .inventoryBalanceId(inv.getId())
                    .productId(product.getId())
                    .quantity(item.quantity())
                    .status("ACTIVE")
                    .reservedAt(now)
                    .expiresAt(quoteExpiresAt)
                    .build());

            long lineTotal = product.getUnitPrice() * item.quantity();
            total += lineTotal;
            linesToSave.add(OrderLineEntity.builder()
                    .id(UUID.randomUUID())
                    .orderId(orderId)
                    .productId(product.getId())
                    .productName(product.getName())
                    .quantity(item.quantity())
                    .unitPrice(product.getUnitPrice())
                    .lineTotal(lineTotal)
                    .fulfillmentStatus("PENDING")
                    .build());
        }

        OrderEntity savedOrder = orderRepository.saveAndFlush(OrderEntity.builder()
                .id(orderId)
                .buyerProfileId(buyerProfileId)
                .merchantId(merchantIds.iterator().next())
                .shippingAddress(request.shippingAddress().trim())
                .totalAmount(total)
                .status("AWAITING_PAYMENT")
                .paymentStatus("UNPAID")
                .quoteExpiresAt(quoteExpiresAt)
                .createdAt(now)
                .updatedAt(now)
                .build());

        orderLineRepository.saveAll(linesToSave);
        reservationRepository.saveAll(reservationsToSave);
        publishDomainEvent("order.created.v1", savedOrder);
        return toOrderResponse(savedOrder);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> listOrders(String buyerProfileId) {
        return orderRepository.findByBuyerProfileIdOrderByCreatedAtDesc(buyerProfileId)
                .stream()
                .map(this::toOrderResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(String buyerProfileId, UUID orderId) {
        OrderEntity order = requireOwnedOrder(buyerProfileId, orderId);
        return toOrderResponse(order);
    }

    @Transactional
    public PaymentAttemptResponse createPaymentAttempt(String buyerProfileId, UUID orderId) {
        return createPaymentAttempt(buyerProfileId, orderId, null);
    }

    @Transactional
    public PaymentAttemptResponse createPaymentAttempt(String buyerProfileId, UUID orderId, String customReturnUrl) {
        OrderEntity order = requireOwnedOrder(buyerProfileId, orderId);
        if ("PAID".equals(order.getPaymentStatus())) {
            throw new DomainException(CommerceErrorCode.ORDER_INVALID_STATE, "Order is already paid.");
        }
        if (Instant.now().isAfter(order.getQuoteExpiresAt())) {
            throw new DomainException(CommerceErrorCode.ORDER_QUOTE_EXPIRED);
        }
        String txnRef = "HC-" + order.getId().toString().substring(0, 8).toUpperCase()
                + "-" + (System.currentTimeMillis() % 100_000);
        String url = vnpayStrategy.buildPaymentUrl(
                txnRef, order.getTotalAmount(), "Thanh toan don hang " + order.getId(), customReturnUrl);
        Instant now = Instant.now();
        PaymentAttemptEntity saved = paymentAttemptRepository.save(PaymentAttemptEntity.builder()
                .id(UUID.randomUUID())
                .orderId(order.getId())
                .txnRef(txnRef)
                .provider("VNPAY_SANDBOX")
                .amount(order.getTotalAmount())
                .status("PENDING")
                .paymentUrl(url)
                .createdAt(now)
                .updatedAt(now)
                .build());
        return toPaymentAttemptResponse(saved);
    }

    @Transactional
    public Map<String, String> handleVnpayIpn(Map<String, String> params) {
        if (!vnpayStrategy.verifyIpnSignature(params)) {
            return Map.of("RspCode", "97", "Message", "Invalid Checksum");
        }
        String txnRef = params.get("vnp_TxnRef");
        String rspCode = params.getOrDefault("vnp_ResponseCode", "99");
        PaymentAttemptEntity attempt = paymentAttemptRepository.findByTxnRef(txnRef).orElse(null);
        if (attempt == null) {
            return Map.of("RspCode", "01", "Message", "Order not Found");
        }
        if (!"PENDING".equals(attempt.getStatus())) {
            return Map.of("RspCode", "02", "Message", "Order already confirmed");
        }
        OrderEntity order = orderRepository.findById(attempt.getOrderId()).orElseThrow();
        Instant now = Instant.now();
        attempt.setResponseCode(rspCode);
        attempt.setUpdatedAt(now);

        if ("00".equals(rspCode)) {
            attempt.setStatus("SUCCEEDED");
            paymentAttemptRepository.save(attempt);

            order.setPaymentStatus("PAID");
            order.setStatus("CONFIRMED");
            order.setUpdatedAt(now);
            orderRepository.save(order);

            for (OrderLineEntity line : orderLineRepository.findByOrderId(order.getId())) {
                inventoryRepository.findByProductId(line.getProductId()).ifPresent(inv -> {
                    inv.setReservedQty(Math.max(0, inv.getReservedQty() - line.getQuantity()));
                    inv.setUpdatedAt(now);
                    inventoryRepository.save(inv);
                });
            }
            for (InventoryReservationEntity res : reservationRepository.findByOrderId(order.getId())) {
                res.setStatus("CONFIRMED");
                res.setResolvedAt(now);
                reservationRepository.save(res);
            }
            publishDomainEvent("order.payment.succeeded.v1", order);
        } else {
            attempt.setStatus("FAILED");
            paymentAttemptRepository.save(attempt);

            order.setPaymentStatus("FAILED");
            order.setStatus("PAYMENT_FAILED");
            order.setUpdatedAt(now);
            orderRepository.save(order);

            for (OrderLineEntity line : orderLineRepository.findByOrderId(order.getId())) {
                inventoryRepository.findByProductId(line.getProductId()).ifPresent(inv -> {
                    inv.setReservedQty(Math.max(0, inv.getReservedQty() - line.getQuantity()));
                    inv.setAvailableQty(inv.getAvailableQty() + line.getQuantity());
                    inv.setUpdatedAt(now);
                    inventoryRepository.save(inv);
                });
            }
            for (InventoryReservationEntity res : reservationRepository.findByOrderId(order.getId())) {
                res.setStatus("RELEASED");
                res.setResolvedAt(now);
                reservationRepository.save(res);
            }
            publishDomainEvent("order.payment.failed.v1", order);
        }
        return Map.of("RspCode", "00", "Message", "Confirm Success");
    }

    @Transactional
    public OrderResponse fulfillOrderLine(String actorProfileId, UUID orderId, UUID lineId) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        OrderLineEntity line = orderLineRepository.findById(lineId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_LINE_NOT_FOUND));
        if (!line.getOrderId().equals(orderId)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Order line does not belong to order.");
        }
        Instant now = Instant.now();
        line.setFulfillmentStatus("DELIVERED");
        line.setDeliveredAt(now);
        orderLineRepository.save(line);

        ProductEntity product = productRepository.findById(line.getProductId()).orElse(null);
        String merchantName = merchantRepository.findById(order.getMerchantId())
                .map(MerchantEntity::getName)
                .orElse("Cửa hàng HomeCare");
        publishOrderLineDeliveredEvent(order, line, product, merchantName, now);

        boolean allDelivered = orderLineRepository.findByOrderId(orderId)
                .stream()
                .allMatch(l -> "DELIVERED".equals(l.getFulfillmentStatus()));
        if (allDelivered) {
            order.setStatus("DELIVERED");
            order.setUpdatedAt(now);
            orderRepository.save(order);
        }
        return toOrderResponse(order);
    }

    @Transactional
    public OrderReturnResponse createOrderReturnRequest(
            String buyerProfileId,
            UUID orderId,
            UUID lineId,
            CreateOrderReturnRequest request) {
        OrderEntity order = requireOwnedOrder(buyerProfileId, orderId);
        OrderLineEntity line = orderLineRepository.findById(lineId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_LINE_NOT_FOUND));
        if (!line.getOrderId().equals(orderId)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Order line does not belong to order.");
        }
        if (!"DELIVERED".equals(line.getFulfillmentStatus())) {
            throw new DomainException(CommerceErrorCode.ORDER_LINE_NOT_DELIVERED);
        }
        if (request.quantity() > line.getQuantity()) {
            throw new DomainException(CommerceErrorCode.RETURN_QUANTITY_EXCEEDED);
        }
        ProductEntity product = productRepository.findById(line.getProductId())
                .orElseThrow(() -> new DomainException(CommerceErrorCode.PRODUCT_NOT_FOUND));
        Instant deliveredAt = line.getDeliveredAt() != null ? line.getDeliveredAt() : order.getUpdatedAt();
        Instant returnDeadline = deliveredAt.plus(product.getReturnWindowDays(), ChronoUnit.DAYS);
        if (product.getReturnWindowDays() <= 0 || Instant.now().isAfter(returnDeadline)) {
            throw new DomainException(
                    CommerceErrorCode.RETURN_WINDOW_EXPIRED,
                    "Return window of " + product.getReturnWindowDays() + " days has expired.");
        }

        String returnType = request.returnType().trim().toUpperCase();
        if (!"REFUND".equals(returnType) && !"EXCHANGE".equals(returnType)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "returnType must be REFUND or EXCHANGE.");
        }

        OrderReturnRequestEntity saved = returnRequestRepository.save(OrderReturnRequestEntity.builder()
                .id(UUID.randomUUID())
                .orderId(order.getId())
                .orderLineId(line.getId())
                .productId(product.getId())
                .productName(product.getName())
                .buyerProfileId(buyerProfileId)
                .organizationId(order.getMerchantId())
                .returnType(returnType)
                .quantity(request.quantity())
                .reason(request.reason().trim())
                .status("REQUESTED")
                .createdAt(Instant.now())
                .build());
        return toOrderReturnResponse(saved);
    }

    @Transactional
    public OrderReturnResponse resolveOrderReturnRequest(
            String actorProfileId,
            UUID orderId,
            UUID returnId,
            ResolveOrderReturnRequest request) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        OrderReturnRequestEntity ret = returnRequestRepository.findById(returnId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.RETURN_REQUEST_NOT_FOUND));
        if (!ret.getOrderId().equals(orderId)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Return request does not belong to order.");
        }
        if (!"REQUESTED".equals(ret.getStatus())) {
            throw new DomainException(CommerceErrorCode.RETURN_ALREADY_RESOLVED);
        }

        String action = request.action().trim().toUpperCase();
        Instant now = Instant.now();
        ret.setResolutionNote(request.resolutionNote());
        ret.setResolvedAt(now);

        if ("APPROVE".equals(action) || "APPROVED".equals(action)) {
            ret.setStatus("APPROVED");
            OrderLineEntity line = orderLineRepository.findById(ret.getOrderLineId()).orElseThrow();
            if ("REFUND".equalsIgnoreCase(ret.getReturnType())) {
                line.setFulfillmentStatus("RETURNED");
                inventoryRepository.findByProductId(ret.getProductId()).ifPresent(inv -> {
                    inv.setAvailableQty(inv.getAvailableQty() + ret.getQuantity());
                    inv.setUpdatedAt(now);
                    inventoryRepository.save(inv);
                });
            } else {
                line.setFulfillmentStatus("EXCHANGED");
            }
            orderLineRepository.save(line);
            publishReturnApprovedEvent(order, ret, now);
        } else if ("REJECT".equals(action) || "REJECTED".equals(action)) {
            ret.setStatus("REJECTED");
        } else {
            throw new DomainException(
                    HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "action must be APPROVE or REJECT.");
        }

        return toOrderReturnResponse(returnRequestRepository.save(ret));
    }

    @Transactional(readOnly = true)
    public List<OrderReturnResponse> listOrderReturns(UUID orderId) {
        return returnRequestRepository.findByOrderIdOrderByCreatedAtDesc(orderId)
                .stream()
                .map(this::toOrderReturnResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public VerifyOrderLineReviewEligibilityResponse verifyOrderLineReviewEligibility(
            UUID orderLineId,
            String requesterProfileId) {
        return orderLineRepository.findById(orderLineId)
                .flatMap(line -> orderRepository.findById(line.getOrderId())
                        .filter(order -> order.getBuyerProfileId().equals(requesterProfileId)
                                && "DELIVERED".equals(line.getFulfillmentStatus()))
                        .map(order -> new VerifyOrderLineReviewEligibilityResponse(
                                true,
                                line.getId(),
                                order.getId(),
                                line.getProductId(),
                                line.getFulfillmentStatus())))
                .orElseGet(() -> new VerifyOrderLineReviewEligibilityResponse(
                        false,
                        orderLineId,
                        null,
                        null,
                        "NOT_ELIGIBLE"));
    }

    private OrderEntity requireOwnedOrder(String buyerProfileId, UUID orderId) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        if (!order.getBuyerProfileId().equals(buyerProfileId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Not the owner of this order.");
        }
        return order;
    }

    private void publishOrderLineDeliveredEvent(
            OrderEntity order,
            OrderLineEntity line,
            ProductEntity product,
            String merchantName,
            Instant now) {
        UUID eventId = UUID.randomUUID();
        String eventType = "order.line.delivered.v1";
        String category = product != null ? product.getCategory() : "AC";
        String brand = product != null ? product.getBrand() : "HomeCare";
        String productType = product != null ? product.getProductType() : "SMALL_PART";
        int warrantyMonths = product != null ? product.getWarrantyMonths() : 0;
        int returnWindowDays = product != null ? product.getReturnWindowDays() : 7;

        String payload = """
                {"eventId":"%s","eventType":"%s","occurredAt":"%s","producer":"commerce-service","correlationId":"%s","data":{"orderId":"%s","orderLineId":"%s","buyerProfileId":"%s","organizationId":"%s","merchantName":"%s","productId":"%s","productName":"%s","category":"%s","brand":"%s","productType":"%s","warrantyMonths":%d,"returnWindowDays":%d}}
                """.formatted(
                eventId,
                eventType,
                now,
                order.getId(),
                order.getId(),
                line.getId(),
                order.getBuyerProfileId(),
                order.getMerchantId(),
                merchantName,
                line.getProductId(),
                line.getProductName(),
                category,
                brand,
                productType,
                warrantyMonths,
                returnWindowDays).strip();

        saveAndSendOutbox(eventId, "CustomerOrder", order.getId(), eventType, payload, now);
    }

    private void publishReturnApprovedEvent(OrderEntity order, OrderReturnRequestEntity ret, Instant now) {
        UUID eventId = UUID.randomUUID();
        String eventType = "order.return.approved.v1";
        String payload = """
                {"eventId":"%s","eventType":"%s","occurredAt":"%s","producer":"commerce-service","correlationId":"%s","data":{"returnId":"%s","orderId":"%s","orderLineId":"%s","buyerProfileId":"%s","organizationId":"%s","productName":"%s","returnType":"%s","quantity":%d}}
                """.formatted(
                eventId,
                eventType,
                now,
                order.getId(),
                ret.getId(),
                order.getId(),
                ret.getOrderLineId(),
                ret.getBuyerProfileId(),
                ret.getOrganizationId(),
                ret.getProductName(),
                ret.getReturnType(),
                ret.getQuantity()).strip();
        saveAndSendOutbox(eventId, "CustomerOrder", order.getId(), eventType, payload, now);
    }

    private void publishDomainEvent(String eventType, OrderEntity order) {
        Instant now = Instant.now();
        UUID eventId = UUID.randomUUID();
        String payload = """
                {"eventId":"%s","eventType":"%s","occurredAt":"%s","producer":"commerce-service","correlationId":"%s","data":{"orderId":"%s","buyerProfileId":"%s","totalAmount":%d,"status":"%s","paymentStatus":"%s"}}
                """.formatted(
                eventId,
                eventType,
                now,
                order.getId(),
                order.getId(),
                order.getBuyerProfileId(),
                order.getTotalAmount(),
                order.getStatus(),
                order.getPaymentStatus()).strip();

        saveAndSendOutbox(eventId, "CustomerOrder", order.getId(), eventType, payload, now);
    }

    private void saveAndSendOutbox(
            UUID eventId,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String payload,
            Instant now) {
        CommerceOutboxEventEntity outbox = outboxEventRepository.save(CommerceOutboxEventEntity.builder()
                .id(eventId)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .routingKey(eventType)
                .payloadJson(payload)
                .status("PENDING")
                .retryCount(0)
                .nextAttemptAt(now)
                .createdAt(now)
                .build());

        try {
            rabbitTemplate.convertAndSend(EXCHANGE, eventType, payload);
            outbox.setStatus("PUBLISHED");
            outbox.setPublishedAt(Instant.now());
            outboxEventRepository.save(outbox);
        } catch (Exception e) {
            log.warn("RabbitMQ publish skipped in local/test mode for {}: {}", eventType, e.getMessage());
        }
    }

    private ProductResponse toProductResponse(ProductEntity p) {
        String merchantName = merchantRepository.findById(p.getMerchantId())
                .map(MerchantEntity::getName)
                .orElse("HomeCare Mall");
        InventoryEntity inv = inventoryRepository.findByProductId(p.getId()).orElse(null);
        return new ProductResponse(
                p.getId(),
                p.getMerchantId(),
                merchantName,
                p.getSku(),
                p.getName(),
                p.getCategory(),
                p.getBrand(),
                p.getProductType(),
                p.getWarrantyMonths(),
                p.getReturnWindowDays(),
                p.getDescription(),
                p.getImageUrl(),
                p.getUnitPrice(),
                inv != null ? inv.getAvailableQty() : 0,
                inv != null ? inv.getReservedQty() : 0,
                p.getStatus());
    }

    private OrderResponse toOrderResponse(OrderEntity o) {
        List<OrderLineResponse> lines = orderLineRepository.findByOrderId(o.getId())
                .stream()
                .map(l -> new OrderLineResponse(
                        l.getId(),
                        l.getProductId(),
                        l.getProductName(),
                        l.getQuantity(),
                        l.getUnitPrice(),
                        l.getLineTotal(),
                        l.getFulfillmentStatus()))
                .toList();
        List<PaymentAttemptResponse> attempts = paymentAttemptRepository.findByOrderIdOrderByCreatedAtDesc(o.getId())
                .stream()
                .map(this::toPaymentAttemptResponse)
                .toList();
        return new OrderResponse(
                o.getId(),
                o.getBuyerProfileId(),
                o.getMerchantId(),
                o.getShippingAddress(),
                o.getTotalAmount(),
                o.getStatus(),
                o.getPaymentStatus(),
                o.getQuoteExpiresAt(),
                o.getVersion(),
                o.getCreatedAt(),
                o.getUpdatedAt(),
                lines,
                attempts);
    }

    private PaymentAttemptResponse toPaymentAttemptResponse(PaymentAttemptEntity a) {
        return new PaymentAttemptResponse(
                a.getId(),
                a.getOrderId(),
                a.getTxnRef(),
                a.getProvider(),
                a.getAmount(),
                a.getStatus(),
                a.getPaymentUrl(),
                a.getCreatedAt());
    }

    private OrderReturnResponse toOrderReturnResponse(OrderReturnRequestEntity r) {
        return new OrderReturnResponse(
                r.getId(),
                r.getOrderId(),
                r.getOrderLineId(),
                r.getProductId(),
                r.getProductName(),
                r.getBuyerProfileId(),
                r.getOrganizationId(),
                r.getReturnType(),
                r.getQuantity(),
                r.getReason(),
                r.getStatus(),
                r.getResolutionNote(),
                r.getCreatedAt(),
                r.getResolvedAt());
    }
}
