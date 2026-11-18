package com.homecare.commerce.service;

import lombok.RequiredArgsConstructor;

import com.homecare.commerce.client.ProviderOrgClient;
import com.homecare.commerce.dto.CommerceDtos.ConfirmManualRefundRequest;
import com.homecare.commerce.dto.CommerceDtos.CreateOrderRequest;
import com.homecare.commerce.dto.CommerceDtos.CreateOrderReturnRequest;
import com.homecare.commerce.dto.CommerceDtos.OrderLineRequest;
import com.homecare.commerce.dto.CommerceDtos.OrderLineResponse;
import com.homecare.commerce.dto.CommerceDtos.OrderResponse;
import com.homecare.commerce.dto.CommerceDtos.OrderReturnResponse;
import com.homecare.commerce.dto.CommerceDtos.PaymentAttemptResponse;
import com.homecare.commerce.dto.CommerceDtos.ProductInput;
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
import com.homecare.commerce.entity.OrderStatus;
import com.homecare.commerce.entity.PaymentAttemptEntity;
import com.homecare.commerce.entity.PaymentAttemptStatus;
import com.homecare.commerce.entity.ProductEntity;
import com.homecare.commerce.entity.ProductType;
import com.homecare.commerce.entity.RefundOperationEntity;
import com.homecare.commerce.exception.CommerceErrorCode;
import com.homecare.commerce.repository.CommerceOutboxEventRepository;
import com.homecare.commerce.repository.InventoryRepository;
import com.homecare.commerce.repository.InventoryReservationRepository;
import com.homecare.commerce.repository.MerchantRepository;
import com.homecare.commerce.repository.OrderLineRepository;
import com.homecare.commerce.repository.OrderRepository;
import com.homecare.commerce.repository.OrderReturnRequestRepository;
import com.homecare.commerce.repository.PaymentAttemptRepository;
import com.homecare.commerce.repository.ProductRepository;
import com.homecare.commerce.config.CommerceAppProperties;
import com.homecare.commerce.repository.RefundOperationRepository;
import com.homecare.common.web.EtagSupport;
import com.homecare.commerce.security.CommerceAccessPolicy;
import com.homecare.commerce.security.OrderReadScope;
import com.homecare.commerce.security.OrderSpecifications;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.common.domain.EntityStatus;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.common.web.PageResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class CommerceDomainService {

    private static final Logger log = LoggerFactory.getLogger(CommerceDomainService.class);
    public static final String EXCHANGE = DomainEventContracts.EXCHANGE;

    private final MerchantRepository merchantRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final InventoryReservationRepository reservationRepository;
    private final OrderRepository orderRepository;
    private final OrderLineRepository orderLineRepository;
    private final OrderReturnRequestRepository returnRequestRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final RefundOperationRepository refundOperationRepository;
    private final CommerceOutboxEventRepository outboxEventRepository;
    private final VnpaySandboxStrategy vnpayStrategy;
    private final ObjectMapper objectMapper;
    private final ProviderOrgClient providerOrgClient;
    private final CommerceAccessPolicy accessPolicy;
    private final CommerceAppProperties appProperties;

    private MerchantEntity requireMerchant(UUID merchantId, UUID organizationId, String user, boolean admin) {
        var merchant = merchantRepository.findById(merchantId).orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND));
        if (!"ACTIVE".equals(merchant.getStatus())) throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        if (!admin && (organizationId == null || !organizationId.equals(merchant.getOrganizationId())
                || !providerOrgClient.canOperate(organizationId, user))) throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        return merchant;
    }

    private Pageable pageRequest(int page, int size, String sort, boolean products) {
        if (page < 1 || size < 1 || size > 100) throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        String[] parts = (sort == null ? "createdAt,desc" : sort).split(",");
        var allowed = products ? Set.of("createdAt", "name", "unitPrice", "id") : Set.of("createdAt", "totalAmount", "id");
        if (parts.length > 2 || !allowed.contains(parts[0])) throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        String direction = parts.length == 2 ? parts[1] : "asc";
        if (!Set.of("asc", "desc").contains(direction)) throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        var order = Sort.by(Direction.fromString(direction), parts[0]);
        return PageRequest.of(page - 1, size, order.and(Sort.by("id")));
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> productsPage(String category, String q, UUID merchantId,
            Long minPrice, Long maxPrice, Boolean inStock, String sort, int page, int size) {
        return productsPage(category, q, merchantId, minPrice, maxPrice, inStock, sort, false, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> productsPage(String category, String q, UUID merchantId,
            Long minPrice, Long maxPrice, Boolean inStock, String sort, boolean includeInactive, int page, int size) {
        if ((minPrice != null && minPrice < 0) || (maxPrice != null && maxPrice < 0)
                || (minPrice != null && maxPrice != null && minPrice > maxPrice)) throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        Pageable pageable = pageRequest(page, size, sort, true);
        var result = includeInactive
                ? productRepository.searchIncludingInactive(category, q, merchantId, minPrice, maxPrice, inStock, pageable)
                : productRepository.search(category, q, merchantId, minPrice, maxPrice, inStock, pageable);
        List<ProductEntity> content = result.getContent();
        if (content.isEmpty()) {
            return PageResponse.of(List.of(), page, size, result.getTotalElements());
        }
        var merchantNames = merchantRepository.findAllById(content.stream().map(ProductEntity::getMerchantId).collect(java.util.stream.Collectors.toSet()))
                .stream().collect(java.util.stream.Collectors.toMap(MerchantEntity::getId, MerchantEntity::getName));
        var invByProduct = inventoryRepository.findByProductIdIn(content.stream().map(ProductEntity::getId).toList())
                .stream().collect(java.util.stream.Collectors.toMap(InventoryEntity::getProductId, i -> i));
        var items = content.stream()
                .map(p -> toProductResponse(p, merchantNames.getOrDefault(p.getMerchantId(), "HomeCare Mall"), invByProduct.get(p.getId())))
                .toList();
        return PageResponse.of(items, page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> ordersPage(
            ActorContext actor, String status, String q, String sort, int page, int size) {
        MerchantEntity merchant = actor.mode() == AccessMode.ORGANIZATION
                ? merchantRepository.findByOrganizationId(actor.organizationId())
                        .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN))
                : null;
        OrderReadScope scope = accessPolicy.orderReadScope(actor, merchant);
        var result = orderRepository.findAll(
                OrderSpecifications.withScope(scope, status, q),
                pageRequest(page, size, sort, false));
        List<OrderEntity> content = result.getContent();
        if (content.isEmpty()) {
            return PageResponse.of(List.of(), page, size, result.getTotalElements());
        }
        List<UUID> orderIds = content.stream().map(OrderEntity::getId).toList();
        var linesByOrder = orderLineRepository.findByOrderIdIn(orderIds).stream()
                .collect(java.util.stream.Collectors.groupingBy(OrderLineEntity::getOrderId));
        var attemptsByOrder = paymentAttemptRepository.findByOrderIdInOrderByCreatedAtDesc(orderIds).stream()
                .collect(java.util.stream.Collectors.groupingBy(PaymentAttemptEntity::getOrderId));
        var items = content.stream()
                .map(o -> toOrderResponse(o,
                        linesByOrder.getOrDefault(o.getId(), List.of()),
                        attemptsByOrder.getOrDefault(o.getId(), List.of())))
                .toList();
        return PageResponse.of(items, page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderForPortal(ActorContext actor, UUID id) {
        var order = orderRepository.findById(id).orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        MerchantEntity merchant = merchantRepository.findById(order.getMerchantId()).orElse(null);
        if (!accessPolicy.isOrderVisible(actor, order, merchant)) {
            throw new DomainException(CommerceErrorCode.ORDER_NOT_FOUND);
        }
        return toOrderResponse(order);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> ordersPage(String user, UUID organizationId, boolean admin,
            boolean operator, String status, String q, String sort, int page, int size) {
        UUID merchant = null;
        if (organizationId != null) {
            if (!admin && !operator) {
                throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
            }
            var m = merchantRepository.findByOrganizationId(organizationId).orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN));
            requireMerchant(m.getId(), organizationId, user, admin);
            merchant = m.getId();
        }
        boolean orgOrAdminScope = admin || (operator && merchant != null);
        var result = orderRepository.search(orgOrAdminScope ? null : user, merchant, status, q, pageRequest(page, size, sort, false));
        List<OrderEntity> content = result.getContent();
        if (content.isEmpty()) {
            return PageResponse.of(List.of(), page, size, result.getTotalElements());
        }
        List<UUID> orderIds = content.stream().map(OrderEntity::getId).toList();
        var linesByOrder = orderLineRepository.findByOrderIdIn(orderIds).stream()
                .collect(java.util.stream.Collectors.groupingBy(OrderLineEntity::getOrderId));
        var attemptsByOrder = paymentAttemptRepository.findByOrderIdInOrderByCreatedAtDesc(orderIds).stream()
                .collect(java.util.stream.Collectors.groupingBy(PaymentAttemptEntity::getOrderId));
        var items = content.stream()
                .map(o -> toOrderResponse(o,
                        linesByOrder.getOrDefault(o.getId(), List.of()),
                        attemptsByOrder.getOrDefault(o.getId(), List.of())))
                .toList();
        return PageResponse.of(items, page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderForPortal(String user, UUID id, UUID org, boolean admin) {
        var order = orderRepository.findById(id).orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        if (!user.equals(order.getBuyerProfileId())) requireMerchant(order.getMerchantId(), org, user, admin);
        return toOrderResponse(order);
    }

    @Transactional
    public ProductResponse saveProduct(UUID id, ProductInput input, ActorContext actor) {
        var item = id == null ? new ProductEntity() : productRepository.findById(id).orElseThrow(() -> new DomainException(CommerceErrorCode.PRODUCT_NOT_FOUND));
        UUID merchantId = id == null ? input.merchantId() : item.getMerchantId();
        if (merchantId == null) throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        MerchantEntity merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND));
        accessPolicy.assertCanManageCatalog(actor, merchant);
        return saveProductInternal(id, item, merchantId, input);
    }

    @Transactional
    public ProductResponse saveProduct(UUID id, ProductInput input, UUID org, String user, boolean admin) {
        var item = id == null ? new ProductEntity() : productRepository.findById(id).orElseThrow(() -> new DomainException(CommerceErrorCode.PRODUCT_NOT_FOUND));
        UUID merchant = id == null ? input.merchantId() : item.getMerchantId();
        if (merchant == null) throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        requireMerchant(merchant, org, user, admin);
        return saveProductInternal(id, item, merchant, input);
    }

    private ProductResponse saveProductInternal(UUID id, ProductEntity item, UUID merchant, ProductInput input) {
        if (input.merchantId() != null && !merchant.equals(input.merchantId())) throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        if (id == null) {
            if (input.sku() == null || input.name() == null || input.category() == null || input.brand() == null || input.unitPrice() == null) throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
            item.setId(UUID.randomUUID()); item.setMerchantId(merchant); item.setCreatedAt(Instant.now());
            item.setStatus("ACTIVE"); item.setProductType(ProductType.SMALL_PART.name()); item.setReturnWindowDays(7);
        }
        if (input.sku() != null) item.setSku(input.sku());
        if (input.name() != null) item.setName(input.name());
        if (input.category() != null) item.setCategory(input.category());
        if (input.brand() != null) item.setBrand(input.brand());
        if (input.productType() != null) item.setProductType(input.productType().name());
        if (input.warrantyMonths() != null) item.setWarrantyMonths(input.warrantyMonths());
        if (input.returnWindowDays() != null) item.setReturnWindowDays(input.returnWindowDays());
        if (input.description() != null) item.setDescription(input.description());
        if (input.imageUrl() != null) item.setImageUrl(input.imageUrl());
        if (input.unitPrice() != null) item.setUnitPrice(input.unitPrice());
        if (input.status() != null) item.setStatus(input.status());
        productRepository.saveAndFlush(item);
        if (id == null) inventoryRepository.save(InventoryEntity.builder().id(UUID.randomUUID()).productId(item.getId()).updatedAt(Instant.now()).build());
        return toProductResponse(item);
    }

    @Transactional
    public void deleteProduct(UUID id, ActorContext actor) {
        var item = productRepository.findById(id).orElseThrow(() -> new DomainException(CommerceErrorCode.PRODUCT_NOT_FOUND));
        MerchantEntity merchant = merchantRepository.findById(item.getMerchantId())
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND));
        accessPolicy.assertCanManageCatalog(actor, merchant);
        item.setStatus("INACTIVE"); productRepository.save(item);
    }

    @Transactional
    public void deleteProduct(UUID id, UUID org, String user, boolean admin) {
        var item = productRepository.findById(id).orElseThrow(() -> new DomainException(CommerceErrorCode.PRODUCT_NOT_FOUND));
        requireMerchant(item.getMerchantId(), org, user, admin); item.setStatus("INACTIVE"); productRepository.save(item);
    }

    @Transactional
    public ProductResponse updateInventory(UUID id, int quantity, ActorContext actor) {
        if (quantity < 0) throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        var item = productRepository.findById(id).orElseThrow(() -> new DomainException(CommerceErrorCode.PRODUCT_NOT_FOUND));
        MerchantEntity merchant = merchantRepository.findById(item.getMerchantId())
                .orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND));
        accessPolicy.assertCanManageCatalog(actor, merchant);
        var inventory = inventoryRepository.lockByProductId(id).orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND));
        inventory.setAvailableQty(quantity); inventory.setUpdatedAt(Instant.now()); inventoryRepository.save(inventory);
        return toProductResponse(item);
    }

    @Transactional
    public ProductResponse updateInventory(UUID id, int quantity, UUID org, String user, boolean admin) {
        if (quantity < 0) throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        var item = productRepository.findById(id).orElseThrow(() -> new DomainException(CommerceErrorCode.PRODUCT_NOT_FOUND));
        requireMerchant(item.getMerchantId(), org, user, admin);
        var inventory = inventoryRepository.lockByProductId(id).orElseThrow(() -> new DomainException(CommonErrorCode.RESOURCE_NOT_FOUND));
        inventory.setAvailableQty(quantity); inventory.setUpdatedAt(Instant.now()); inventoryRepository.save(inventory);
        return toProductResponse(item);
    }

    @Transactional
    public OrderResponse cancelOrder(UUID id, ActorContext actor) {
        var order = orderRepository.lockById(id).orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        MerchantEntity merchant = merchantRepository.findById(order.getMerchantId()).orElse(null);
        accessPolicy.assertCanCancelOrder(actor, order, merchant);
        return cancelOrderInternal(order);
    }

    @Transactional
    public OrderResponse cancelOrder(UUID id, String user, UUID org, boolean admin) {
        var order = orderRepository.lockById(id).orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        if (!user.equals(order.getBuyerProfileId())) requireMerchant(order.getMerchantId(), org, user, admin);
        return cancelOrderInternal(order);
    }

    private OrderResponse cancelOrderInternal(OrderEntity order) {
        if (OrderStatus.CANCELLED.name().equals(order.getStatus())) return toOrderResponse(order);
        if (!OrderStatus.AWAITING_PAYMENT.name().equals(order.getStatus())) throw new DomainException(CommerceErrorCode.ORDER_INVALID_STATE);
        releaseReservations(order);
        order.setStatus(OrderStatus.CANCELLED.name()); order.setUpdatedAt(Instant.now()); orderRepository.save(order);
        publishDomainEvent("order.cancelled.v1", order);
        return toOrderResponse(order);
    }

    @Transactional
    public void expireOrder(UUID id, Instant now) {
        var order = orderRepository.lockById(id).orElse(null);
        if (order == null || !OrderStatus.AWAITING_PAYMENT.name().equals(order.getStatus()) || order.getQuoteExpiresAt().isAfter(now)) return;
        releaseReservations(order);
        order.setStatus(OrderStatus.CANCELLED.name()); order.setUpdatedAt(now); orderRepository.save(order);
        publishDomainEvent("order.cancelled.v1", order);
    }

    private void releaseReservations(OrderEntity order) {
        List<InventoryReservationEntity> reservations = reservationRepository.findByOrderId(order.getId())
                .stream()
                .sorted(Comparator.comparing(InventoryReservationEntity::getProductId))
                .toList();
        for (var reservation : reservations) {
            if (!"ACTIVE".equals(reservation.getStatus())) continue;
            var inventory = inventoryRepository.lockByProductId(reservation.getProductId()).orElseThrow();
            inventory.setAvailableQty(Math.addExact(inventory.getAvailableQty(), reservation.getQuantity()));
            inventory.setReservedQty(inventory.getReservedQty() - reservation.getQuantity());
            inventory.setUpdatedAt(Instant.now()); inventoryRepository.save(inventory);
            reservation.setStatus("RELEASED"); reservation.setResolvedAt(Instant.now()); reservationRepository.save(reservation);
        }
    }

    @Transactional(readOnly = true)
    public ProductResponse getProduct(UUID productId) {
        ProductEntity p = productRepository.findById(productId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.PRODUCT_NOT_FOUND));
        return toProductResponse(p);
    }

    @Transactional
    public OrderResponse createOrder(String buyerProfileId, CreateOrderRequest request) {
        return createOrder(buyerProfileId, null, request);
    }

    @Transactional
    public OrderResponse createOrder(String buyerProfileId, String buyerName, CreateOrderRequest request) {
        Set<UUID> merchantIds = new HashSet<>();
        long total = 0L;
        Instant now = Instant.now();
        Instant quoteExpiresAt = now.plus(15, ChronoUnit.MINUTES);
        UUID orderId = UUID.randomUUID();
        List<OrderLineEntity> linesToSave = new ArrayList<>();
        List<InventoryReservationEntity> reservationsToSave = new ArrayList<>();

        Map<UUID, Integer> quantitiesByProduct = new TreeMap<>();
        for (OrderLineRequest item : request.items()) {
            quantitiesByProduct.merge(item.productId(), item.quantity(), Math::addExact);
        }

        for (var entry : quantitiesByProduct.entrySet()) {
            UUID productId = entry.getKey();
            int quantity = entry.getValue();
            ProductEntity product = productRepository.findById(productId)
                    .orElseThrow(() -> new DomainException(
                            CommerceErrorCode.PRODUCT_NOT_FOUND, "Product not found: " + productId));
            if (!"ACTIVE".equals(product.getStatus())) throw new DomainException(CommerceErrorCode.PRODUCT_NOT_FOUND);
            merchantIds.add(product.getMerchantId());
            if (merchantIds.size() > 1) {
                throw new DomainException(CommerceErrorCode.MIXED_MERCHANT_ORDER);
            }
            InventoryEntity inv = inventoryRepository.lockByProductId(product.getId())
                    .orElseThrow(() -> new DomainException(
                            CommerceErrorCode.STOCK_INSUFFICIENT, "Inventory not found."));
            if (inv.getAvailableQty() < quantity) {
                throw new DomainException(
                        CommerceErrorCode.STOCK_INSUFFICIENT,
                        "Insufficient stock for product " + product.getName());
            }
            inv.setAvailableQty(inv.getAvailableQty() - quantity);
            inv.setReservedQty(inv.getReservedQty() + quantity);
            inv.setUpdatedAt(now);
            inventoryRepository.save(inv);

            reservationsToSave.add(InventoryReservationEntity.builder()
                    .id(UUID.randomUUID())
                    .orderId(orderId)
                    .inventoryBalanceId(inv.getId())
                    .productId(product.getId())
                    .quantity(quantity)
                    .status(EntityStatus.ACTIVE.name())
                    .reservedAt(now)
                    .expiresAt(quoteExpiresAt)
                    .build());

            long lineTotal = Math.multiplyExact(product.getUnitPrice(), quantity);
            total = Math.addExact(total, lineTotal);
            linesToSave.add(OrderLineEntity.builder()
                    .id(UUID.randomUUID())
                    .orderId(orderId)
                    .productId(product.getId())
                    .productName(product.getName())
                    .quantity(quantity)
                    .unitPrice(product.getUnitPrice())
                    .lineTotal(lineTotal)
                    .fulfillmentStatus("PENDING")
                    .build());
        }

        OrderEntity savedOrder = orderRepository.saveAndFlush(OrderEntity.builder()
                .id(orderId)
                .buyerProfileId(buyerProfileId)
                .buyerName(buyerName)
                .merchantId(merchantIds.iterator().next())
                .shippingAddress(request.shippingAddress().trim())
                .totalAmount(total)
                .status(OrderStatus.AWAITING_PAYMENT.name())
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
        if (!OrderStatus.AWAITING_PAYMENT.name().equals(order.getStatus())) {
            throw new DomainException(CommerceErrorCode.ORDER_INVALID_STATE, "Order is already paid.");
        }
        if (Instant.now().isAfter(order.getQuoteExpiresAt())) {
            throw new DomainException(CommerceErrorCode.ORDER_QUOTE_EXPIRED);
        }
        String txnRef = "HC-" + order.getId().toString().substring(0, 8).toUpperCase()
                + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String url = vnpayStrategy.buildPaymentUrl(
                txnRef, order.getTotalAmount(), "Thanh toan don hang " + order.getId(), customReturnUrl);
        Instant now = Instant.now();
        PaymentAttemptEntity saved = paymentAttemptRepository.save(PaymentAttemptEntity.builder()
                .id(UUID.randomUUID())
                .orderId(order.getId())
                .txnRef(txnRef)
                .provider("VNPAY_SANDBOX")
                .amount(order.getTotalAmount())
                .status(PaymentAttemptStatus.PENDING.name())
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
        String tmnCode = params.get("vnp_TmnCode");
        if (tmnCode == null || !vnpayStrategy.getTmnCode().equals(tmnCode)) {
            return Map.of("RspCode", "97", "Message", "Invalid TmnCode");
        }
        String txnRef = params.get("vnp_TxnRef");
        String rspCode = params.getOrDefault("vnp_ResponseCode", "99");
        PaymentAttemptEntity attempt = paymentAttemptRepository.findByTxnRef(txnRef).orElse(null);
        if (attempt == null) {
            return Map.of("RspCode", "01", "Message", "Order not Found");
        }
        if (!PaymentAttemptStatus.PENDING.name().equals(attempt.getStatus())) {
            return Map.of("RspCode", "02", "Message", "Order already confirmed");
        }
        long vnpAmount;
        try {
            vnpAmount = Long.parseLong(params.getOrDefault("vnp_Amount", "-1"));
        } catch (NumberFormatException e) {
            vnpAmount = -1L;
        }
        if (vnpAmount != attempt.getAmount() * 100L) {
            return Map.of("RspCode", "04", "Message", "Invalid Amount");
        }
        OrderEntity order = orderRepository.lockById(attempt.getOrderId()).orElseThrow();
        if (!OrderStatus.AWAITING_PAYMENT.name().equals(order.getStatus()) || !order.getQuoteExpiresAt().isAfter(Instant.now())) return Map.of("RspCode", "02", "Message", "Order no longer payable");
        Instant now = Instant.now();
        attempt.setResponseCode(rspCode);
        attempt.setUpdatedAt(now);

        if ("00".equals(rspCode)) {
            attempt.setStatus(PaymentAttemptStatus.SUCCEEDED.name());
            attempt.setVnpTransactionNo(params.get("vnp_TransactionNo"));
            attempt.setVnpBankTranNo(params.get("vnp_BankTranNo"));
            attempt.setVnpBankCode(params.get("vnp_BankCode"));
            attempt.setVnpPayDate(params.get("vnp_PayDate"));
            paymentAttemptRepository.save(attempt);

            order.setPaymentStatus("PAID");
            order.setStatus(OrderStatus.CONFIRMED.name());
            order.setUpdatedAt(now);
            orderRepository.save(order);

            List<OrderLineEntity> sortedLines = orderLineRepository.findByOrderId(order.getId())
                    .stream()
                    .sorted(Comparator.comparing(OrderLineEntity::getProductId))
                    .toList();
            for (OrderLineEntity line : sortedLines) {
                inventoryRepository.lockByProductId(line.getProductId()).ifPresent(inv -> {
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
            attempt.setStatus(PaymentAttemptStatus.FAILED.name());
            paymentAttemptRepository.save(attempt);
            publishDomainEvent("order.payment.failed.v1", order);
        }
        return Map.of("RspCode", "00", "Message", "Confirm Success");
    }

    @Transactional
    public OrderResponse fulfillOrderLine(String actorProfileId, UUID orderId, UUID lineId) {
        return fulfillOrderLine(actorProfileId, orderId, lineId, null, true);
    }

    @Transactional
    public OrderResponse fulfillOrderLine(String actorProfileId, UUID orderId, UUID lineId, UUID orgIdHeader) {
        return fulfillOrderLine(actorProfileId, orderId, lineId, orgIdHeader, orgIdHeader == null);
    }

    @Transactional
    public OrderResponse fulfillOrderLine(ActorContext actor, UUID orderId, UUID lineId) {
        OrderEntity order = orderRepository.lockById(orderId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        MerchantEntity merchant = merchantRepository.findById(order.getMerchantId()).orElse(null);
        accessPolicy.assertCanFulfillOrder(actor, merchant);
        return fulfillOrderLineInternal(order, lineId);
    }

    @Transactional
    public OrderResponse fulfillOrderLine(
            String actorProfileId,
            UUID orderId,
            UUID lineId,
            UUID orgIdHeader,
            boolean isGlobalAdmin) {
        OrderEntity order = orderRepository.lockById(orderId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        verifyActiveMerchantScope(actorProfileId, order, orgIdHeader, isGlobalAdmin);
        return fulfillOrderLineInternal(order, lineId);
    }

    private OrderResponse fulfillOrderLineInternal(OrderEntity order, UUID lineId) {
        UUID orderId = order.getId();
        OrderLineEntity line = orderLineRepository.findById(lineId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_LINE_NOT_FOUND));
        if (!line.getOrderId().equals(orderId)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (!"PAID".equals(order.getPaymentStatus()) || !"PENDING".equals(line.getFulfillmentStatus())) {
            throw new DomainException(CommerceErrorCode.ORDER_INVALID_STATE,
                    "Order must be paid and order line must be PENDING.");
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
        return createOrderReturnRequest(buyerProfileId, null, orderId, lineId, request);
    }

    @Transactional
    public OrderReturnResponse createOrderReturnRequest(
            String buyerProfileId,
            String buyerName,
            UUID orderId,
            UUID lineId,
            CreateOrderReturnRequest request) {
        OrderEntity order = orderRepository.lockById(orderId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        if (!order.getBuyerProfileId().equals(buyerProfileId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Not the owner of this order.");
        }
        OrderLineEntity line = orderLineRepository.findById(lineId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_LINE_NOT_FOUND));
        if (!line.getOrderId().equals(orderId)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (!"DELIVERED".equals(line.getFulfillmentStatus())) {
            throw new DomainException(CommerceErrorCode.ORDER_LINE_NOT_DELIVERED);
        }
        int existingReturnedQty = returnRequestRepository.findByOrderIdOrderByCreatedAtDesc(orderId)
                .stream()
                .filter(r -> lineId.equals(r.getOrderLineId()) && !"REJECTED".equalsIgnoreCase(r.getStatus()))
                .mapToInt(OrderReturnRequestEntity::getQuantity)
                .sum();
        if (request.quantity() <= 0 || existingReturnedQty + request.quantity() > line.getQuantity()) {
            throw new DomainException(CommerceErrorCode.RETURN_QUANTITY_EXCEEDED);
        }
        ProductEntity product = productRepository.findById(line.getProductId())
                .orElseThrow(() -> new DomainException(CommerceErrorCode.PRODUCT_NOT_FOUND));
        Instant deliveredAt = line.getDeliveredAt() != null ? line.getDeliveredAt() : order.getUpdatedAt();
        Instant returnDeadline = deliveredAt.plus(product.getReturnWindowDays(), ChronoUnit.DAYS);
        if (product.getReturnWindowDays() <= 0 || Instant.now().isAfter(returnDeadline)) {
            throw new DomainException(CommerceErrorCode.RETURN_WINDOW_EXPIRED);
        }

        String returnType = request.returnType().trim().toUpperCase();
        if (!"REFUND".equals(returnType) && !"EXCHANGE".equals(returnType)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        }
        if ("EXCHANGE".equals(returnType) && !appProperties.getCommerce().isAllowExchangeReturns()) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        }

        OrderReturnRequestEntity saved = returnRequestRepository.save(OrderReturnRequestEntity.builder()
                .id(UUID.randomUUID())
                .orderId(order.getId())
                .orderLineId(line.getId())
                .productId(product.getId())
                .productName(product.getName())
                .buyerProfileId(buyerProfileId)
                .buyerName(buyerName != null ? buyerName : order.getBuyerName())
                .organizationId(order.getMerchantId())
                .returnType(returnType)
                .quantity(request.quantity())
                .reason(request.reason().trim())
                .status("REQUESTED")
                .createdAt(Instant.now())
                .build());
        return toOrderReturnResponse(saved);
    }

    private void verifyActiveMerchantScope(
            String actorProfileId,
            OrderEntity order,
            UUID orgIdHeader,
            boolean isGlobalAdmin) {
        MerchantEntity merchant = merchantRepository.findById(order.getMerchantId()).orElse(null);
        if (merchant != null && EntityStatus.INACTIVE.name().equalsIgnoreCase(merchant.getStatus())) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        if (!isGlobalAdmin) {
            if (orgIdHeader == null
                    || merchant == null
                    || !orgIdHeader.equals(merchant.getOrganizationId())
                    || actorProfileId == null
                    || !providerOrgClient.canOperate(orgIdHeader, actorProfileId)) {
                throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
            }
        }
    }

    @Transactional
    public OrderReturnResponse resolveOrderReturnRequest(
            String actorProfileId,
            UUID orderId,
            UUID returnId,
            ResolveOrderReturnRequest request) {
        return resolveOrderReturnRequest(actorProfileId, orderId, returnId, request, null, true);
    }

    @Transactional
    public OrderReturnResponse resolveOrderReturnRequest(
            String actorProfileId,
            UUID orderId,
            UUID returnId,
            ResolveOrderReturnRequest request,
            UUID orgIdHeader) {
        return resolveOrderReturnRequest(actorProfileId, orderId, returnId, request, orgIdHeader, orgIdHeader == null);
    }

        @Transactional
    public OrderReturnResponse resolveOrderReturnRequest(
            ActorContext actor,
            UUID orderId,
            UUID returnId,
            ResolveOrderReturnRequest request) {
        return resolveOrderReturnRequest(actor, orderId, returnId, request, null);
    }

    @Transactional
    public OrderReturnResponse resolveOrderReturnRequest(
            ActorContext actor,
            UUID orderId,
            UUID returnId,
            ResolveOrderReturnRequest request,
            String ifMatch) {
        OrderEntity order = orderRepository.lockById(orderId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        MerchantEntity merchant = merchantRepository.findById(order.getMerchantId()).orElse(null);
        accessPolicy.assertCanResolveReturn(actor, merchant);
        return resolveOrderReturnRequestInternal(order, returnId, request, ifMatch);
    }

    @Transactional
    public OrderReturnResponse resolveOrderReturnRequest(
            String actorProfileId,
            UUID orderId,
            UUID returnId,
            ResolveOrderReturnRequest request,
            UUID orgIdHeader,
            boolean isGlobalAdmin) {
        OrderEntity order = orderRepository.lockById(orderId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        verifyActiveMerchantScope(actorProfileId, order, orgIdHeader, isGlobalAdmin);
        return resolveOrderReturnRequestInternal(order, returnId, request, null);
    }

    private OrderReturnResponse resolveOrderReturnRequestInternal(
            OrderEntity order,
            UUID returnId,
            ResolveOrderReturnRequest request,
            String ifMatch) {
        UUID orderId = order.getId();
        OrderReturnRequestEntity ret = returnRequestRepository.findById(returnId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.RETURN_REQUEST_NOT_FOUND));
        if (!ret.getOrderId().equals(orderId)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        }
        if (ifMatch != null && !ifMatch.isBlank()) {
            EtagSupport.verifyIfMatch(ifMatch, ret.getVersion());
        }
        if (!"REQUESTED".equals(ret.getStatus())) {
            throw new DomainException(CommerceErrorCode.RETURN_ALREADY_RESOLVED);
        }

        String action = request.action().trim().toUpperCase();
        Instant now = Instant.now();
        ret.setResolutionNote(request.resolutionNote());
        ret.setResolvedAt(now);

        if ("APPROVE".equals(action) || "APPROVED".equals(action)) {
            OrderLineEntity line = orderLineRepository.findById(ret.getOrderLineId()).orElseThrow();
            if ("REFUND".equalsIgnoreCase(ret.getReturnType())) {
                line.setFulfillmentStatus("RETURNED");
                if (!ret.isRestocked()) {
                    inventoryRepository.lockByProductId(ret.getProductId()).ifPresent(inv -> {
                        inv.setAvailableQty(inv.getAvailableQty() + ret.getQuantity());
                        inv.setUpdatedAt(now);
                        inventoryRepository.save(inv);
                    });
                    ret.setRestocked(true);
                }
                boolean refundEnabled = appProperties.getVnpay().isRefundEnabled();
                ret.setStatus(refundEnabled ? "REFUND_FAILED" : "APPROVED_PENDING_MANUAL_REFUND");
                long refundAmount = line.getUnitPrice() * ret.getQuantity();
                PaymentAttemptEntity paidAttempt = paymentAttemptRepository
                        .findByOrderIdOrderByCreatedAtDesc(orderId)
                        .stream()
                        .filter(a -> PaymentAttemptStatus.SUCCEEDED.name().equals(a.getStatus()))
                        .findFirst()
                        .orElse(null);
                refundOperationRepository.findByReturnRequestId(ret.getId())
                        .orElseGet(() -> refundOperationRepository.save(RefundOperationEntity.builder()
                                .id(UUID.randomUUID())
                                .returnRequestId(ret.getId())
                                .orderId(orderId)
                                .txnRef(paidAttempt != null ? paidAttempt.getTxnRef() : null)
                                .vnpTransactionNo(paidAttempt != null ? paidAttempt.getVnpTransactionNo() : null)
                                .amount(refundAmount)
                                .status(refundEnabled ? "REFUND_FAILED" : "PENDING_MANUAL_REFUND")
                                .note(request.resolutionNote())
                                .createdAt(now)
                                .updatedAt(now)
                                .build()));
            } else {
                ret.setStatus("APPROVED");
                line.setFulfillmentStatus("EXCHANGED");
            }
            orderLineRepository.save(line);
            publishReturnApprovedEvent(order, ret, now);
        } else if ("REJECT".equals(action) || "REJECTED".equals(action)) {
            ret.setStatus("REJECTED");
        } else {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        }

        return toOrderReturnResponse(returnRequestRepository.saveAndFlush(ret));
    }

    @Transactional
    public OrderReturnResponse confirmManualRefund(
            ActorContext actor,
            UUID orderId,
            UUID returnId,
            ConfirmManualRefundRequest request,
            String ifMatch) {
        OrderEntity order = orderRepository.lockById(orderId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        MerchantEntity merchant = merchantRepository.findById(order.getMerchantId()).orElse(null);
        accessPolicy.assertCanConfirmManualRefund(actor, merchant);
        OrderReturnRequestEntity ret = returnRequestRepository.findById(returnId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.RETURN_REQUEST_NOT_FOUND));
        if (!ret.getOrderId().equals(orderId)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        }
        EtagSupport.verifyIfMatch(ifMatch, ret.getVersion());
        if (!"APPROVED_PENDING_MANUAL_REFUND".equals(ret.getStatus())
                && !"REFUND_FAILED".equals(ret.getStatus())) {
            throw new DomainException(CommerceErrorCode.RETURN_ALREADY_RESOLVED);
        }
        Instant now = Instant.now();
        ret.setStatus("REFUNDED");
        if (request.note() != null && !request.note().isBlank()) {
            ret.setResolutionNote(request.note());
        }
        ret.setResolvedAt(now);
        OrderReturnRequestEntity saved = returnRequestRepository.saveAndFlush(ret);

        RefundOperationEntity refundOp = refundOperationRepository.findByReturnRequestId(ret.getId())
                .orElseGet(() -> RefundOperationEntity.builder()
                        .id(UUID.randomUUID())
                        .returnRequestId(ret.getId())
                        .orderId(orderId)
                        .amount(0L)
                        .createdAt(now)
                        .build());
        refundOp.setStatus("REFUNDED");
        refundOp.setProviderReference(request.reference());
        refundOp.setActorProfileId(actor.userId());
        refundOp.setNote(request.note());
        refundOp.setUpdatedAt(now);
        refundOperationRepository.save(refundOp);

        publishRefundSucceededEvent(order, saved, now);
        return toOrderReturnResponse(saved);
    }

    @Transactional
    public OrderReturnResponse retryRefund(
            ActorContext actor,
            UUID orderId,
            UUID returnId,
            String ifMatch) {
        OrderEntity order = orderRepository.lockById(orderId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        MerchantEntity merchant = merchantRepository.findById(order.getMerchantId()).orElse(null);
        accessPolicy.assertCanResolveReturn(actor, merchant);
        OrderReturnRequestEntity ret = returnRequestRepository.findById(returnId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.RETURN_REQUEST_NOT_FOUND));
        if (!ret.getOrderId().equals(orderId)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        }
        EtagSupport.verifyIfMatch(ifMatch, ret.getVersion());
        if (!"REFUND_FAILED".equals(ret.getStatus())) {
            throw new DomainException(CommerceErrorCode.RETURN_ALREADY_RESOLVED);
        }
        if (!appProperties.getVnpay().isRefundEnabled()) {
            ret.setStatus("APPROVED_PENDING_MANUAL_REFUND");
            ret.setResolvedAt(Instant.now());
            return toOrderReturnResponse(returnRequestRepository.saveAndFlush(ret));
        }
        return toOrderReturnResponse(ret);
    }

    private void publishRefundSucceededEvent(OrderEntity order, OrderReturnRequestEntity ret, Instant now) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("orderId", order.getId().toString());
            data.put("returnId", ret.getId().toString());
            data.put("buyerProfileId", order.getBuyerProfileId());
            data.put("productId", ret.getProductId().toString());
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("eventId", UUID.randomUUID().toString());
            envelope.put("eventType", DomainEventContracts.ORDER_REFUND_SUCCEEDED);
            envelope.put("occurredAt", now.toString());
            envelope.put("producer", "commerce-service");
            envelope.put("data", data);
            outboxEventRepository.save(CommerceOutboxEventEntity.builder()
                    .id(UUID.randomUUID())
                    .aggregateType("Order")
                    .aggregateId(order.getId())
                    .eventType(DomainEventContracts.ORDER_REFUND_SUCCEEDED)
                    .routingKey(DomainEventContracts.ORDER_REFUND_SUCCEEDED)
                    .payloadJson(objectMapper.writeValueAsString(envelope))
                    .status("PENDING")
                    .createdAt(now)
                    .build());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize order.refund.succeeded.v1 event", e);
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderReturnResponse> merchantReturnsPage(
            ActorContext actor, String status, int page, int size) {
        PageResponse.validate(page, size);
        if (actor.mode() != AccessMode.ORGANIZATION) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        MerchantEntity merchant = merchantRepository.findByOrganizationId(actor.organizationId()).orElse(null);
        accessPolicy.assertCanReadMerchantReturns(actor, merchant);
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        String normalizedStatus = status != null && !status.isBlank() ? status.trim().toUpperCase() : null;
        var result = normalizedStatus != null
                ? returnRequestRepository.findByOrganizationIdAndStatus(actor.organizationId(), normalizedStatus, pageable)
                : returnRequestRepository.findByOrganizationId(actor.organizationId(), pageable);
        return PageResponse.of(result.map(this::toOrderReturnResponse).getContent(), page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderReturnResponse> listOrderReturnsPage(
            ActorContext actor, UUID orderId, int page, int size) {
        PageResponse.validate(page, size);
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        MerchantEntity merchant = merchantRepository.findById(order.getMerchantId()).orElse(null);
        if (!accessPolicy.canListOrderReturns(actor, order, merchant)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        var result = returnRequestRepository.findByOrderId(orderId, pageable);
        return PageResponse.of(result.map(this::toOrderReturnResponse).getContent(), page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public List<OrderReturnResponse> listOrderReturns(ActorContext actor, UUID orderId) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        MerchantEntity merchant = merchantRepository.findById(order.getMerchantId()).orElse(null);
        if (!accessPolicy.canListOrderReturns(actor, order, merchant)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        return returnRequestRepository.findByOrderIdOrderByCreatedAtDesc(orderId)
                .stream()
                .map(this::toOrderReturnResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OrderReturnResponse> listOrderReturns(String callerProfileId, boolean isPrivileged, UUID orderId) {
        return listOrderReturns(callerProfileId, isPrivileged, isPrivileged, null, orderId);
    }

    @Transactional(readOnly = true)
    public List<OrderReturnResponse> listOrderReturns(
            String callerProfileId,
            boolean isPrivileged,
            boolean isGlobalAdmin,
            UUID orgIdHeader,
            UUID orderId) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        if (isGlobalAdmin) {
            // global admin allowed
        } else if (orgIdHeader != null && isPrivileged) {
            verifyActiveMerchantScope(callerProfileId, order, orgIdHeader, false);
        } else if (!order.getBuyerProfileId().equals(callerProfileId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
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

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", order.getId());
        data.put("orderLineId", line.getId());
        data.put("buyerProfileId", order.getBuyerProfileId());
        data.put("buyerName", order.getBuyerName());
        data.put("organizationId", order.getMerchantId());
        data.put("merchantName", merchantName);
        data.put("productId", line.getProductId());
        data.put("productName", line.getProductName());
        data.put("category", category);
        data.put("brand", brand);
        data.put("productType", productType);
        data.put("warrantyMonths", warrantyMonths);
        data.put("returnWindowDays", returnWindowDays);

        saveOutbox(eventId, "CustomerOrder", order.getId(), eventType, data, now);
    }

    private void publishReturnApprovedEvent(OrderEntity order, OrderReturnRequestEntity ret, Instant now) {
        UUID eventId = UUID.randomUUID();
        String eventType = "order.return.approved.v1";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("returnId", ret.getId());
        data.put("orderId", order.getId());
        data.put("orderLineId", ret.getOrderLineId());
        data.put("buyerProfileId", ret.getBuyerProfileId());
        data.put("organizationId", ret.getOrganizationId());
        data.put("productName", ret.getProductName());
        data.put("returnType", ret.getReturnType());
        data.put("quantity", ret.getQuantity());
        saveOutbox(eventId, "CustomerOrder", order.getId(), eventType, data, now);
    }

    private void publishDomainEvent(String eventType, OrderEntity order) {
        Instant now = Instant.now();
        UUID eventId = UUID.randomUUID();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", order.getId());
        data.put("buyerProfileId", order.getBuyerProfileId());
        data.put("totalAmount", order.getTotalAmount());
        data.put("status", order.getStatus());
        data.put("paymentStatus", order.getPaymentStatus());

        saveOutbox(eventId, "CustomerOrder", order.getId(), eventType, data, now);
    }

    private void saveOutbox(
            UUID eventId,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            Map<String, Object> data,
            Instant now) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(DomainEventContracts.FIELD_EVENT_ID, eventId);
        envelope.put(DomainEventContracts.FIELD_EVENT_TYPE, eventType);
        envelope.put(DomainEventContracts.FIELD_OCCURRED_AT, now.toString());
        envelope.put(DomainEventContracts.FIELD_PRODUCER, "commerce-service");
        envelope.put("correlationId", aggregateId);
        envelope.put(DomainEventContracts.FIELD_DATA, data);

        String payload;
        try {
            payload = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize commerce outbox payload", e);
        }

        outboxEventRepository.save(CommerceOutboxEventEntity.builder()
                .id(eventId)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .routingKey(eventType)
                .payloadJson(payload)
                .status(EntityStatus.PENDING.name())
                .retryCount(0)
                .nextAttemptAt(now)
                .createdAt(now)
                .build());
    }

    private ProductResponse toProductResponse(ProductEntity p) {
        String merchantName = merchantRepository.findById(p.getMerchantId())
                .map(MerchantEntity::getName)
                .orElse("HomeCare Mall");
        InventoryEntity inv = inventoryRepository.findByProductId(p.getId()).orElse(null);
        return toProductResponse(p, merchantName, inv);
    }

    private ProductResponse toProductResponse(ProductEntity p, String merchantName, InventoryEntity inv) {
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
        return toOrderResponse(
                o,
                orderLineRepository.findByOrderId(o.getId()),
                paymentAttemptRepository.findByOrderIdOrderByCreatedAtDesc(o.getId()));
    }

    private OrderResponse toOrderResponse(
            OrderEntity o,
            List<OrderLineEntity> orderLines,
            List<PaymentAttemptEntity> paymentAttempts) {
        List<OrderLineResponse> lines = orderLines
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
        List<PaymentAttemptResponse> attempts = paymentAttempts
                .stream()
                .map(this::toPaymentAttemptResponse)
                .toList();
        return new OrderResponse(
                o.getId(),
                o.getBuyerProfileId(),
                o.getBuyerName(),
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
                r.getBuyerName(),
                r.getOrganizationId(),
                r.getReturnType(),
                r.getQuantity(),
                r.getReason(),
                r.getStatus(),
                r.getResolutionNote(),
                r.getCreatedAt(),
                r.getResolvedAt(),
                r.getVersion());
    }
}
