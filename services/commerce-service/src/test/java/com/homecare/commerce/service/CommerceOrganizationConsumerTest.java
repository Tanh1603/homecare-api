package com.homecare.commerce.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.homecare.commerce.entity.MerchantEntity;
import com.homecare.commerce.repository.MerchantRepository;

@SpringBootTest
@ActiveProfiles("test")
class CommerceOrganizationConsumerTest {

    @Autowired
    private CommerceOrganizationConsumer consumer;

    @Autowired
    private MerchantRepository merchantRepository;

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
}
