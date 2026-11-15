package com.homecare.commerce.service;

import com.homecare.commerce.repository.OrderRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UnpaidOrderExpiryJob {
    private final OrderRepository orders;
    private final CommerceDomainService service;
    @Scheduled(fixedDelayString = "${homecare.orders.expiry-poll-ms:60000}", initialDelayString = "${homecare.orders.expiry-poll-ms:60000}")
    public void expire() {
        Instant now = Instant.now();
        for (var order : orders.findByStatusAndQuoteExpiresAtBefore("AWAITING_PAYMENT", now, PageRequest.of(0, 100))) {
            service.expireOrder(order.getId(), now);
        }
    }
}
