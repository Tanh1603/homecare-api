package com.homecare.commerce.service;

import com.homecare.common.error.DomainException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homecare.commerce.client.ProviderOrgClient;
import com.homecare.commerce.dto.CommerceDtos.CreateOrderRequest;
import com.homecare.commerce.dto.CommerceDtos.CreateOrderReturnRequest;
import com.homecare.commerce.dto.CommerceDtos.OrderLineRequest;
import com.homecare.commerce.dto.CommerceDtos.OrderResponse;
import com.homecare.commerce.dto.CommerceDtos.OrderReturnResponse;
import com.homecare.commerce.dto.CommerceDtos.PaymentAttemptResponse;
import com.homecare.commerce.dto.CommerceDtos.ProductResponse;
import com.homecare.commerce.dto.CommerceDtos.ResolveOrderReturnRequest;
import com.homecare.commerce.entity.InventoryReservationEntity;
import com.homecare.commerce.repository.CommerceOutboxEventRepository;
import com.homecare.commerce.repository.InventoryReservationRepository;
import com.homecare.commerce.repository.OrderReturnRequestRepository;
import com.homecare.common.domain.EntityStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class CommerceAndVnpayTest {
    @MockitoBean
    private ProviderOrgClient providerOrgClient;

    @Autowired
    private CommerceDomainService service;

    @Autowired
    private VnpaySandboxStrategy vnpayStrategy;

    @Autowired
    private InventoryReservationRepository reservationRepository;

    @Autowired
    private CommerceOutboxEventRepository outboxEventRepository;

    @Autowired
    private OrderReturnRequestRepository returnRequestRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void enforcesSingleMerchantReservesStockVerifiesVnpayIpnAndFulfillsOrderLines() {
        assertThat(service.productsPage(null, null, null, null, null, null, null, 1, 20).pagination().totalItems())
                .isGreaterThanOrEqualTo(13);

        assertThatThrownBy(() -> service.createOrder(
                "buyer-1",
                new CreateOrderRequest(
                        List.of(
                                new OrderLineRequest(CommerceDataInitializer.PRODUCT_AC_FILTER_ID, 1),
                                new OrderLineRequest(CommerceDataInitializer.PRODUCT_RO_CORE_ID, 1)),
                        "100 Nguyễn Thị Minh Khai, Q3")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("MIXED_MERCHANT_ORDER");

        OrderResponse order = service.createOrder(
                "buyer-1",
                new CreateOrderRequest(
                        List.of(new OrderLineRequest(CommerceDataInitializer.PRODUCT_AC_FILTER_ID, 2)),
                        "100 Nguyễn Thị Minh Khai, Q3"));

        assertThat(order.totalAmount()).isEqualTo(370_000L);
        assertThat(order.status()).isEqualTo("AWAITING_PAYMENT");
        assertThat(order.merchantId()).isEqualTo(CommerceDataInitializer.MERCHANT_1_ID);

        List<InventoryReservationEntity> activeRes = reservationRepository.findByOrderId(order.id());
        assertThat(activeRes).hasSize(1);
        assertThat(activeRes.getFirst().getStatus()).isEqualTo(EntityStatus.ACTIVE.name());

        PaymentAttemptResponse attempt = service.createPaymentAttempt(
                "buyer-1", order.id(), "homecare://payment-result");
        assertThat(attempt.paymentUrl()).contains("vnp_SecureHash=");
        assertThat(attempt.paymentUrl()).contains("vnp_ReturnUrl=homecare%3A%2F%2Fpayment-result");

        Map<String, String> ipnParams = new HashMap<>();
        ipnParams.put("vnp_TxnRef", attempt.txnRef());
        ipnParams.put("vnp_Amount", String.valueOf(attempt.amount() * 100L));
        ipnParams.put("vnp_ResponseCode", "00");
        ipnParams.put("vnp_TmnCode", "HOMECARE01");
        ipnParams.put("vnp_SecureHash", vnpayStrategy.signParamsForTest(ipnParams));

        Map<String, String> ipnResult = service.handleVnpayIpn(ipnParams);
        assertThat(ipnResult.get("RspCode")).isEqualTo("00");

        OrderResponse paidOrder = service.getOrder("buyer-1", order.id());
        assertThat(paidOrder.paymentStatus()).isEqualTo("PAID");
        assertThat(paidOrder.status()).isEqualTo("CONFIRMED");

        List<InventoryReservationEntity> confirmedRes = reservationRepository.findByOrderId(order.id());
        assertThat(confirmedRes.getFirst().getStatus()).isEqualTo("CONFIRMED");
        assertThat(outboxEventRepository.findAll()).isNotEmpty();

        OrderResponse delivered = service.fulfillOrderLine(
                "admin-1", order.id(), paidOrder.lines().getFirst().id());
        assertThat(delivered.status()).isEqualTo("DELIVERED");
        assertThat(service.verifyOrderLineReviewEligibility(
                paidOrder.lines().getFirst().id(), "buyer-1").eligible()).isTrue();
    }

    @Test
    void supportsOrderReturnAndRefundForSmallPartsAndToolsWithinReturnWindow() {
        ProductResponse beforeOrder = service.getProduct(CommerceDataInitializer.PRODUCT_WRENCH_SCREW_KIT_ID);
        assertThat(beforeOrder.productType()).isEqualTo("TOOL");
        assertThat(beforeOrder.warrantyMonths()).isZero();
        assertThat(beforeOrder.returnWindowDays()).isEqualTo(15);
        int initialStock = beforeOrder.availableQty();

        OrderResponse order = service.createOrder(
                "buyer-return-1",
                new CreateOrderRequest(
                        List.of(new OrderLineRequest(CommerceDataInitializer.PRODUCT_WRENCH_SCREW_KIT_ID, 2)),
                        "88 Điện Biên Phủ, Bình Thạnh"));
        var lineId = order.lines().getFirst().id();

        assertThatThrownBy(() -> service.createOrderReturnRequest(
                "buyer-return-1",
                order.id(),
                lineId,
                new CreateOrderReturnRequest("REFUND", 1, "Chưa giao hàng nhưng bấm trả")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("ORDER_LINE_NOT_DELIVERED");

        assertThatThrownBy(() -> service.fulfillOrderLine("demo-organization", order.id(), lineId))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("ORDER_INVALID_STATE");

        PaymentAttemptResponse attempt = service.createPaymentAttempt("buyer-return-1", order.id());
        Map<String, String> ipnParams = new HashMap<>();
        ipnParams.put("vnp_TxnRef", attempt.txnRef());
        ipnParams.put("vnp_Amount", String.valueOf(attempt.amount() * 100L));
        ipnParams.put("vnp_ResponseCode", "00");
        ipnParams.put("vnp_TmnCode", "HOMECARE01");
        ipnParams.put("vnp_SecureHash", vnpayStrategy.signParamsForTest(ipnParams));
        assertThat(service.handleVnpayIpn(ipnParams).get("RspCode")).isEqualTo("00");

        service.fulfillOrderLine("demo-organization", order.id(), lineId);
        assertThatThrownBy(() -> service.fulfillOrderLine("demo-organization", order.id(), lineId))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("ORDER_INVALID_STATE");

        assertThatThrownBy(() -> service.createOrderReturnRequest(
                "buyer-return-1",
                order.id(),
                lineId,
                new CreateOrderReturnRequest("REFUND", 5, "Trả vượt số lượng đã mua")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("RETURN_QUANTITY_EXCEEDED");

        OrderReturnResponse createdReturn = service.createOrderReturnRequest(
                "buyer-return-1",
                order.id(),
                lineId,
                new CreateOrderReturnRequest("REFUND", 2, "Ốc vít không vừa ren giá đỡ dàn nóng"));
        assertThat(createdReturn.status()).isEqualTo("REQUESTED");
        assertThat(createdReturn.organizationId()).isEqualTo(CommerceDataInitializer.MERCHANT_1_ID);

        assertThatThrownBy(() -> service.createOrderReturnRequest(
                "buyer-return-1",
                order.id(),
                lineId,
                new CreateOrderReturnRequest("REFUND", 1, "Duplicate return exceeding line qty")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("RETURN_QUANTITY_EXCEEDED");

        // Simulate legacy pre-migration row with version = NULL, then backfill and resolve
        jdbcTemplate.execute("ALTER TABLE order_return_requests ALTER COLUMN version SET NULL");
        jdbcTemplate.update("UPDATE order_return_requests SET version = NULL WHERE id = ?", createdReturn.id());
        assertThat(returnRequestRepository.backfillNullVersions()).isEqualTo(1);

        OrderReturnResponse approved = service.resolveOrderReturnRequest(
                "demo-organization",
                order.id(),
                createdReturn.id(),
                new ResolveOrderReturnRequest("APPROVE", "Cửa hàng đồng ý hoàn tiền 100% và nhận lại bộ ốc vít"));
        assertThat(approved.status()).isEqualTo("APPROVED_PENDING_MANUAL_REFUND");

        ProductResponse afterRefund = service.getProduct(CommerceDataInitializer.PRODUCT_WRENCH_SCREW_KIT_ID);
        assertThat(afterRefund.availableQty()).isEqualTo(initialStock);
        assertThatThrownBy(() -> service.resolveOrderReturnRequest(
                "demo-organization",
                order.id(),
                createdReturn.id(),
                new ResolveOrderReturnRequest("APPROVE", "Retry must not restock twice")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("RETURN_ALREADY_RESOLVED");
        assertThat(service.getProduct(CommerceDataInitializer.PRODUCT_WRENCH_SCREW_KIT_ID).availableQty())
                .isEqualTo(initialStock);

        OrderResponse updatedOrder = service.getOrder("buyer-return-1", order.id());
        assertThat(updatedOrder.lines().getFirst().fulfillmentStatus()).isEqualTo("RETURNED");
        assertThatThrownBy(() -> service.fulfillOrderLine("demo-organization", order.id(), lineId))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("ORDER_INVALID_STATE");
    }

    @Test
    void coalescesDuplicateCartLinesInSortedProductOrderAndGeneratesDistinctTxnRefs() {
        OrderResponse order = service.createOrder(
                "buyer-coalesce-1",
                new CreateOrderRequest(
                        List.of(
                                new OrderLineRequest(CommerceDataInitializer.PRODUCT_WRENCH_SCREW_KIT_ID, 1),
                                new OrderLineRequest(CommerceDataInitializer.PRODUCT_AC_FILTER_ID, 2),
                                new OrderLineRequest(CommerceDataInitializer.PRODUCT_WRENCH_SCREW_KIT_ID, 3)),
                        "99 Pasteur, Q1"));
        assertThat(order.lines()).hasSize(2);
        assertThat(order.lines().get(0).productId()).isEqualTo(CommerceDataInitializer.PRODUCT_AC_FILTER_ID);
        assertThat(order.lines().get(0).quantity()).isEqualTo(2);
        assertThat(order.lines().get(1).productId()).isEqualTo(CommerceDataInitializer.PRODUCT_WRENCH_SCREW_KIT_ID);
        assertThat(order.lines().get(1).quantity()).isEqualTo(4);

        PaymentAttemptResponse attempt1 = service.createPaymentAttempt("buyer-coalesce-1", order.id());
        PaymentAttemptResponse attempt2 = service.createPaymentAttempt("buyer-coalesce-1", order.id());
        assertThat(attempt1.txnRef()).isNotEqualTo(attempt2.txnRef());
    }

    @Test
    void listOrderReturnsEnforcesOwnershipCheck() {
        OrderResponse order = service.createOrder(
                "buyer-owner-xyz",
                new CreateOrderRequest(
                        List.of(new OrderLineRequest(CommerceDataInitializer.PRODUCT_WRENCH_SCREW_KIT_ID, 1)),
                        "123 Nguyễn Huệ, Q1"));

        assertThatThrownBy(() -> service.listOrderReturns("stranger-user", false, order.id()))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("RESOURCE_FORBIDDEN");

        assertThat(service.listOrderReturns("buyer-owner-xyz", false, order.id())).isEmpty();
        assertThat(service.listOrderReturns("admin-user", true, order.id())).isEmpty();
    }

    @Test
    void rejectsInvalidReturnUrlIpnTamperingAndCrossTenantMerchantSpoofing() {
        OrderResponse order = service.createOrder(
                "buyer-sec-1",
                new CreateOrderRequest(
                        List.of(new OrderLineRequest(CommerceDataInitializer.PRODUCT_AC_FILTER_ID, 1)),
                        "12 Le Loi, Q1"));

        assertThatThrownBy(() -> service.createPaymentAttempt(
                "buyer-sec-1", order.id(), "https://evil.example.com/steal"))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("VALIDATION_FAILED");

        PaymentAttemptResponse attempt = service.createPaymentAttempt("buyer-sec-1", order.id());

        Map<String, String> badTmn = new HashMap<>();
        badTmn.put("vnp_TxnRef", attempt.txnRef());
        badTmn.put("vnp_Amount", String.valueOf(attempt.amount() * 100L));
        badTmn.put("vnp_ResponseCode", "00");
        badTmn.put("vnp_TmnCode", "WRONG_TMN");
        badTmn.put("vnp_SecureHash", vnpayStrategy.signParamsForTest(badTmn));
        assertThat(service.handleVnpayIpn(badTmn).get("RspCode")).isEqualTo("97");

        Map<String, String> badAmount = new HashMap<>();
        badAmount.put("vnp_TxnRef", attempt.txnRef());
        badAmount.put("vnp_Amount", "100");
        badAmount.put("vnp_ResponseCode", "00");
        badAmount.put("vnp_TmnCode", "HOMECARE01");
        badAmount.put("vnp_SecureHash", vnpayStrategy.signParamsForTest(badAmount));
        assertThat(service.handleVnpayIpn(badAmount).get("RspCode")).isEqualTo("04");
        assertThat(service.getOrder("buyer-sec-1", order.id()).status()).isEqualTo("AWAITING_PAYMENT");

        // Non-00 IPN fails only the attempt and keeps the order AWAITING_PAYMENT for retry
        Map<String, String> failedIpn = new HashMap<>();
        failedIpn.put("vnp_TxnRef", attempt.txnRef());
        failedIpn.put("vnp_Amount", String.valueOf(attempt.amount() * 100L));
        failedIpn.put("vnp_ResponseCode", "24");
        failedIpn.put("vnp_TmnCode", "HOMECARE01");
        failedIpn.put("vnp_SecureHash", vnpayStrategy.signParamsForTest(failedIpn));
        assertThat(service.handleVnpayIpn(failedIpn).get("RspCode")).isEqualTo("00");
        assertThat(service.getOrder("buyer-sec-1", order.id()).status()).isEqualTo("AWAITING_PAYMENT");

        PaymentAttemptResponse retryAttempt = service.createPaymentAttempt("buyer-sec-1", order.id());
        Map<String, String> retrySuccessIpn = new HashMap<>();
        retrySuccessIpn.put("vnp_TxnRef", retryAttempt.txnRef());
        retrySuccessIpn.put("vnp_Amount", String.valueOf(retryAttempt.amount() * 100L));
        retrySuccessIpn.put("vnp_ResponseCode", "00");
        retrySuccessIpn.put("vnp_TmnCode", "HOMECARE01");
        retrySuccessIpn.put("vnp_SecureHash", vnpayStrategy.signParamsForTest(retrySuccessIpn));
        assertThat(service.handleVnpayIpn(retrySuccessIpn).get("RspCode")).isEqualTo("00");
        assertThat(service.getOrder("buyer-sec-1", order.id()).status()).isEqualTo("CONFIRMED");

        assertThatThrownBy(() -> service.ordersPage(
                "stranger-org-user", CommerceDataInitializer.MERCHANT_1_ID, false, true, null, null, null, 1, 20))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("RESOURCE_FORBIDDEN");

        assertThatThrownBy(() -> service.fulfillOrderLine(
                "stranger-org-user",
                order.id(),
                order.lines().getFirst().id(),
                CommerceDataInitializer.MERCHANT_1_ID,
                false))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("RESOURCE_FORBIDDEN");
    }
}
