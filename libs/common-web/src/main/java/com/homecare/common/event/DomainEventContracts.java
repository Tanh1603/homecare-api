package com.homecare.common.event;

/**
 * Shared RabbitMQ exchange, envelope field names, and cross-service domain event routing keys.
 */
public final class DomainEventContracts {

    private DomainEventContracts() {
    }

    public static final String EXCHANGE = "homecare.domain.events";
    public static final String DLX = "homecare.events.dlx";

    public static String dlqName(String queueName) {
        return queueName + ".dlq";
    }

    public static final String FIELD_EVENT_ID = "eventId";
    public static final String FIELD_EVENT_TYPE = "eventType";
    public static final String FIELD_OCCURRED_AT = "occurredAt";
    public static final String FIELD_PRODUCER = "producer";
    public static final String FIELD_DATA = "data";

    // Organization & Provider governance lifecycle events
    public static final String ORG_APPROVED = "organization.approved.v1";
    public static final String ORG_REJECTED = "organization.rejected.v1";
    public static final String ORG_UPDATED = "organization.updated.v1";
    public static final String ORG_SUSPENDED = "organization.suspended.v1";
    public static final String ORG_BANNED = "organization.banned.v1";
    public static final String ORG_MEMBER_CHANGED = "organization.member.changed.v1";
    public static final String OFFERING_DEACTIVATED = "provider.offering.deactivated.v1";
    public static final String USER_SUSPENDED = "user.suspended.v1";

    // Booking lifecycle events
    public static final String BOOKING_REQUESTED = "booking.requested.v1";
    public static final String BOOKING_ASSIGNED = "booking.assigned.v1";
    public static final String BOOKING_REASSIGNED = "booking.reassigned.v1";
    public static final String BOOKING_CANCELLED = "booking.cancelled.v1";
    public static final String BOOKING_COMPLETED = "booking.completed.v1";
    public static final String INSTALLATION_COMPLETED = "INSTALLATION_COMPLETED";

    // Commerce lifecycle events
    public static final String ORDER_CREATED = "order.created.v1";
    public static final String ORDER_CANCELLED = "order.cancelled.v1";
    public static final String ORDER_PAYMENT_SUCCEEDED = "order.payment.succeeded.v1";
    public static final String ORDER_PAYMENT_FAILED = "order.payment.failed.v1";
    public static final String ORDER_LINE_DELIVERED = "order.line.delivered.v1";
    public static final String ORDER_RETURN_APPROVED = "order.return.approved.v1";
    public static final String ORDER_REFUND_SUCCEEDED = "order.refund.succeeded.v1";

    // Household lifecycle events
    public static final String MAINTENANCE_REMINDER_DUE = "maintenance.reminder.due.v1";
}
