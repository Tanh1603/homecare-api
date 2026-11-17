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

    // Organization lifecycle events
    public static final String ORG_APPROVED = "organization.approved.v1";
    public static final String ORG_UPDATED = "organization.updated.v1";
    public static final String ORG_SUSPENDED = "organization.suspended.v1";
    public static final String ORG_MEMBER_CHANGED = "organization.member.changed.v1";

    // Booking & Commerce lifecycle events
    public static final String BOOKING_REASSIGNED = "booking.reassigned.v1";
    public static final String ORDER_REFUND_SUCCEEDED = "order.refund.succeeded.v1";
}
