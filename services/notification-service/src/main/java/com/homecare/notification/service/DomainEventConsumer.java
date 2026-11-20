package com.homecare.notification.service;

import tools.jackson.databind.ObjectMapper;

import com.homecare.common.event.DomainEventContracts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DomainEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(DomainEventConsumer.class);
    public static final String QUEUE = "notification.inbox.q";
    public static final String EXCHANGE = DomainEventContracts.EXCHANGE;

    private final NotificationInboxService inboxService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = QUEUE)
    @SuppressWarnings("unchecked")
    public void onDomainEvent(String payloadJson) {
        try {
            Map<String, Object> root = objectMapper.readValue(payloadJson, Map.class);
            Object rawEventId = root.get(DomainEventContracts.FIELD_EVENT_ID);
            String eventType = String.valueOf(root.getOrDefault(DomainEventContracts.FIELD_EVENT_TYPE, "domain.event"));
            String eventId = rawEventId != null && !String.valueOf(rawEventId).isBlank()
                    && !"null".equalsIgnoreCase(String.valueOf(rawEventId))
                    ? String.valueOf(rawEventId)
                    : deterministicFallbackEventId(eventType, payloadJson);
            Map<String, Object> data = root.get(DomainEventContracts.FIELD_DATA) instanceof Map<?, ?> m
                    ? (Map<String, Object>) m
                    : Map.of();

            switch (eventType) {
                case DomainEventContracts.BOOKING_CANCELLED -> {
                    for (String field : List.of("requesterProfileId", "technicianProfileId")) {
                        inboxService.recordIfAbsent(eventId, stringField(data, field), eventType,
                                "Lịch hẹn đã hủy", "Lịch hẹn dịch vụ đã được hủy.", Map.of("bookingId", stringField(data, "bookingId")));
                    }
                }
                case DomainEventContracts.BOOKING_REQUESTED -> inboxService.recordIfAbsent(
                        eventId,
                        stringField(data, "requesterProfileId"),
                        eventType,
                        "Đã tiếp nhận yêu cầu dịch vụ",
                        "Yêu cầu dịch vụ " + stringField(data, "serviceName") + " của bạn đang chờ điều phối.",
                        Map.of("bookingId", stringField(data, "bookingId")));
                case DomainEventContracts.BOOKING_ASSIGNED -> {
                    String bookingId = stringField(data, "bookingId");
                    inboxService.recordIfAbsent(
                            eventId,
                            stringField(data, "requesterProfileId"),
                            eventType,
                            "Đã phân công Kỹ thuật viên AI",
                            "Lịch hẹn dịch vụ " + stringField(data, "serviceName") + " đã được gán kỹ thuật viên.",
                            Map.of("bookingId", bookingId));
                    inboxService.recordIfAbsent(
                            eventId,
                            stringField(data, "technicianProfileId"),
                            eventType,
                            "Công việc mới được điều phối",
                            "Bạn vừa được phân công lịch hẹn dịch vụ " + stringField(data, "serviceName"),
                            Map.of("bookingId", bookingId));
                }
                case DomainEventContracts.BOOKING_REASSIGNED -> {
                    String bookingId = stringField(data, "bookingId");
                    String serviceName = stringField(data, "serviceName");
                    inboxService.recordIfAbsent(
                            eventId,
                            stringField(data, "requesterProfileId"),
                            eventType,
                            "Thay đổi kỹ thuật viên",
                            "Lịch hẹn dịch vụ " + serviceName + " đã được điều phối sang kỹ thuật viên " + stringField(data, "newTechnicianName") + ".",
                            Map.of("bookingId", bookingId));
                    inboxService.recordIfAbsent(
                            eventId,
                            stringField(data, "oldTechnicianProfileId"),
                            eventType,
                            "Lịch hẹn đã được điều chuyển",
                            "Lịch hẹn dịch vụ " + serviceName + " đã được chuyển cho kỹ thuật viên khác.",
                            Map.of("bookingId", bookingId));
                    inboxService.recordIfAbsent(
                            eventId,
                            stringField(data, "newTechnicianProfileId"),
                            eventType,
                            "Công việc mới được điều phối",
                            "Bạn vừa được phân công lịch hẹn dịch vụ " + serviceName + ".",
                            Map.of("bookingId", bookingId));
                }
                case DomainEventContracts.ORDER_REFUND_SUCCEEDED -> inboxService.recordIfAbsent(
                        eventId,
                        stringField(data, "buyerProfileId"),
                        eventType,
                        "Hoàn tiền thành công",
                        "Yêu cầu hoàn tiền cho đơn hàng " + stringField(data, "orderId") + " đã được hoàn tất.",
                        Map.of("orderId", stringField(data, "orderId")));
                case DomainEventContracts.BOOKING_COMPLETED -> inboxService.recordIfAbsent(
                        eventId,
                        stringField(data, "requesterProfileId"),
                        eventType,
                        "Hoàn tất dịch vụ — Mời bạn đánh giá",
                        "Dịch vụ " + stringField(data, "serviceName") + " đã hoàn thành.",
                        lifecycleData("bookingId", stringField(data, "bookingId"),
                                "homecare://requests/" + stringField(data, "bookingId")));
                case "WarrantyActivated" -> recordLifecycle(
                        eventId, eventType, data, "ownerProfileId",
                        "Bảo hành HomeCare đã được kích hoạt",
                        "Thiết bị của bạn đã được kích hoạt bảo hành HomeCare.",
                        "deviceId");
                case DomainEventContracts.INSTALLATION_COMPLETED, "InstallationCompleted" -> recordLifecycle(
                        eventId, eventType, data, "requesterProfileId",
                        "Lắp đặt thiết bị đã hoàn tất",
                        "Lắp đặt thiết bị của bạn đã hoàn tất và được ghi nhận.",
                        "deviceId");
                case "CareRecordUpdated" -> recordLifecycle(
                        eventId, eventType, data, "ownerProfileId",
                        "Lịch sử chăm sóc đã cập nhật",
                        "Lịch sử chăm sóc thiết bị của bạn vừa được cập nhật.",
                        "deviceId");
                case "QuotationSent" -> recordLifecycle(
                        eventId, eventType, data, "requesterProfileId",
                        "Báo giá dịch vụ đã được gửi",
                        "Bạn có một báo giá mới cần xem xét.",
                        "requestId");
                case "WarrantyRequestRejected" -> recordLifecycle(
                        eventId, eventType, data, "requesterProfileId",
                        "Yêu cầu bảo hành chưa được chấp thuận",
                        "Yêu cầu bảo hành của bạn chưa được chấp thuận.",
                        "requestId");
                case "FallbackToMarketplaceAvailable" -> recordLifecycle(
                        eventId, eventType, data, "requesterProfileId",
                        "Đã có lựa chọn thay thế trên Marketplace",
                        "Bạn có thể xem các lựa chọn dịch vụ thay thế trên Marketplace.",
                        "requestId");
                case DomainEventContracts.ORDER_CREATED -> inboxService.recordIfAbsent(
                        eventId,
                        stringField(data, "buyerProfileId"),
                        eventType,
                        "Đơn hàng đã tạo thành công",
                        "Đơn hàng của bạn đang chờ thanh toán qua VNPAY Sandbox.",
                        Map.of("orderId", stringField(data, "orderId")));
                case DomainEventContracts.ORDER_PAYMENT_SUCCEEDED -> inboxService.recordIfAbsent(
                        eventId,
                        stringField(data, "buyerProfileId"),
                        eventType,
                        "Thanh toán VNPAY thành công",
                        "Đơn hàng " + stringField(data, "orderId") + " đã được xác nhận thanh toán.",
                        Map.of("orderId", stringField(data, "orderId")));
                case DomainEventContracts.ORDER_PAYMENT_FAILED -> inboxService.recordIfAbsent(
                        eventId,
                        stringField(data, "buyerProfileId"),
                        eventType,
                        "Thanh toán đơn hàng chưa thành công",
                        "Giao dịch VNPAY cho đơn hàng " + stringField(data, "orderId") + " không thành công.",
                        Map.of("orderId", stringField(data, "orderId")));
                case DomainEventContracts.ORDER_LINE_DELIVERED -> {
                    int warrantyMonths = intField(data, "warrantyMonths");
                    String productName = stringField(data, "productName");
                    String title = warrantyMonths > 0
                            ? "Giao hàng thành công & Đã kích hoạt Bảo hành điện tử"
                            : "Giao hàng thành công — Hỗ trợ đổi trả tại Shop";
                    String body = warrantyMonths > 0
                            ? "Sản phẩm " + productName + " đã được kích hoạt bảo hành " + warrantyMonths + " tháng tại " + stringField(data, "merchantName") + "."
                            : "Sản phẩm " + productName + " đã giao thành công (hỗ trợ đổi/trả trong " + intField(data, "returnWindowDays") + " ngày).";
                    inboxService.recordIfAbsent(
                            eventId,
                            stringField(data, "buyerProfileId"),
                            eventType,
                            title,
                            body,
                            Map.of("orderId", stringField(data, "orderId")));
                }
                case DomainEventContracts.ORDER_RETURN_APPROVED -> inboxService.recordIfAbsent(
                        eventId,
                        stringField(data, "buyerProfileId"),
                        eventType,
                        "Yêu cầu đổi/trả hàng đã được Cửa hàng chấp thuận",
                        "Yêu cầu " + stringField(data, "returnType") + " cho sản phẩm " + stringField(data, "productName") + " đã được duyệt.",
                        Map.of("orderId", stringField(data, "orderId")));
                case DomainEventContracts.MAINTENANCE_REMINDER_DUE -> inboxService.recordIfAbsent(
                        eventId,
                        stringField(data, "ownerProfileId"),
                        eventType,
                        "Nhắc lịch bảo dưỡng thiết bị định kỳ",
                        "Thiết bị của bạn đã đến hạn bảo dưỡng: " + stringField(data, "title"),
                        lifecycleData("scheduleId", stringField(data, "scheduleId"),
                                "homecare://devices/" + stringField(data, "deviceId")));
                default -> log.debug("Ignored domain event type {}", eventType);
            }
        } catch (Exception e) {
            log.warn("Failed to parse/process domain event payload: {}", e.getMessage());
            throw new IllegalStateException("Failed to process domain event", e);
        }
    }

    private static String deterministicFallbackEventId(String eventType, String payloadJson) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((eventType + "|" + payloadJson).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private void recordLifecycle(
                String eventId,
                String eventType,
                Map<String, Object> data,
                String recipientField,
                String title,
                String body,
                String targetIdField) {
            String targetId = stringField(data, targetIdField);
            String deeplinkPrefix = "deviceId".equals(targetIdField)
                    ? "homecare://devices/"
                    : "homecare://requests/";
            inboxService.recordIfAbsent(
                    eventId,
                    firstNonBlank(stringField(data, recipientField), stringField(data, "ownerProfileId"),
                            stringField(data, "requesterProfileId")),
                    eventType,
                    title,
                    body + (targetId.isBlank() ? "" : " Xem chi tiết: " + deeplinkPrefix + targetId),
                    lifecycleData(targetIdField, targetId, targetId.isBlank() ? null : deeplinkPrefix + targetId));
        }

    private Map<String, String> lifecycleData(String idField, String id, String deeplink) {
            Map<String, String> data = new java.util.LinkedHashMap<>();
            data.put(idField, id);
            if (deeplink != null && !deeplink.isBlank()) {
                data.put("deeplink", deeplink);
            }
            return data;
        }

    private String firstNonBlank(String... values) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            return "";
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
}
