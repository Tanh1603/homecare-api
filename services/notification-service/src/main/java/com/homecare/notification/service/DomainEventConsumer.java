package com.homecare.notification.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.homecare.common.event.DomainEventContracts;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

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
            String eventId = String.valueOf(root.getOrDefault("eventId", "evt-unknown"));
            String eventType = String.valueOf(root.getOrDefault("eventType", "domain.event"));
            Map<String, Object> data = root.get("data") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m
                    : Map.of();

            switch (eventType) {
                case "booking.requested.v1" -> inboxService.recordIfAbsent(
                        eventId,
                        stringField(data, "requesterProfileId"),
                        eventType,
                        "Đã tiếp nhận yêu cầu dịch vụ",
                        "Yêu cầu dịch vụ " + stringField(data, "serviceName") + " của bạn đang chờ điều phối.",
                        Map.of("bookingId", stringField(data, "bookingId")));
                case "booking.assigned.v1" -> {
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
                case "booking.completed.v1" -> inboxService.recordIfAbsent(
                        eventId,
                        stringField(data, "requesterProfileId"),
                        eventType,
                        "Hoàn tất dịch vụ — Mời bạn đánh giá",
                        "Dịch vụ " + stringField(data, "serviceName") + " đã hoàn thành.",
                        Map.of("bookingId", stringField(data, "bookingId")));
                case "order.created.v1" -> inboxService.recordIfAbsent(
                        eventId,
                        stringField(data, "buyerProfileId"),
                        eventType,
                        "Đơn hàng đã tạo thành công",
                        "Đơn hàng của bạn đang chờ thanh toán qua VNPAY Sandbox.",
                        Map.of("orderId", stringField(data, "orderId")));
                case "order.payment.succeeded.v1" -> inboxService.recordIfAbsent(
                        eventId,
                        stringField(data, "buyerProfileId"),
                        eventType,
                        "Thanh toán VNPAY thành công",
                        "Đơn hàng " + stringField(data, "orderId") + " đã được xác nhận thanh toán.",
                        Map.of("orderId", stringField(data, "orderId")));
                case "order.payment.failed.v1" -> inboxService.recordIfAbsent(
                        eventId,
                        stringField(data, "buyerProfileId"),
                        eventType,
                        "Thanh toán đơn hàng chưa thành công",
                        "Giao dịch VNPAY cho đơn hàng " + stringField(data, "orderId") + " không thành công.",
                        Map.of("orderId", stringField(data, "orderId")));
                case "order.line.delivered.v1" -> {
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
                case "order.return.approved.v1" -> inboxService.recordIfAbsent(
                        eventId,
                        stringField(data, "buyerProfileId"),
                        eventType,
                        "Yêu cầu đổi/trả hàng đã được Cửa hàng chấp thuận",
                        "Yêu cầu " + stringField(data, "returnType") + " cho sản phẩm " + stringField(data, "productName") + " đã được duyệt.",
                        Map.of("orderId", stringField(data, "orderId")));
                case "maintenance.reminder.due.v1" -> inboxService.recordIfAbsent(
                        eventId,
                        stringField(data, "ownerProfileId"),
                        eventType,
                        "Nhắc lịch bảo dưỡng thiết bị định kỳ",
                        "Thiết bị của bạn đã đến hạn bảo dưỡng: " + stringField(data, "title"),
                        Map.of("deviceId", stringField(data, "deviceId"), "scheduleId", stringField(data, "scheduleId")));
                default -> log.debug("Ignored domain event type {}", eventType);
            }
        } catch (Exception e) {
            log.warn("Failed to parse domain event payload: {}", e.getMessage());
        }
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
