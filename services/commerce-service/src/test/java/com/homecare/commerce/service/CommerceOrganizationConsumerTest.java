package com.homecare.commerce.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import com.homecare.commerce.entity.MerchantEntity;
import com.homecare.commerce.entity.OrderEntity;
import com.homecare.commerce.repository.MerchantRepository;
import com.homecare.commerce.repository.OrderRepository;
import com.homecare.common.error.DomainException;

@SpringBootTest
@ActiveProfiles("test")
class CommerceOrganizationConsumerTest {

    @Autowired
    private CommerceOrganizationConsumer consumer;

    @Autowired
    private MerchantRepository merchantRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private CommerceDomainService commerceDomainService;

    @Test
    void createsMerchantIdempotentlyOnApproveAndDeactivatesOnSuspend() {
        UUID orgId = UUID.randomUUID();
        UUID approveEventId = UUID.randomUUID();
        String approvedJson = """
                {"eventId":"%s","eventType":"organization.approved.v1","data":{"organizationId":"%s","name":"Cửa Hàng Linh Kiện A","type":"BOTH","ownerUserId":"user-owner-1","city":"Hà Nội","phone":"0901112223"}}
                """.formatted(approveEventId, orgId).strip();

        // First call creates INACTIVE MerchantEntity
        consumer.onOrganizationEvent(approvedJson);
        MerchantEntity created = merchantRepository.findByOrganizationId(orgId).orElseThrow();
        assertThat(created.getStatus()).isEqualTo("INACTIVE");
        assertThat(created.getName()).isEqualTo("Cửa Hàng Linh Kiện A");

        // Duplicate event is ignored idempotently
        created.setStatus("ACTIVE");
        merchantRepository.save(created);
        consumer.onOrganizationEvent(approvedJson);
        MerchantEntity afterDup = merchantRepository.findByOrganizationId(orgId).orElseThrow();
        assertThat(afterDup.getStatus()).isEqualTo("ACTIVE");

        // Suspend event sets status back to INACTIVE without deleting
        UUID suspendEventId = UUID.randomUUID();
        String suspendedJson = """
                {"eventId":"%s","eventType":"organization.suspended.v1","data":{"organizationId":"%s"}}
                """.formatted(suspendEventId, orgId).strip();
        consumer.onOrganizationEvent(suspendedJson);
        MerchantEntity suspended = merchantRepository.findByOrganizationId(orgId).orElseThrow();
        assertThat(suspended.getStatus()).isEqualTo("INACTIVE");
    }

    @Test
    void ignoresServiceOnlyOrganizationOnApprove() {
        UUID serviceOrgId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        String json = """
                {"eventId":"%s","eventType":"organization.approved.v1","data":{"organizationId":"%s","name":"Service Only Org","type":"SERVICE","ownerUserId":"user-2"}}
                """.formatted(eventId, serviceOrgId).strip();

        consumer.onOrganizationEvent(json);
        assertThat(merchantRepository.existsByOrganizationId(serviceOrgId)).isFalse();
    }

    @Test
    void fulfillOrderLineRejectsInactiveMerchantAndMismatchedOrgHeader() {
        UUID orgId = UUID.randomUUID();
        MerchantEntity merchant = merchantRepository.save(MerchantEntity.builder()
                .id(orgId)
                .organizationId(orgId)
                .ownerProfileId("owner-1")
                .name("Suspended Shop")
                .city("HCM")
                .ratingAvg(0.0)
                .status("INACTIVE")
                .createdAt(Instant.now())
                .build());
        OrderEntity order = orderRepository.save(OrderEntity.builder()
                .id(UUID.randomUUID())
                .buyerProfileId("buyer-1")
                .merchantId(merchant.getId())
                .status("CONFIRMED")
                .paymentStatus("PAID")
                .totalAmount(100000L)
                .shippingAddress("HCM")
                .quoteExpiresAt(Instant.now().plusSeconds(3600))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        assertThatThrownBy(() -> commerceDomainService.fulfillOrderLine("actor-1", order.getId(), UUID.randomUUID(), orgId))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).status())
                .isEqualTo(HttpStatus.FORBIDDEN);

        merchant.setStatus("ACTIVE");
        merchantRepository.save(merchant);

        UUID otherOrgId = UUID.randomUUID();
        assertThatThrownBy(() -> commerceDomainService.fulfillOrderLine("actor-1", order.getId(), UUID.randomUUID(), otherOrgId))
                .isInstanceOf(DomainException.class)
                .extracting(ex -> ((DomainException) ex).status())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
