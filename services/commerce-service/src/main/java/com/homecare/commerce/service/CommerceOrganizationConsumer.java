package com.homecare.commerce.service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.commerce.entity.CommerceProcessedEventEntity;
import com.homecare.commerce.entity.MerchantEntity;
import com.homecare.commerce.repository.CommerceProcessedEventRepository;
import com.homecare.commerce.repository.MerchantRepository;

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

    @Bean
    public Queue commerceOrganizationQueue() {
        return new Queue(QUEUE, true);
    }

    @Bean
    public Binding bindCommerceOrgApproved(Queue commerceOrganizationQueue, TopicExchange domainEventsExchange) {
        return BindingBuilder.bind(commerceOrganizationQueue)
                .to(domainEventsExchange)
                .with("organization.approved.v1");
    }

    @Bean
    public Binding bindCommerceOrgSuspended(Queue commerceOrganizationQueue, TopicExchange domainEventsExchange) {
        return BindingBuilder.bind(commerceOrganizationQueue)
                .to(domainEventsExchange)
                .with("organization.suspended.v1");
    }

    @RabbitListener(queues = QUEUE)
    @Transactional
    @SuppressWarnings("unchecked")
    public void onOrganizationEvent(String payloadJson) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(payloadJson, Map.class);
            String eventIdStr = str(envelope.get("eventId"));
            String eventType = str(envelope.get("eventType"));
            if (eventIdStr == null || eventType == null) {
                return;
            }
            UUID eventId = UUID.fromString(eventIdStr);
            if (processedEventRepository.existsById(eventId)) {
                log.debug("Skipping already processed organization event {} ({})", eventId, eventType);
                return;
            }

            Object dataObj = envelope.get("data");
            if (!(dataObj instanceof Map<?, ?> data)) {
                return;
            }
            String orgIdStr = str(data.get("organizationId"));
            if (orgIdStr == null) {
                return;
            }
            UUID orgId = UUID.fromString(orgIdStr);

            if ("organization.approved.v1".equals(eventType)) {
                handleApproved(orgId, data);
            } else if ("organization.suspended.v1".equals(eventType)) {
                handleSuspended(orgId);
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
        if (merchantRepository.existsByOrganizationId(orgId)) {
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
                .status("INACTIVE")
                .createdAt(Instant.now())
                .build());
        log.info("Initialized INACTIVE MerchantEntity for approved organizationId={} type={}", orgId, type);
    }

    private void handleSuspended(UUID orgId) {
        merchantRepository.findByOrganizationId(orgId).ifPresent(merchant -> {
            merchant.setStatus("INACTIVE");
            merchantRepository.save(merchant);
            log.info("Set MerchantEntity status=INACTIVE for suspended organizationId={}", orgId);
        });
    }

    private static String str(Object obj) {
        return obj == null ? null : String.valueOf(obj);
    }
}
