package com.homecare.household.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
    @Transactional
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
                    String deviceEligibility = blankToNull(stringField(data, "deviceEligibility"));
                    boolean eligibleForDevice = "REQUIRED".equalsIgnoreCase(deviceEligibility)
                            || "OPTIONAL".equalsIgnoreCase(deviceEligibility);
                    if (uuidField(data, "targetDeviceId") == null && (warrantyMonths > 0 || eligibleForDevice)) {
                        String ward = blankToNull(stringField(data, "shippingWardName"));
                        String province = blankToNull(stringField(data, "shippingProvinceName"));
                        String addressFallback = (ward != null && province != null)
                                ? (ward + ", " + province)
                                : (province != null ? province : ward);
                        domainService.autoActivateFromOrderLineDelivered(
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
                                blankToNull(stringField(data, "model")),
                                deviceEligibility,
                                blankToNull(stringField(data, "warrantyStartTrigger")),
                                warrantyMonths,
                                intField(data, "quantityDelivered"),
                                listStringField(data, "unitKeys"),
                                addressFallback);
                    }
                }
                case DomainEventContracts.BOOKING_COMPLETED -> {
                    // D-11.5: CareRecord & maintenance advancement trigger ONLY at ACCEPTED (booking.accepted.v1),
                    // never prematurely on booking.completed.v1.
                    log.debug("HouseholdEventConsumer ignored booking.completed.v1 for CareRecord (D-11.5)");
                }
                case DomainEventContracts.BOOKING_ACCEPTED -> {
                    UUID deviceId = uuidField(data, "deviceId");
                    if (deviceId != null) {
                        domainService.recordCareHistoryFromBookingAccepted(
                                uuidField(data, "bookingId"),
                                deviceId,
                                stringField(data, "requesterProfileId"),
                                stringField(data, "bookingType"),
                                stringField(data, "serviceName"),
                                instantField(data, "acceptedAt"),
                                "Nghiệm thu dịch vụ tự động ghi nhận từ Booking #" + stringField(data, "bookingId"));
                    }
                }
                case DomainEventContracts.INSTALLATION_COMPLETED -> domainService.recordInstallationCompleted(
                        uuidField(data, "bookingId"),
                        uuidField(data, "deviceId"),
                        uuidField(data, "orderLineId"),
                        uuidField(data, "executorOrgId"),
                        instantField(data, "completedAt"),
                        requiredEvidenceRefs(data));
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

    private List<String> listStringField(Map<String, Object> data, String key) {
        Object val = data.get(key);
        if (!(val instanceof List<?> rawList)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : rawList) {
            if (item != null) {
                String s = String.valueOf(item).trim();
                if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) {
                    result.add(s);
                }
            }
        }
        return result;
    }

    private java.time.Instant instantField(Map<String, Object> data, String key) {
        String value = blankToNull(stringField(data, key));
        if (value == null) {
            return null;
        }
        try {
            return java.time.Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> requiredEvidenceRefs(Map<String, Object> data) {
        List<String> refs = listStringField(data, "evidenceRefs");
        if (refs.isEmpty()) {
            throw new IllegalArgumentException("INSTALLATION_COMPLETED requires evidenceRefs");
        }
        return refs;
    }
}
