package com.homecare.notification.service;

import tools.jackson.databind.ObjectMapper;

import com.homecare.common.event.DomainEventContracts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DomainEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(DomainEventConsumer.class);
    public static final String QUEUE = "notification.inbox.q";
    public static final String EXCHANGE = DomainEventContracts.EXCHANGE;

    private final NotificationInboxService inboxService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<RabbitTemplate> rabbitTemplateProvider;

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
                            "Đã phân công kỹ thuật viên",
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
                case DomainEventContracts.BOOKING_COMPLETED, DomainEventContracts.BOOKING_ACCEPTED -> inboxService.recordIfAbsent(
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
                case "QuotationSent", "quotation.sent.v1" -> recordLifecycle(
                        eventId, eventType, data, "requesterProfileId",
                        "Báo giá dịch vụ đã được gửi",
                        "Bạn có một báo giá mới cần xem xét.",
                        "serviceRequestId");
                case DomainEventContracts.QUOTATION_SUPERSEDED -> recordLifecycle(
                        eventId, eventType, data, "requesterProfileId",
                        "Báo giá cũ đã được thay thế",
                        "Đối tác đã phát hành phiên bản báo giá mới thay thế bản trước đó.",
                        "serviceRequestId");
                case DomainEventContracts.QUOTATION_REJECTED -> recordLifecycle(
                        eventId, eventType, data, "requesterProfileId",
                        "Đã ghi nhận từ chối báo giá",
                        "Bạn có thể chọn đơn vị thay thế hoặc điều chỉnh phạm vi yêu cầu.",
                        "serviceRequestId");
                case DomainEventContracts.QUOTATION_REVISED_SENT -> recordLifecycle(
                        eventId, eventType, data, "requesterProfileId",
                        "Báo giá phát sinh cần phê duyệt",
                        "Kỹ thuật viên vừa gửi báo giá điều chỉnh chi phí phát sinh cho lịch hẹn.",
                        "bookingId");
                case DomainEventContracts.SERVICE_REQUEST_TIMEOUT -> recordLifecycle(
                        eventId, eventType, data, "requesterProfileId",
                        "Đơn vị chưa phản hồi trong 24 giờ",
                        "Bạn có thể chọn đơn vị thay thế khác cho yêu cầu dịch vụ này.",
                        "serviceRequestId");
                case DomainEventContracts.SERVICE_REQUEST_EXPIRY_WARNING -> recordLifecycle(
                        eventId, eventType, data, "requesterProfileId",
                        "Yêu cầu dịch vụ sắp hết hạn sau 24 giờ",
                        "Vui lòng chọn đơn vị thay thế hoặc hủy yêu cầu trước khi tự động hết hạn.",
                        "serviceRequestId");
                case DomainEventContracts.SERVICE_REQUEST_EXPIRED -> recordLifecycle(
                        eventId, eventType, data, "requesterProfileId",
                        "Yêu cầu dịch vụ đã hết hạn",
                        "Yêu cầu dịch vụ đã tự động đóng sau 7 ngày không có thao tác.",
                        "serviceRequestId");
                case "service.request.fallback.available.v1", "warranty.request.fallback.available.v1" -> recordLifecycle(
                        eventId, eventType, data, "requesterProfileId",
                        "Có thể chọn đơn vị thay thế",
                        "Đơn vị hiện tại không thể tiếp nhận; bạn có thể chọn nhà cung cấp khác.",
                        "serviceRequestId");
                case DomainEventContracts.BOOKING_RESCHEDULE_REQUESTED -> {
                    String bookingId = stringField(data, "bookingId");
                    inboxService.recordIfAbsent(
                            eventId,
                            stringField(data, "requesterProfileId"),
                            eventType,
                            "Có đề xuất đổi lịch hẹn",
                            "Lịch hẹn dịch vụ vừa có yêu cầu thay đổi thời gian, vui lòng xác nhận.",
                            Map.of("bookingId", bookingId));
                }
                case DomainEventContracts.BOOKING_TECHNICIAN_NO_SHOW -> {
                    String bookingId = stringField(data, "bookingId");
                    inboxService.recordIfAbsent(
                            eventId,
                            stringField(data, "requesterProfileId"),
                            eventType,
                            "Đã ghi nhận thợ không đến",
                            "Phí khảo sát (nếu có) đã được miễn; bạn có thể chọn đơn vị khác hoặc đặt lại.",
                            Map.of("bookingId", bookingId));
                }
                case DomainEventContracts.BOOKING_COMPLETION_PENDING_ACCEPTANCE -> {
                    String bookingId = stringField(data, "bookingId");
                    inboxService.recordIfAbsent(
                            eventId,
                            stringField(data, "requesterProfileId"),
                            eventType,
                            "Công việc đã hoàn tất — Vui lòng nghiệm thu",
                            "Kỹ thuật viên đã hoàn thành dịch vụ " + stringField(data, "serviceName")
                                    + ". Vui lòng kiểm tra bằng chứng và xác nhận trong 7 ngày.",
                            Map.of("bookingId", bookingId));
                    publishCompletionDeliveryCallback(bookingId);
                }
                case DomainEventContracts.BOOKING_ACCEPTANCE_REMINDER -> inboxService.recordIfAbsent(
                        eventId,
                        stringField(data, "requesterProfileId"),
                        eventType,
                        "Nhắc nghiệm thu dịch vụ (Ngày thứ 3)",
                        "Dịch vụ " + stringField(data, "serviceName") + " đang chờ bạn xác nhận nghiệm thu.",
                        Map.of("bookingId", stringField(data, "bookingId")));
                case DomainEventContracts.BOOKING_REVISION_REQUESTED -> inboxService.recordIfAbsent(
                        eventId,
                        stringField(data, "technicianProfileId"),
                        eventType,
                        "Khách hàng yêu cầu chỉnh sửa lại",
                        "Lịch hẹn dịch vụ " + stringField(data, "serviceName") + " cần khắc phục lại trong 48 giờ.",
                        Map.of("bookingId", stringField(data, "bookingId")));
                case DomainEventContracts.BOOKING_WAITING_FOR_PARTS, DomainEventContracts.BOOKING_WAITING_FOR_PARTS_REMINDER -> inboxService.recordIfAbsent(
                        eventId,
                        stringField(data, "requesterProfileId"),
                        eventType,
                        "Lịch hẹn đang chờ linh kiện",
                        "Dịch vụ " + stringField(data, "serviceName") + " đang tạm dừng chờ linh kiện thay thế.",
                        Map.of("bookingId", stringField(data, "bookingId")));
                case DomainEventContracts.BOOKING_CUSTOMER_NO_SHOW -> inboxService.recordIfAbsent(
                        eventId,
                        stringField(data, "requesterProfileId"),
                        eventType,
                        "Ghi nhận vắng mặt tại lịch hẹn",
                        "Kỹ thuật viên đã đến và chờ đủ 30 phút nhưng không liên hệ được.",
                        Map.of("bookingId", stringField(data, "bookingId")));
                case DomainEventContracts.DISPUTE_OPENED -> inboxService.recordIfAbsent(
                        eventId,
                        stringField(data, "requesterProfileId"),
                        eventType,
                        "Đã tiếp nhận tranh chấp",
                        "HomeCare đã ghi nhận tranh chấp và bảo toàn toàn bộ bằng chứng liên quan.",
                        Map.of("disputeId", stringField(data, "disputeId"), "bookingId", stringField(data, "bookingId")));
                case DomainEventContracts.DISPUTE_SLA_REMINDER -> inboxService.recordIfAbsent(
                        eventId,
                        stringField(data, "requesterProfileId"),
                        eventType,
                        "Cập nhật tiến độ xử lý tranh chấp",
                        "Tranh chấp của bạn vừa có cập nhật thời hạn phản hồi.",
                        Map.of("disputeId", stringField(data, "disputeId"), "bookingId", stringField(data, "bookingId")));
                case DomainEventContracts.DISPUTE_RESOLVED -> inboxService.recordIfAbsent(
                        eventId,
                        stringField(data, "requesterProfileId"),
                        eventType,
                        "Tranh chấp đã có kết quả xử lý",
                        "Admin HomeCare đã cập nhật kết quả xử lý tranh chấp cho lịch hẹn của bạn.",
                        Map.of("disputeId", stringField(data, "disputeId"), "bookingId", stringField(data, "bookingId")));
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
                case DomainEventContracts.PROVIDER_SCHEDULE_UPDATED -> {
                    String technicianProfileId = stringField(data, "technicianProfileId");
                    String summary = firstNonBlank(
                            stringField(data, "summary"),
                            "Tổ chức vừa cập nhật lịch làm việc của bạn. Mở mục Ca trực để xem lịch mới nhất.");
                    inboxService.recordIfAbsent(
                            eventId,
                            technicianProfileId,
                            eventType,
                            "Lịch làm việc đã được cập nhật",
                            summary,
                            lifecycleData("technicianId", stringField(data, "technicianId"), "homecare://technician/schedule"));
                }
                case DomainEventContracts.PROVIDER_SCHEDULE_REQUEST_CREATED -> {
                    String technicianName = firstNonBlank(stringField(data, "technicianName"), "Kỹ thuật viên");
                    String requestTypeLabel = formatScheduleRequestType(stringField(data, "requestType"));
                    String rangeLabel = formatDateRange(stringField(data, "fromDate"), stringField(data, "toDate"));
                    String body = technicianName + " vừa gửi yêu cầu " + requestTypeLabel
                            + (rangeLabel.isBlank() ? "" : " (" + rangeLabel + ")") + ".";
                    Object recipientsObj = data.get("recipientProfileIds");
                    if (recipientsObj instanceof List<?> recipients) {
                        for (Object rec : recipients) {
                            if (rec != null) {
                                inboxService.recordIfAbsent(
                                        eventId,
                                        String.valueOf(rec),
                                        eventType,
                                        "Yêu cầu điều chỉnh lịch mới từ KTV",
                                        body,
                                        lifecycleData("requestId", stringField(data, "requestId"), "/org/schedules"));
                            }
                        }
                    }
                }
                case DomainEventContracts.PROVIDER_SCHEDULE_REQUEST_REVIEWED -> {
                    String technicianProfileId = stringField(data, "technicianProfileId");
                    String decision = stringField(data, "status");
                    boolean approved = "APPROVED".equalsIgnoreCase(decision);
                    String requestTypeLabel = formatScheduleRequestType(stringField(data, "requestType"));
                    String rangeLabel = formatDateRange(stringField(data, "fromDate"), stringField(data, "toDate"));
                    String reviewNote = stringField(data, "reviewNote");
                    String title = approved
                            ? "Yêu cầu điều chỉnh lịch đã được duyệt"
                            : "Yêu cầu điều chỉnh lịch bị từ chối";
                    String body = "Yêu cầu " + requestTypeLabel
                            + (rangeLabel.isBlank() ? "" : " (" + rangeLabel + ")")
                            + (approved ? " đã được tổ chức phê duyệt và cập nhật vào lịch trực." : " đã bị từ chối.")
                            + (reviewNote.isBlank() ? "" : " Ghi chú: " + reviewNote);
                    inboxService.recordIfAbsent(
                            eventId,
                            technicianProfileId,
                            eventType,
                            title,
                            body,
                            lifecycleData("requestId", stringField(data, "requestId"), "homecare://technician/schedule"));
                }
                case DomainEventContracts.PARTS_REQUEST_CREATED -> {
                    String bookingId = stringField(data, "bookingId");
                    String partName = firstNonBlank(stringField(data, "partName"), "linh kiện");
                    recordForRecipients(
                            eventId,
                            eventType,
                            data,
                            "providerRecipientProfileIds",
                            firstNonBlank(stringField(data, "providerOwnerProfileId"),
                                    stringField(data, "providerId").isBlank() ? "" : "org:" + stringField(data, "providerId")),
                            "Yêu cầu duyệt linh kiện ngoài",
                            "Kỹ thuật viên đề xuất dùng " + partName + " từ nhà cung cấp ngoài, cần đối tác phê duyệt.",
                            lifecycleData("bookingId", bookingId, "/org/bookings"));
                }
                case DomainEventContracts.PARTS_REQUEST_REVIEWED -> {
                    String bookingId = stringField(data, "bookingId");
                    String partName = firstNonBlank(stringField(data, "partName"), "linh kiện");
                    boolean approved = "PROVIDER_APPROVED".equalsIgnoreCase(stringField(data, "status"))
                            || "APPROVED".equalsIgnoreCase(stringField(data, "status"));
                    inboxService.recordIfAbsent(
                            eventId,
                            firstNonBlank(stringField(data, "requestedByProfileId"), stringField(data, "technicianProfileId")),
                            eventType,
                            approved ? "Yêu cầu linh kiện đã được duyệt" : "Yêu cầu linh kiện bị từ chối",
                            "Đề xuất linh kiện " + partName + (approved ? " đã được đối tác phê duyệt." : " đã bị từ chối."),
                            lifecycleData("bookingId", bookingId, "homecare://technician/jobs/" + bookingId));
                }
                case DomainEventContracts.WARRANTY_CLAIM_SUBMITTED -> {
                    String bookingId = stringField(data, "bookingId");
                    String claimId = stringField(data, "claimId");
                    String claimStatus = stringField(data, "claimStatus");
                    String category = firstNonBlank(stringField(data, "warrantyCategory"), "WARRANTY");
                    if ("PENDING_VERIFICATION".equalsIgnoreCase(claimStatus)) {
                        Map<String, String> adminData = lifecycleData("claimId", claimId, "/admin/warranties");
                        adminData.put("bookingId", bookingId);
                        recordForRecipients(
                                eventId,
                                eventType,
                                data,
                                "adminProfileIds",
                                "admin",
                                "Yêu cầu bảo hành chờ Admin xác minh",
                                "Yêu cầu bảo hành (" + category + ") cần Admin xác minh tính hợp lệ.",
                                adminData);
                    }
                    Map<String, String> orgData = lifecycleData("claimId", claimId, "/org/warranties");
                    orgData.put("bookingId", bookingId);
                    recordForRecipients(
                            eventId,
                            eventType,
                            data,
                            "providerRecipientProfileIds",
                            firstNonBlank(stringField(data, "providerOwnerProfileId"),
                                    stringField(data, "providerId").isBlank() ? "" : "org:" + stringField(data, "providerId")),
                            "Có yêu cầu bảo hành mới",
                            "Khách hàng vừa gửi yêu cầu bảo hành (" + category + ") cho lịch hẹn.",
                            orgData);
                }
                case DomainEventContracts.WARRANTY_CLAIM_VERIFIED -> {
                    String bookingId = stringField(data, "bookingId");
                    String claimId = stringField(data, "claimId");
                    boolean verified = "VERIFIED".equalsIgnoreCase(stringField(data, "claimStatus"))
                            || "PENDING_PROVIDER_RESPONSE".equalsIgnoreCase(stringField(data, "claimStatus"));
                    Map<String, String> meta = lifecycleData("claimId", claimId, "homecare://requests/" + bookingId);
                    meta.put("bookingId", bookingId);
                    inboxService.recordIfAbsent(
                            eventId,
                            stringField(data, "requesterProfileId"),
                            eventType,
                            verified ? "Yêu cầu bảo hành đã được xác minh" : "Yêu cầu bảo hành bị từ chối xác minh",
                            verified
                                    ? "Admin đã xác minh hợp lệ yêu cầu bảo hành của bạn."
                                    : "Yêu cầu bảo hành không đủ điều kiện sau bước xác minh.",
                            meta);
                    Map<String, String> orgMeta = lifecycleData("claimId", claimId, "/org/warranties");
                    orgMeta.put("bookingId", bookingId);
                    recordForRecipients(
                            eventId,
                            eventType,
                            data,
                            "providerRecipientProfileIds",
                            firstNonBlank(stringField(data, "providerOwnerProfileId"),
                                    stringField(data, "providerId").isBlank() ? "" : "org:" + stringField(data, "providerId")),
                            "Cập nhật xác minh yêu cầu bảo hành",
                            verified
                                    ? "Yêu cầu bảo hành đã được Admin xác minh; vui lòng phản hồi xử lý."
                                    : "Admin đã đóng yêu cầu bảo hành do không đủ điều kiện.",
                            orgMeta);
                }
                case DomainEventContracts.WARRANTY_CLAIM_RESPONDED -> {
                    String bookingId = stringField(data, "bookingId");
                    String claimId = stringField(data, "claimId");
                    String reworkBookingId = stringField(data, "reworkBookingId");
                    boolean accepted = !reworkBookingId.isBlank()
                            || "REWORK_CREATED".equalsIgnoreCase(stringField(data, "claimStatus"))
                            || "PROVIDER_ACCEPTED".equalsIgnoreCase(stringField(data, "claimStatus"));
                    Map<String, String> meta = lifecycleData("claimId", claimId, "homecare://requests/" + bookingId);
                    meta.put("bookingId", bookingId);
                    if (!reworkBookingId.isBlank()) {
                        meta.put("reworkBookingId", reworkBookingId);
                    }
                    inboxService.recordIfAbsent(
                            eventId,
                            stringField(data, "requesterProfileId"),
                            eventType,
                            accepted ? "Đơn vị đã tiếp nhận bảo hành 0đ" : "Đơn vị từ chối yêu cầu bảo hành",
                            accepted
                                    ? "Đối tác đã tạo lịch bảo hành 0đ để khắc phục cho bạn."
                                    : "Đối tác chưa chấp thuận yêu cầu bảo hành; bạn có thể theo dõi chi tiết hoặc khiếu nại.",
                            meta);
                }
                case DomainEventContracts.WARRANTY_CLAIM_SLA_ESCALATED -> {
                    String bookingId = stringField(data, "bookingId");
                    String claimId = stringField(data, "claimId");
                    Map<String, String> meta = lifecycleData("claimId", claimId, "homecare://requests/" + bookingId);
                    meta.put("bookingId", bookingId);
                    inboxService.recordIfAbsent(
                            eventId,
                            stringField(data, "requesterProfileId"),
                            eventType,
                            "Yêu cầu bảo hành quá hạn phản hồi 48 giờ",
                            "Đơn vị chưa phản hồi trong 48 giờ; hệ thống đã đánh dấu quá hạn để hỗ trợ xử lý.",
                            meta);
                }
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
            Map<String, String> data = new LinkedHashMap<>();
            data.put(idField, id);
            if (deeplink != null && !deeplink.isBlank()) {
                data.put("deeplink", deeplink);
            }
            return data;
        }

    private void recordForRecipients(
            String eventId,
            String eventType,
            Map<String, Object> data,
            String listField,
            String fallbackRecipientId,
            String title,
            String body,
            Map<String, String> extraData) {
        Object recipientsObj = data.get(listField);
        boolean sent = false;
        if (recipientsObj instanceof List<?> recipients) {
            for (Object rec : recipients) {
                if (rec != null && !String.valueOf(rec).isBlank()) {
                    inboxService.recordIfAbsent(eventId, String.valueOf(rec), eventType, title, body, extraData);
                    sent = true;
                }
            }
        }
        if (!sent && fallbackRecipientId != null && !fallbackRecipientId.isBlank()) {
            inboxService.recordIfAbsent(eventId, fallbackRecipientId, eventType, title, body, extraData);
        }
    }

    private String formatScheduleRequestType(String requestType) {
        return switch (requestType.toUpperCase()) {
            case "LEAVE" -> "xin nghỉ phép";
            case "SHIFT_CHANGE" -> "đổi giờ ca trực";
            case "EXTRA_HOURS" -> "đăng ký thêm giờ";
            default -> "điều chỉnh lịch";
        };
    }

    private String formatDateRange(String fromDate, String toDate) {
        if (fromDate == null || fromDate.isBlank()) {
            return "";
        }
        if (toDate == null || toDate.isBlank() || fromDate.equals(toDate)) {
            return fromDate;
        }
        return fromDate + " → " + toDate;
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

    private void publishCompletionDeliveryCallback(String bookingId) {
        if (bookingId == null || bookingId.isBlank()) {
            return;
        }
        RabbitTemplate template = rabbitTemplateProvider.getIfAvailable();
        if (template == null) {
            return;
        }
        try {
            Instant now = Instant.now();
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put(DomainEventContracts.FIELD_EVENT_ID, UUID.randomUUID().toString());
            envelope.put(DomainEventContracts.FIELD_EVENT_TYPE, DomainEventContracts.NOTIFICATION_COMPLETION_DELIVERED);
            envelope.put(DomainEventContracts.FIELD_OCCURRED_AT, now.toString());
            envelope.put(DomainEventContracts.FIELD_PRODUCER, "notification-service");
            envelope.put(DomainEventContracts.FIELD_DATA, Map.of(
                    "bookingId", bookingId,
                    "deliveredAt", now.toString()));
            template.convertAndSend(
                    EXCHANGE,
                    DomainEventContracts.NOTIFICATION_COMPLETION_DELIVERED,
                    objectMapper.writeValueAsString(envelope));
        } catch (Exception ex) {
            log.warn("Failed to publish completion delivery callback for booking {}: {}", bookingId, ex.getMessage());
            throw new IllegalStateException("Failed to publish completion delivery callback for booking " + bookingId, ex);
        }
    }
}
