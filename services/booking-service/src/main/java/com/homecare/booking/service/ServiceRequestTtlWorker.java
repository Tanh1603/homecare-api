package com.homecare.booking.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.booking.entity.QuotationEntity;
import com.homecare.booking.entity.ServiceRequestEntity;
import com.homecare.booking.entity.WorkUpdateEntity;
import com.homecare.booking.repository.QuotationRepository;
import com.homecare.booking.repository.ServiceRequestRepository;
import com.homecare.booking.repository.WorkUpdateRepository;
import com.homecare.common.event.DomainEventContracts;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Enforces D-23 / BR-SR-03 TTL transitions idempotently via FOR UPDATE SKIP LOCKED:
 * 1) AWAITING_PROVIDER > 24h -> AWAITING_ALTERNATIVE (never auto-cancels).
 * 2) SENT Quotations past validUntil -> EXPIRED (and moves INITIAL request to AWAITING_ALTERNATIVE;
 *    and unblocks linked REVISED WorkUpdate extraCostStatus).
 * 3) Idle AWAITING_ALTERNATIVE / QUOTATION_REJECTED >= 6d -> 24h expiry warning; >= 7d -> EXPIRED.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ServiceRequestTtlWorker {

    private static final int BATCH_SIZE = 50;

    private final ServiceRequestRepository requestRepository;
    private final QuotationRepository quotationRepository;
    private final WorkUpdateRepository workUpdateRepository;
    private final ServiceRequestService serviceRequestService;

    public record TtlRunSummary(int providerTimeouts, int expiredQuotations, int expiryWarnings, int expiredRequests) {
    }

    @Scheduled(fixedDelayString = "${homecare.service-request.ttl-poll-ms:60000}")
    public void scheduledRun() {
        processTimeouts(Instant.now());
    }

    @Transactional
    public TtlRunSummary processTimeouts(Instant now) {
        int providerTimeouts = handleProviderTimeouts(now);
        int expiredQuotations = handleExpiredQuotations(now);
        int expiryWarnings = handleExpiryWarnings(now);
        int expiredRequests = handleIdleExpirations(now);
        return new TtlRunSummary(providerTimeouts, expiredQuotations, expiryWarnings, expiredRequests);
    }

    private int handleProviderTimeouts(Instant now) {
        Instant cutoff = now.minus(24, ChronoUnit.HOURS);
        List<ServiceRequestEntity> timedOut = requestRepository.lockTimedOutAwaitingProvider(cutoff, BATCH_SIZE);
        for (ServiceRequestEntity req : timedOut) {
            req.setStatus("AWAITING_ALTERNATIVE");
            req.setFallbackReason("PROVIDER_TIMEOUT_24H");
            req.setFallbackNotifiedAt(now);
            req.setStatusChangedAt(now);
            req.setExpiryWarningNotifiedAt(null);
            req.setUpdatedAt(now);
            requestRepository.save(req);
            serviceRequestService.publishEvent(DomainEventContracts.SERVICE_REQUEST_TIMEOUT, req, now);
        }
        return timedOut.size();
    }

    private int handleExpiredQuotations(Instant now) {
        List<QuotationEntity> expired = quotationRepository.lockExpiredSentQuotations(now, BATCH_SIZE);
        for (QuotationEntity quote : expired) {
            quote.setStatus("EXPIRED");
            quote.setUpdatedAt(now);
            quotationRepository.save(quote);
            if (QuotationService.QUOTE_KIND_INITIAL.equals(quote.getQuoteKind()) && quote.getServiceRequestId() != null) {
                requestRepository.lockById(quote.getServiceRequestId()).ifPresent(req -> {
                    if ("QUOTATION_SENT".equals(req.getStatus())) {
                        req.setStatus("AWAITING_ALTERNATIVE");
                        req.setFallbackReason("QUOTATION_EXPIRED");
                        req.setStatusChangedAt(now);
                        req.setExpiryWarningNotifiedAt(null);
                        req.setUpdatedAt(now);
                        requestRepository.save(req);
                    }
                });
            } else if (QuotationService.QUOTE_KIND_REVISED.equals(quote.getQuoteKind()) && quote.getBookingId() != null) {
                for (WorkUpdateEntity wu : workUpdateRepository.findByBookingIdOrderByCreatedAtAsc(quote.getBookingId())) {
                    if (quote.getId().equals(wu.getRevisedQuotationId())
                            && "PENDING_APPROVAL".equals(wu.getExtraCostStatus())) {
                        wu.setExtraCostStatus("EXPIRED");
                        workUpdateRepository.save(wu);
                    }
                }
            }
        }
        return expired.size();
    }

    private int handleExpiryWarnings(Instant now) {
        Instant warningCutoff = now.minus(6, ChronoUnit.DAYS);
        List<ServiceRequestEntity> dueForWarning = requestRepository.lockDueForExpiryWarning(warningCutoff, BATCH_SIZE);
        for (ServiceRequestEntity req : dueForWarning) {
            req.setExpiryWarningNotifiedAt(now);
            req.setUpdatedAt(now);
            requestRepository.save(req);
            serviceRequestService.publishEvent(DomainEventContracts.SERVICE_REQUEST_EXPIRY_WARNING, req, now);
        }
        return dueForWarning.size();
    }

    private int handleIdleExpirations(Instant now) {
        Instant expiryCutoff = now.minus(7, ChronoUnit.DAYS);
        List<ServiceRequestEntity> expired = requestRepository.lockIdleExpired(expiryCutoff, BATCH_SIZE);
        for (ServiceRequestEntity req : expired) {
            req.setStatus("EXPIRED");
            req.setStatusChangedAt(now);
            req.setUpdatedAt(now);
            requestRepository.save(req);
            serviceRequestService.publishEvent(DomainEventContracts.SERVICE_REQUEST_EXPIRED, req, now);
        }
        return expired.size();
    }
}
