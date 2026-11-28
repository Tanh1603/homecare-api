package com.homecare.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.homecare.common.error.DomainException;
import com.homecare.common.web.EtagSupport;
import com.homecare.review.client.EvidenceVerificationClient;
import com.homecare.review.client.EvidenceVerificationClient.BookingEligibility;
import com.homecare.review.dto.ReviewDtos.CreateServiceReviewRequest;
import com.homecare.review.dto.ReviewDtos.UpdateReviewRequest;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class ReviewLifecycleAndSyncPostgresIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired ReviewDomainService service;
    @Autowired ReviewRatingEventPublisher ratingEventPublisher;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean EvidenceVerificationClient verificationClient;
    @MockitoBean RabbitTemplate rabbitTemplate;

    @Test
    void enforcesD31LifecycleModerationConcurrencyAndNonRollbackOnBrokerOutage() throws Exception {
        UUID providerId = UUID.randomUUID();
        Instant now = Instant.now();

        // 1. Ineligible warranty rework & post-30-day window rejected
        UUID reworkBookingId = UUID.randomUUID();
        when(verificationClient.verifyBookingEligible(reworkBookingId, "cust-1"))
                .thenReturn(new BookingEligibility(false, providerId, "ACCEPTED", now.minus(1, ChronoUnit.DAYS), true));
        assertThat(service.checkServiceReviewEligibility("cust-1", reworkBookingId).reasonCode())
                .isEqualTo("WARRANTY_REWORK_INELIGIBLE");
        assertThatThrownBy(() -> service.createServiceReview(
                "cust-1", new CreateServiceReviewRequest(reworkBookingId, providerId, 5, "Rework")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("REVIEW_NOT_ELIGIBLE");

        UUID expiredBookingId = UUID.randomUUID();
        when(verificationClient.verifyBookingEligible(expiredBookingId, "cust-1"))
                .thenReturn(new BookingEligibility(true, providerId, "ACCEPTED", now.minus(32, ChronoUnit.DAYS), false));
        assertThat(service.checkServiceReviewEligibility("cust-1", expiredBookingId).reasonCode())
                .isEqualTo("WINDOW_EXPIRED");
        assertThatThrownBy(() -> service.createServiceReview(
                "cust-1", new CreateServiceReviewRequest(expiredBookingId, providerId, 5, "Too late")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("REVIEW_WINDOW_EXPIRED");

        // 2. Concurrent review creations for same provider produce exact aggregate (5 & 3 => 4.00, 2)
        UUID b1 = UUID.randomUUID();
        UUID b2 = UUID.randomUUID();
        when(verificationClient.verifyBookingEligible(b1, "cust-a"))
                .thenReturn(new BookingEligibility(true, providerId, "ACCEPTED", now.minus(1, ChronoUnit.DAYS), false));
        when(verificationClient.verifyBookingEligible(b2, "cust-b"))
                .thenReturn(new BookingEligibility(true, providerId, "ACCEPTED", now.minus(1, ChronoUnit.DAYS), false));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(1);
            Future<?> f1 = pool.submit(() -> {
                ready.await();
                return service.createServiceReview("cust-a", "An", new CreateServiceReviewRequest(b1, providerId, 5, "Tot"));
            });
            Future<?> f2 = pool.submit(() -> {
                ready.await();
                return service.createServiceReview("cust-b", "Binh", new CreateServiceReviewRequest(b2, providerId, 3, "Tam duoc"));
            });
            ready.countDown();
            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        var snapTwo = ratingEventPublisher.computeCommittedSnapshot(providerId);
        assertThat(snapTwo.ratingAvg()).isEqualTo(4.0);
        assertThat(snapTwo.reviewCount()).isEqualTo(2);

        // 3. Admin Hide excludes from public list & updates aggregate; hiding all -> 0.0 / 0; Restore restores
        var publicList = service.providerReviewsPage(providerId, 1, 10).items();
        assertThat(publicList).hasSize(2);
        var threeStar = publicList.stream().filter(r -> r.rating() == 3).findFirst().orElseThrow();
        var fiveStar = publicList.stream().filter(r -> r.rating() == 5).findFirst().orElseThrow();

        var hidden3 = service.adminHideServiceReview(
                "admin-1", threeStar.id(), EtagSupport.format(threeStar.version()), "Vi pham quy dinh noi dung");
        assertThat(hidden3.status()).isEqualTo("HIDDEN");
        assertThat(hidden3.hiddenBy()).isEqualTo("admin-1");
        assertThat(service.providerReviewsPage(providerId, 1, 10).items()).hasSize(1);
        var snapOne = ratingEventPublisher.computeCommittedSnapshot(providerId);
        assertThat(snapOne.ratingAvg()).isEqualTo(5.0);
        assertThat(snapOne.reviewCount()).isEqualTo(1);

        var hidden5 = service.adminHideServiceReview(
                "admin-1", fiveStar.id(), EtagSupport.format(fiveStar.version()), "Tam an kiem tra");
        var snapZero = ratingEventPublisher.computeCommittedSnapshot(providerId);
        assertThat(snapZero.ratingAvg()).isEqualTo(0.0);
        assertThat(snapZero.reviewCount()).isEqualTo(0);

        service.adminRestoreServiceReview("admin-1", hidden5.id(), EtagSupport.format(hidden5.version()), "Hop le");
        assertThat(ratingEventPublisher.computeCommittedSnapshot(providerId).reviewCount()).isEqualTo(1);

        // 4. Broker outage on after-commit publish MUST NOT roll back committed review, and Resync heals
        doThrow(new AmqpConnectException(new RuntimeException("Rabbit down")))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), anyString());

        UUID b3 = UUID.randomUUID();
        when(verificationClient.verifyBookingEligible(b3, "cust-c"))
                .thenReturn(new BookingEligibility(true, providerId, "ACCEPTED", now.minus(1, ChronoUnit.DAYS), false));
        var committedDespiteBrokerFailure = service.createServiceReview(
                "cust-c", new CreateServiceReviewRequest(b3, providerId, 4, "Rat tot"));
        assertThat(service.getServiceReviewByBooking("cust-c", b3).rating()).isEqualTo(4);

        var resync = service.adminResyncRatings(providerId);
        assertThat(resync.resyncedProviders()).isEqualTo(1);
        assertThat(resync.snapshots().getFirst().ratingAvg()).isEqualTo(4.5);
        assertThat(resync.snapshots().getFirst().reviewCount()).isEqualTo(2);

        // 5. Post-7-day edit window & permanent delete
        jdbcTemplate.update(
                "UPDATE reviews SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(now.minus(9, ChronoUnit.DAYS)),
                committedDespiteBrokerFailure.id());
        assertThatThrownBy(() -> service.updateServiceReview(
                "cust-c",
                committedDespiteBrokerFailure.id(),
                EtagSupport.format(committedDespiteBrokerFailure.version()),
                new UpdateReviewRequest(5, "Edit after 7 days")))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo("REVIEW_EDIT_WINDOW_EXPIRED");

        service.deleteServiceReview(
                "cust-c",
                committedDespiteBrokerFailure.id(),
                EtagSupport.format(committedDespiteBrokerFailure.version()));
        assertThat(service.checkServiceReviewEligibility("cust-c", b3).reasonCode())
                .isEqualTo("PERMANENTLY_DELETED");
    }
}
