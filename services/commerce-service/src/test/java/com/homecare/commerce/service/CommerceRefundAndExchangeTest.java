package com.homecare.commerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homecare.commerce.client.ProviderOrgClient;
import com.homecare.commerce.dto.CommerceDtos.ConfirmManualRefundRequest;
import com.homecare.commerce.dto.CommerceDtos.CreateOrderRequest;
import com.homecare.commerce.dto.CommerceDtos.CreateOrderReturnRequest;
import com.homecare.commerce.dto.CommerceDtos.OrderLineRequest;
import com.homecare.commerce.dto.CommerceDtos.OrderResponse;
import com.homecare.commerce.dto.CommerceDtos.OrderReturnResponse;
import com.homecare.commerce.dto.CommerceDtos.ResolveOrderReturnRequest;
import com.homecare.commerce.repository.CommerceOutboxEventRepository;
import com.homecare.commerce.repository.MerchantRepository;
import com.homecare.commerce.repository.RefundOperationRepository;
import com.homecare.common.error.DomainException;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.common.security.OrgRolePermissionMapper;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.web.EtagSupport;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class CommerceRefundAndExchangeTest {

    @MockitoBean
    private ProviderOrgClient providerOrgClient;

    @Autowired
    private CommerceDomainService service;

    @Autowired
    private VnpaySandboxStrategy vnpayStrategy;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private RefundOperationRepository refundOperationRepository;

    @Autowired
    private CommerceOutboxEventRepository outboxEventRepository;

    @Test
    void rejectsExchangeReturnAndSeparatesApprovalFromManualRefundConfirmation() {
        OrderResponse order = service.createOrder(
                "buyer-refund-1",
                "Người mua A",
                new CreateOrderRequest(
                        List.of(new OrderLineRequest(CommerceDataInitializer.PRODUCT_WRENCH_SCREW_KIT_ID, 1)),
                        "123 Hai Bà Trưng"));
        var attempt = service.createPaymentAttempt("buyer-refund-1", order.id(), "homecare://payment-result");

        Map<String, String> ipnParams = new HashMap<>();
        ipnParams.put("vnp_TxnRef", attempt.txnRef());
        ipnParams.put("vnp_Amount", String.valueOf(attempt.amount() * 100L));
        ipnParams.put("vnp_ResponseCode", "00");
        ipnParams.put("vnp_TmnCode", "HOMECARE01");
        ipnParams.put("vnp_TransactionNo", "14559900");
        ipnParams.put("vnp_BankCode", "NCB");
        ipnParams.put("vnp_SecureHash", vnpayStrategy.signParamsForTest(ipnParams));
        assertThat(service.handleVnpayIpn(ipnParams).get("RspCode")).isEqualTo("00");

        OrderResponse delivered = service.fulfillOrderLine("admin-1", order.id(), order.lines().getFirst().id());
        var lineId = delivered.lines().getFirst().id();

        // 1. EXCHANGE is gated off by default
        assertThatThrownBy(() -> service.createOrderReturnRequest(
                "buyer-refund-1", "Người mua A", order.id(), lineId,
                new CreateOrderReturnRequest("EXCHANGE", 1, "Đổi mẫu khác")))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).status()).isEqualTo(HttpStatus.BAD_REQUEST));

        // 2. REFUND succeeds -> REQUESTED
        OrderReturnResponse ret = service.createOrderReturnRequest(
                "buyer-refund-1", "Người mua A", order.id(), lineId,
                new CreateOrderReturnRequest("REFUND", 1, "Hỏng khi nhận"));
        assertThat(ret.status()).isEqualTo("REQUESTED");

        var merchant = merchantRepository.findById(order.merchantId()).orElseThrow();
        ActorContext orgStaff = new ActorContext(
                "staff-1",
                AccessMode.ORGANIZATION,
                merchant.getOrganizationId(),
                Set.of(RbacConstants.ROLE_ORGANIZATION),
                Set.of(),
                Set.of(RbacConstants.ORG_ROLE_STAFF),
                OrgRolePermissionMapper.permissionsFor(RbacConstants.ORG_ROLE_STAFF));
        ActorContext orgOwner = new ActorContext(
                "owner-1",
                AccessMode.ORGANIZATION,
                merchant.getOrganizationId(),
                Set.of(RbacConstants.ROLE_ORGANIZATION),
                Set.of(),
                Set.of(RbacConstants.ORG_ROLE_OWNER),
                OrgRolePermissionMapper.permissionsFor(RbacConstants.ORG_ROLE_OWNER));

        // 3. Approving return transitions to APPROVED_PENDING_MANUAL_REFUND (never REFUNDED)
        OrderReturnResponse approved = service.resolveOrderReturnRequest(
                orgOwner, order.id(), ret.id(),
                new ResolveOrderReturnRequest("APPROVE", "Chấp nhận hoàn"),
                EtagSupport.format(ret.version()));
        assertThat(approved.status()).isEqualTo("APPROVED_PENDING_MANUAL_REFUND");
        assertThat(refundOperationRepository.findByReturnRequestId(ret.id()))
                .isPresent()
                .get()
                .satisfies(op -> assertThat(op.getVnpTransactionNo()).isEqualTo("14559900"));

        // 4. ORG_STAFF cannot confirm manual refund (403 FORBIDDEN)
        assertThatThrownBy(() -> service.confirmManualRefund(
                orgStaff, order.id(), ret.id(),
                new ConfirmManualRefundRequest("BANK-REF-1", "Chuyển khoản"),
                EtagSupport.format(approved.version())))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).status()).isEqualTo(HttpStatus.FORBIDDEN));

        // 5. ORG_OWNER confirms manual refund -> REFUNDED + order.refund.succeeded.v1
        OrderReturnResponse refunded = service.confirmManualRefund(
                orgOwner, order.id(), ret.id(),
                new ConfirmManualRefundRequest("BANK-REF-1", "Đã chuyển khoản hoàn tiền"),
                EtagSupport.format(approved.version()));
        assertThat(refunded.status()).isEqualTo("REFUNDED");
        assertThat(outboxEventRepository.findAll())
                .anySatisfy(ev -> assertThat(ev.getEventType()).isEqualTo(DomainEventContracts.ORDER_REFUND_SUCCEEDED));
    }
}
