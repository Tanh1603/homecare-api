package com.homecare.booking.service;

import com.homecare.booking.client.ProviderGrpcClientAdapter;
import com.homecare.booking.entity.AssignmentAttemptEntity;
import com.homecare.booking.entity.BookingEntity;
import com.homecare.booking.entity.BookingHistoryEntry;
import com.homecare.booking.entity.BookingOutboxEventEntity;
import com.homecare.booking.repository.AssignmentAttemptRepository;
import com.homecare.booking.repository.BookingAllocationSagaRepository;
import com.homecare.booking.repository.BookingHistoryRepository;
import com.homecare.booking.repository.BookingOutboxEventRepository;
import com.homecare.booking.repository.BookingRepository;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.provider.v1.AllocateTechnicianResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class AssignmentCompensationRecoveryWorker {

    private static final Logger log = LoggerFactory.getLogger(AssignmentCompensationRecoveryWorker.class);
    static final int MAX_RETRIES = 10;

    private final AssignmentAttemptRepository attemptRepository;
    private final BookingRepository bookingRepository;
    private final BookingAllocationSagaRepository sagaRepository;
    private final BookingHistoryRepository historyRepository;
    private final BookingOutboxEventRepository outboxEventRepository;
    private final ProviderGrpcClientAdapter providerGrpcClient;
    private final PlatformTransactionManager transactionManager;
    private final ObjectMapper objectMapper;

    private record ClaimedRecovery(AssignmentAttemptEntity attempt, String claimedStatus) {}

    @Scheduled(
            fixedDelayString = "${homecare.bookings.recovery-poll-ms:5000}",
            initialDelayString = "${homecare.bookings.recovery-poll-ms:5000}")
    public void recoverPendingAttempts() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        List<ClaimedRecovery> claimed = tx.execute(status -> {
            Instant now = Instant.now();
            List<AssignmentAttemptEntity> due = attemptRepository.findDueForRecovery(now, PageRequest.of(0, 20));
            return due.stream().map(a -> {
                String originalStatus = a.getStatus();
                a.setClaimToken(UUID.randomUUID());
                a.setLeaseUntil(now.plusSeconds(15));
                a.setUpdatedAt(now);
                return new ClaimedRecovery(attemptRepository.save(a), originalStatus);
            }).toList();
        });

        if (claimed == null || claimed.isEmpty()) {
            return;
        }

        for (ClaimedRecovery item : claimed) {
            processClaimedAttempt(tx, item.attempt(), item.claimedStatus());
        }
    }

    private void processClaimedAttempt(
            TransactionTemplate tx,
            AssignmentAttemptEntity attempt,
            String claimedStatus) {
        if ("COMPENSATION_PENDING".equals(claimedStatus)) {
            recoverCompensation(tx, attempt);
            return;
        }
        recoverAllocation(tx, attempt, claimedStatus);
    }

    private void recoverAllocation(
            TransactionTemplate tx,
            AssignmentAttemptEntity attempt,
            String expectedStatus) {
        try {
            AllocateTechnicianResponse res = attempt.getTechnicianId() != null
                    ? providerGrpcClient.assignTechnician(
                            attempt.getOperationId(), attempt.getBookingId(), attempt.getServiceId(),
                            attempt.getDeviceType(), attempt.getPreferredStart(), attempt.getPreferredEnd(),
                            attempt.getProviderId(), attempt.getTechnicianId())
                    : providerGrpcClient.allocateTechnician(
                            attempt.getOperationId(), "op-" + attempt.getOperationId(), attempt.getBookingId(),
                            attempt.getServiceId(), attempt.getDeviceType(), attempt.getPreferredStart(),
                            attempt.getPreferredEnd(), attempt.getProviderId(), attempt.isStrictProviderMatch());

            if (res != null && res.getAllocated()) {
                Boolean needsCompensation = tx.execute(status -> {
                    Instant now = Instant.now();
                    BookingEntity booking = bookingRepository.lockById(attempt.getBookingId()).orElse(null);
                    if (booking == null || "CANCELLED".equals(booking.getStatus())) {
                        int fenced = attemptRepository.transitionKeepingLease(
                                attempt.getOperationId(), attempt.getClaimToken(), expectedStatus,
                                "COMPENSATION_PENDING", now.plusSeconds(15), "BOOKING_CANCELLED_DURING_ALLOCATION", now);
                        if (fenced == 1 && booking != null) {
                            booking.setAllocationStatus("COMPENSATION_PENDING");
                            booking.setUpdatedAt(now);
                            bookingRepository.save(booking);
                        }
                        return fenced == 1;
                    }
                    int fenced = attemptRepository.transitionWithFence(
                            attempt.getOperationId(), attempt.getClaimToken(), expectedStatus,
                            "ALLOCATED", null, now);
                    if (fenced == 0) {
                        return false;
                    }
                    booking.setProviderId(parseUuidOrNull(res.getProviderId()));
                    booking.setAssignedTechnicianId(parseUuidOrNull(res.getTechnicianId()));
                    booking.setAssignedTechnicianProfileId(res.getTechnicianProfileId());
                    booking.setAssignedTechnicianName(res.getTechnicianName());
                    booking.setStatus("ASSIGNED");
                    booking.setAllocationStatus("ALLOCATED");
                    booking.setUpdatedAt(now);
                    BookingEntity saved = bookingRepository.saveAndFlush(booking);
                    sagaRepository.findByBookingId(booking.getId()).ifPresent(saga -> {
                        saga.setSagaStatus("ALLOCATED");
                        saga.setCurrentStep("COMPLETED");
                        saga.setAssignedTechnicianId(saved.getAssignedTechnicianId());
                        saga.setFailureReason(null);
                        saga.setCompletedAt(now);
                        saga.setUpdatedAt(now);
                        sagaRepository.save(saga);
                    });
                    historyRepository.save(BookingHistoryEntry.builder()
                            .id(UUID.randomUUID())
                            .bookingId(booking.getId())
                            .fromStatus("REQUESTED")
                            .toStatus("ASSIGNED")
                            .actorId("RECOVERY_WORKER")
                            .reason("Reconciled technician allocation")
                            .occurredAt(now)
                            .build());
                    publishAssignedEvent(saved, now);
                    return false;
                });
                if (Boolean.TRUE.equals(needsCompensation)) {
                    recoverCompensationWithStatus(tx, attempt, "COMPENSATION_PENDING");
                }
            } else {
                String reason = res != null ? res.getFailureReason() : "ALLOCATION_FAILED";
                tx.executeWithoutResult(status -> {
                    Instant now = Instant.now();
                    int fenced = attemptRepository.transitionWithFence(
                            attempt.getOperationId(), attempt.getClaimToken(), expectedStatus,
                            "FAILED", reason, now);
                    if (fenced == 1) {
                        bookingRepository.findById(attempt.getBookingId()).ifPresent(b -> {
                            if (!"CANCELLED".equals(b.getStatus())) {
                                b.setAllocationStatus("UNALLOCATED");
                                b.setUpdatedAt(now);
                                bookingRepository.save(b);
                            }
                        });
                    }
                });
            }
        } catch (Exception ex) {
            handleRetryOrManualIntervention(tx, attempt, expectedStatus, "RECONCILIATION_PENDING", ex.getMessage());
        }
    }

    private void recoverCompensation(TransactionTemplate tx, AssignmentAttemptEntity attempt) {
        recoverCompensationWithStatus(tx, attempt, "COMPENSATION_PENDING");
    }

    private void recoverCompensationWithStatus(
            TransactionTemplate tx,
            AssignmentAttemptEntity attempt,
            String expectedStatus) {
        try {
            var rel = providerGrpcClient.releaseBooking(
                    attempt.getBookingId(), attempt.getOperationId(), "RECOVERY_COMPENSATION");
            if (rel != null && rel.getReleased()) {
                tx.executeWithoutResult(status -> {
                    Instant now = Instant.now();
                    int fenced = attemptRepository.transitionWithFence(
                            attempt.getOperationId(), attempt.getClaimToken(), expectedStatus,
                            "COMPENSATED", null, now);
                    if (fenced == 1) {
                        bookingRepository.findById(attempt.getBookingId()).ifPresent(b -> {
                            if (!"CANCELLED".equals(b.getStatus())) {
                                b.setStatus("REQUESTED");
                            }
                            b.setAllocationStatus("UNALLOCATED");
                            b.setUpdatedAt(now);
                            bookingRepository.save(b);
                        });
                    }
                });
                return;
            }
            handleRetryOrManualIntervention(tx, attempt, expectedStatus, "COMPENSATION_PENDING", "RELEASE_NOT_CONFIRMED");
        } catch (Exception ex) {
            handleRetryOrManualIntervention(tx, attempt, expectedStatus, "COMPENSATION_PENDING", ex.getMessage());
        }
    }

    private void handleRetryOrManualIntervention(
            TransactionTemplate tx,
            AssignmentAttemptEntity attempt,
            String expectedStatus,
            String retryStatus,
            String error) {
        int nextRetry = attempt.getRetryCount() + 1;
        Instant now = Instant.now();
        if (nextRetry >= MAX_RETRIES) {
            log.error("Assignment attempt {} for booking {} exhausted {} retries -> MANUAL_INTERVENTION. Last error: {}",
                    attempt.getOperationId(), attempt.getBookingId(), MAX_RETRIES, error);
            tx.executeWithoutResult(status -> {
                int fenced = attemptRepository.transitionWithFence(
                        attempt.getOperationId(), attempt.getClaimToken(), expectedStatus,
                        "MANUAL_INTERVENTION", error, now);
                if (fenced == 1) {
                    bookingRepository.findById(attempt.getBookingId()).ifPresent(b -> {
                        b.setAllocationStatus("MANUAL_INTERVENTION");
                        b.setUpdatedAt(now);
                        bookingRepository.save(b);
                    });
                }
            });
            return;
        }
        Instant nextAttemptAt = now.plusMillis(computeBackoffWithJitterMs(nextRetry));
        tx.executeWithoutResult(status -> {
            int fenced = attemptRepository.scheduleRetryWithFence(
                    attempt.getOperationId(), attempt.getClaimToken(), expectedStatus,
                    retryStatus, nextAttemptAt, error, now);
            if (fenced == 1) {
                bookingRepository.findById(attempt.getBookingId()).ifPresent(b -> {
                    b.setAllocationStatus(retryStatus);
                    b.setUpdatedAt(now);
                    bookingRepository.save(b);
                });
            }
        });
    }

    static long computeBackoffWithJitterMs(int retryCount) {
        long baseMs = Math.min(300_000L, 5_000L * (1L << Math.min(6, Math.max(0, retryCount - 1))));
        double jitterFactor = 0.8d + (ThreadLocalRandom.current().nextDouble() * 0.4d);
        return Math.max(1_000L, Math.round(baseMs * jitterFactor));
    }

    private void publishAssignedEvent(BookingEntity booking, Instant now) {
        try {
            UUID eventId = UUID.randomUUID();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("bookingId", booking.getId());
            data.put("requesterProfileId", booking.getRequesterProfileId());
            data.put("technicianProfileId", booking.getAssignedTechnicianProfileId());
            data.put("serviceName", booking.getServiceName());
            data.put("status", booking.getStatus());
            data.put("bookingType", booking.getBookingType());
            Map<String, Object> envelope = Map.of(
                    "eventId", eventId.toString(),
                    "eventType", "booking.assigned.v1",
                    "occurredAt", now.toString(),
                    "data", data);
            outboxEventRepository.save(BookingOutboxEventEntity.builder()
                    .id(eventId)
                    .aggregateType("Booking")
                    .aggregateId(booking.getId())
                    .eventType("booking.assigned.v1")
                    .routingKey("booking.assigned.v1")
                    .payloadJson(objectMapper.writeValueAsString(envelope))
                    .status("PENDING")
                    .retryCount(0)
                    .createdAt(now)
                    .build());
        } catch (Exception e) {
            log.error("Failed publishing reconciled booking.assigned.v1 for {}", booking.getId(), e);
        }
    }

    private UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
