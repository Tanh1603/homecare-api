package com.homecare.household.service;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.homecare.common.event.DomainEventContracts;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class HouseholdEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(HouseholdEventConsumer.class);
    public static final String QUEUE = "household.events.q";
    public static final String EXCHANGE = DomainEventContracts.EXCHANGE;

    private final HouseholdDomainService domainService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = QUEUE)
    @SuppressWarnings("unchecked")
    public void onDomainEvent(String payloadJson) {
        try {
            Map<String, Object> root = objectMapper.readValue(payloadJson, Map.class);
            String eventType = String.valueOf(root.getOrDefault(DomainEventContracts.FIELD_EVENT_TYPE, ""));
            Map<String, Object> data = root.get(DomainEventContracts.FIELD_DATA) instanceof Map<?, ?> m
                    ? (Map<String, Object>) m
                    : Map.of();

            switch (eventType) {
                case DomainEventContracts.ORDER_LINE_DELIVERED -> {
                    int warrantyMonths = intField(data, "warrantyMonths");
                    if (warrantyMonths > 0) {
                        domainService.autoActivateWarrantyFromOrderLineDelivered(
                                stringField(data, "buyerProfileId"),
                                blankToNull(stringField(data, "buyerName")),
                                uuidField(data, "organizationId"),
                                stringField(data, "merchantName"),
                                uuidField(data, "orderId"),
                                uuidField(data, "orderLineId"),
                                uuidField(data, "productId"),
                                stringField(data, "productName"),
                                stringField(data, "category"),
                                stringField(data, "brand"),
                                stringField(data, "productType"),
                                warrantyMonths);
                    }
                }
                case DomainEventContracts.BOOKING_COMPLETED -> {
                    UUID deviceId = uuidField(data, "deviceId");
                    if (deviceId != null) {
                        domainService.recordCareHistoryFromBookingCompleted(
                                uuidField(data, "bookingId"),
                                deviceId,
                                stringField(data, "requesterProfileId"),
                                stringField(data, "bookingType"),
                                stringField(data, "serviceName"),
                                "Hoàn tất dịch vụ tự động ghi nhận từ Booking #" + stringField(data, "bookingId"));
                    }
                }
                default -> log.debug("HouseholdEventConsumer ignored event {}", eventType);
            }
        } catch (Exception e) {
            log.warn("HouseholdEventConsumer failed to process event: {}", e.getMessage());
            throw new IllegalStateException("Failed to process household event", e);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String stringField(Map<String, Object> data, String key) {
        Object val = data.get(key);
        return val != null ? String.valueOf(val) : "";
    }

    private int intField(Map<String, Object> data, String key) {
        Object val = data.get(key);
        if (val instanceof Number n) {
            return n.intValue();
        }
        try {
            return val != null ? Integer.parseInt(String.valueOf(val)) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private UUID uuidField(Map<String, Object> data, String key) {
        Object val = data.get(key);
        if (val == null) {
            return null;
        }
        String s = String.valueOf(val).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (Exception e) {
            return null;
        }
    }
}
