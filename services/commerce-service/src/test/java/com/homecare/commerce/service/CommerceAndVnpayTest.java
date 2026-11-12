package com.homecare.commerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

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
import com.homecare.common.error.DomainException;

@SpringBootTest
@ActiveProfiles("test")
class CommerceAndVnpayTest {

    @Autowired
    private CommerceDomainService service;

    @Autowired
    private VnpaySandboxStrategy vnpayStrategy;

    @Autowired
    private InventoryReservationRepository reservationRepository;

    @Autowired
    private CommerceOutboxEventRepository outboxEventRepository;

    @Test
    void enforcesSingleMerchantReservesStockVerifiesVnpayIpnAndFulfillsOrderLines() {
        assertThat(service.listProducts(null)).hasSizeGreaterThanOrEqualTo(13);

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
        assertThat(activeRes.getFirst().getStatus()).isEqualTo("ACTIVE");

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

        service.fulfillOrderLine("demo-organization", order.id(), lineId);

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

        OrderReturnResponse approved = service.resolveOrderReturnRequest(
                "demo-organization",
                order.id(),
                createdReturn.id(),
                new ResolveOrderReturnRequest("APPROVE", "Cửa hàng đồng ý hoàn tiền 100% và nhận lại bộ ốc vít"));
        assertThat(approved.status()).isEqualTo("APPROVED");

        ProductResponse afterRefund = service.getProduct(CommerceDataInitializer.PRODUCT_WRENCH_SCREW_KIT_ID);
        assertThat(afterRefund.availableQty()).isEqualTo(initialStock);

        OrderResponse updatedOrder = service.getOrder("buyer-return-1", order.id());
        assertThat(updatedOrder.lines().getFirst().fulfillmentStatus()).isEqualTo("RETURNED");
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
}
