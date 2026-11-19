package com.homecare.commerce.service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.commerce.entity.CommerceProcessedEventEntity;
import com.homecare.commerce.entity.MerchantEntity;
import com.homecare.commerce.repository.CommerceProcessedEventRepository;
import com.homecare.commerce.repository.MerchantRepository;
import com.homecare.common.domain.EntityStatus;
import com.homecare.common.event.DomainEventContracts;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class CommerceOrganizationConsumer {

    private static final Logger log = LoggerFactory.getLogger(CommerceOrganizationConsumer.class);
    public static final String QUEUE = "commerce.organization.events.q";

    private final MerchantRepository merchantRepository;
    private final CommerceProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = QUEUE)
    @Transactional
    @SuppressWarnings("unchecked")
    public void onOrganizationEvent(String payloadJson) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payloadJson, Map.class);
            String eventIdStr = str(envelope.get(DomainEventContracts.FIELD_EVENT_ID));
            String eventType = str(envelope.get(DomainEventContracts.FIELD_EVENT_TYPE));
            if (eventIdStr == null || eventType == null) {
                return;
            }
            UUID eventId = UUID.fromString(eventIdStr);
            if (processedEventRepository.existsById(eventId)) {
                log.debug("Skipping already processed organization event {} ({})", eventId, eventType);
                return;
            }

            Object dataObj = envelope.get(DomainEventContracts.FIELD_DATA);
            if (!(dataObj instanceof Map<?, ?> data)) {
                return;
            }
            String orgIdStr = str(data.get("organizationId"));
            if (orgIdStr == null) {
                return;
            }
            UUID orgId = UUID.fromString(orgIdStr);

            switch (eventType) {
                case DomainEventContracts.ORG_APPROVED -> handleApproved(orgId, data);
                case DomainEventContracts.ORG_UPDATED -> handleUpdated(orgId, data);
                case DomainEventContracts.ORG_SUSPENDED -> handleSuspended(orgId);
                default -> {
                }
            }

            processedEventRepository.save(CommerceProcessedEventEntity.builder()
                    .eventId(eventId)
                    .eventType(eventType)
                    .processedAt(Instant.now())
                    .build());
        } catch (Exception ex) {
            log.error("Failed processing commerce organization event: {}", ex.getMessage(), ex);
            throw new IllegalStateException("Failed processing commerce organization event", ex);
        }
    }

    private void handleApproved(UUID orgId, Map<?, ?> data) {
        String type = str(data.get("type"));
        if (!"COMMERCE".equalsIgnoreCase(type) && !"BOTH".equalsIgnoreCase(type)) {
            return;
        }
        var existing = merchantRepository.findByOrganizationId(orgId);
        if (existing.isPresent()) {
            // Re-approval after suspension reactivates the store.
            existing.get().setStatus(EntityStatus.ACTIVE.name());
            merchantRepository.save(existing.get());
            return;
        }
        String name = str(data.get("name"));
        String city = str(data.get("city"));
        String ownerUserId = str(data.get("ownerUserId"));

        merchantRepository.save(MerchantEntity.builder()
                .id(orgId)
                .organizationId(orgId)
                .ownerProfileId(ownerUserId != null ? ownerUserId : "organization-owner")
                .name(name != null ? name : "HomeCare Merchant")
                .city(city != null ? city : "TP. Hồ Chí Minh")
                .ratingAvg(0.0)
                .status(EntityStatus.ACTIVE.name())
                .createdAt(Instant.now())
                .build());
        log.info("Initialized ACTIVE MerchantEntity for approved organizationId={} type={}", orgId, type);
    }

    private void handleUpdated(UUID orgId, Map<?, ?> data) {
        String type = str(data.get("type"));
        if ("SERVICE".equalsIgnoreCase(type)) {
            return;
        }
        merchantRepository.findByOrganizationId(orgId).ifPresent(merchant -> {
            String name = str(data.get("name"));
            String city = str(data.get("city"));
            if (name != null && !name.isBlank()) merchant.setName(name);
            if (city != null && !city.isBlank()) merchant.setCity(city);
            merchantRepository.save(merchant);
            log.info("Updated MerchantEntity projection for organizationId={}", orgId);
        });
    }

    private void handleSuspended(UUID orgId) {
        merchantRepository.findByOrganizationId(orgId).ifPresent(merchant -> {
            merchant.setStatus(EntityStatus.INACTIVE.name());
            merchantRepository.save(merchant);
            log.info("Set MerchantEntity status=INACTIVE for suspended organizationId={}", orgId);
        });
    }

    private static String str(Object obj) {
        return obj == null ? null : String.valueOf(obj);
    }
}
