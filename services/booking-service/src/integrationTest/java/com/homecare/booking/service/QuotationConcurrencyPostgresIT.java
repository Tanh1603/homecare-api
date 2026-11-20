package com.homecare.booking.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.homecare.booking.repository.BookingRepository;
import com.homecare.booking.service.QuotationService.AcceptQuotationInput;
import com.homecare.booking.service.QuotationService.CreateQuotationInput;
import com.homecare.booking.service.QuotationService.QuotationItemInput;
import com.homecare.booking.service.QuotationService.QuotationResponse;
import com.homecare.booking.service.ServiceRequestService.CreateServiceRequestInput;
import com.homecare.booking.service.ServiceRequestService.ServiceRequestResponse;
import com.homecare.common.error.DomainException;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class QuotationConcurrencyPostgresIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ServiceRequestService serviceRequestService;

    @Autowired
    private QuotationService quotationService;

    @Autowired
    private BookingRepository bookingRepository;

    @Test
    void concurrentAcceptOnSameQuotationCreatesExactlyOneRepairBooking() throws Exception {
        String customer = "cust-concurrent-" + UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        ActorContext providerActor = new ActorContext(
                "staff-1", AccessMode.ORGANIZATION, providerId, Set.of(), Set.of("ORG_ADMIN"), Set.of(), Set.of());

        ServiceRequestResponse req = serviceRequestService.createRequest(
                customer,
                "Khách Hàng",
                new CreateServiceRequestInput(
                        "PAID_REPAIR",
                        "AC",
                        null,
                        "QUOTE_REQUIRED",
                        false,
                        0L,
                        null,
                        null,
                        null,
                        providerId,
                        null,
                        "100 Điện Biên Phủ, TP. Hồ Chí Minh",
                        "Sửa bo mạch",
                        null,
                        null));
        serviceRequestService.providerAccept(providerActor, req.id(), 0L);

        QuotationResponse quote = quotationService.issueQuotation(
                providerActor,
                req.id(),
                new CreateQuotationInput(
                        List.of(new QuotationItemInput("LABOR", "Sửa bo mạch inverter", 1, 450_000L)),
                        Instant.now().plus(2, ChronoUnit.DAYS),
                        true));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Void> task = () -> {
            ready.countDown();
            start.await();
            try {
                quotationService.acceptQuotation(customer, req.id(), quote.id(), new AcceptQuotationInput(1));
                successCount.incrementAndGet();
            } catch (DomainException e) {
                if ("CONFLICT".equals(e.errorCode())) {
                    conflictCount.incrementAndGet();
                } else {
                    throw e;
                }
            }
            return null;
        };

        Future<Void> f1 = pool.submit(task);
        Future<Void> f2 = pool.submit(task);
        ready.await();
        start.countDown();
        f1.get();
        f2.get();
        pool.shutdown();

        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        ServiceRequestResponse finalReq = serviceRequestService.getRequest(
                new ActorContext(customer, AccessMode.PERSONAL, null, Set.of(), Set.of(), Set.of(), Set.of()),
                req.id());
        assertThat(finalReq.status()).isEqualTo("BOOKED");
        assertThat(finalReq.repairBookingId()).isNotNull();
        assertThat(bookingRepository.findById(finalReq.repairBookingId())).isPresent();
    }
}
