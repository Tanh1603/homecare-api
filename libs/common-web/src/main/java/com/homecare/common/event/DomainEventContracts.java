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
    public static final String PROVIDER_SCHEDULE_UPDATED = "provider.schedule.updated.v1";
    public static final String PROVIDER_SCHEDULE_REQUEST_CREATED = "provider.schedule.request.created.v1";
    public static final String PROVIDER_SCHEDULE_REQUEST_REVIEWED = "provider.schedule.request.reviewed.v1";
    public static final String USER_SUSPENDED = "user.suspended.v1";

    // Booking lifecycle events
    public static final String BOOKING_REQUESTED = "booking.requested.v1";
    public static final String BOOKING_ASSIGNED = "booking.assigned.v1";
    public static final String BOOKING_REASSIGNED = "booking.reassigned.v1";
    public static final String BOOKING_CANCELLED = "booking.cancelled.v1";
    public static final String BOOKING_COMPLETED = "booking.completed.v1";
    public static final String BOOKING_ACCEPTED = "booking.accepted.v1";
    public static final String BOOKING_RESCHEDULE_REQUESTED = "booking.reschedule.requested.v1";
    public static final String BOOKING_TECHNICIAN_NO_SHOW = "booking.technician.no_show.v1";
    public static final String BOOKING_ALLOCATION_RELEASE_RETRY = "booking.allocation.release.retry.v1";
    public static final String BOOKING_WAITING_FOR_PARTS = "booking.waiting_for_parts.v1";
    public static final String BOOKING_WAITING_FOR_PARTS_REMINDER = "booking.waiting_for_parts.reminder.v1";
    public static final String BOOKING_CUSTOMER_NO_SHOW = "booking.customer_no_show.v1";
    public static final String BOOKING_COMPLETION_PENDING_ACCEPTANCE = "booking.completion.pending_acceptance.v1";
    public static final String BOOKING_ACCEPTANCE_REMINDER = "booking.acceptance.reminder.v1";
    public static final String BOOKING_REVISION_REQUESTED = "booking.revision_requested.v1";
    public static final String NOTIFICATION_COMPLETION_DELIVERED = "notification.booking.completion.delivered.v1";
    public static final String NOTIFICATION_COMPLETION_FAILED = "notification.booking.completion.failed.v1";
    public static final String DISPUTE_OPENED = "dispute.opened.v1";
    public static final String DISPUTE_SLA_REMINDER = "dispute.sla.reminder.v1";
    public static final String DISPUTE_RESOLVED = "dispute.resolved.v1";
    public static final String INSTALLATION_COMPLETED = "INSTALLATION_COMPLETED";

    // ServiceRequest & Quotation lifecycle events
    public static final String SERVICE_REQUEST_TIMEOUT = "service.request.timeout.v1";
    public static final String SERVICE_REQUEST_EXPIRY_WARNING = "service.request.expiry.warning.v1";
    public static final String SERVICE_REQUEST_EXPIRED = "service.request.expired.v1";
    public static final String QUOTATION_SUPERSEDED = "quotation.superseded.v1";
    public static final String QUOTATION_REJECTED = "quotation.rejected.v1";
    public static final String QUOTATION_REVISED_SENT = "quotation.revised.sent.v1";

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

    // Parts tracking & Warranty claim lifecycle events (006-warranty-and-claims)
    public static final String PARTS_REQUEST_CREATED = "parts.request.created.v1";
    public static final String PARTS_REQUEST_REVIEWED = "parts.request.reviewed.v1";
    public static final String WARRANTY_CLAIM_SUBMITTED = "warranty.claim.submitted.v1";
    public static final String WARRANTY_CLAIM_VERIFIED = "warranty.claim.verified.v1";
    public static final String WARRANTY_CLAIM_RESPONDED = "warranty.claim.responded.v1";
    public static final String WARRANTY_CLAIM_SLA_ESCALATED = "warranty.claim.sla_escalated.v1";

    // Review rating sync events (007-reviews-and-notifications)
    public static final String REVIEW_RATING_UPDATED = "review.rating.updated.v1";
}
