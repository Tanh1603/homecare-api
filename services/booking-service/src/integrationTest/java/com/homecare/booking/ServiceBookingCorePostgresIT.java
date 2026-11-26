package com.homecare.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.homecare.booking.client.HouseholdVerificationClient;
import com.homecare.booking.client.ProviderGrpcClientAdapter;
import com.homecare.booking.entity.BookingEntity;
import com.homecare.booking.repository.BookingRepository;
import com.homecare.booking.repository.ServiceRequestRepository;
import com.homecare.booking.service.QuotationService;
import com.homecare.booking.service.QuotationService.CreateRevisedQuotationInput;
import com.homecare.booking.service.QuotationService.QuotationItemInput;
import com.homecare.booking.service.ServiceRequestService;
import com.homecare.booking.service.ServiceRequestService.CreateServiceRequestInput;
import com.homecare.booking.service.ServiceRequestTtlWorker;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "homecare.service-request.ttl-poll-ms=3600000"
})
class ServiceBookingCorePostgresIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired ServiceRequestService serviceRequestService;
    @Autowired QuotationService quotationService;
    @Autowired ServiceRequestTtlWorker ttlWorker;
    @Autowired ServiceRequestRepository serviceRequestRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired DataSource dataSource;

    @MockitoBean HouseholdVerificationClient householdClient;
    @MockitoBean ProviderGrpcClientAdapter providerGrpcClient;

    @BeforeEach
    void applyFeature3Migration() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(
                    conn,
                    new ClassPathResource("db/migration/V20261126_01__service_request_quotation_and_reschedule.sql"));
        }
    }

    @Test
    void verifiesFlywayConstraintsRevisedQuotationWithNullServiceRequestAndTtlWorker() {
        String customer = "pg-f3-" + UUID.randomUUID();
        UUID providerId = UUID.randomUUID();

        var sr = serviceRequestService.createRequest(
                customer, "PG Customer",
                new CreateServiceRequestInput("PAID_REPAIR", "AIR_CONDITIONER", null, "QUOTE_REQUIRED",
                        true, 700_000L, null, null, null, providerId, null, "100 Nguyễn Huệ", "Kêu to",
                        null, null, "CREDIT_FULL"));
        assertThat(sr.inspectionFeeWarningFlag()).isTrue();

        // DB CHECK constraints reject invalid policy & fallback_count > 3
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE service_requests SET inspection_fee_policy = 'BAD' WHERE id = ?", sr.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE service_requests SET fallback_count = 4 WHERE id = ?", sr.id()))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Revised Quotation succeeds on direct Booking with service_request_id = NULL
        BookingEntity directBooking = bookingRepository.saveAndFlush(BookingEntity.builder()
                .id(UUID.randomUUID())
                .requesterProfileId(customer)
                .serviceId(UUID.randomUUID())
                .serviceName("Sửa điều hòa")
                .providerId(providerId)
                .preferredStart(Instant.now())
                .preferredEnd(Instant.now().plus(1, ChronoUnit.HOURS))
                .address("100 Nguyễn Huệ")
                .status("IN_PROGRESS")
                .allocationStatus("ALLOCATED")
                .quotedPrice(300_000L)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());

        ActorContext orgActor = new ActorContext("mgr", AccessMode.ORGANIZATION, providerId, Set.of("CUSTOMER"), Set.of("ORG_OWNER"), Set.of(), Set.of());
        var rev = quotationService.issueRevisedQuotation(
                orgActor, directBooking.getId(),
                new CreateRevisedQuotationInput(
                        List.of(new QuotationItemInput("MATERIAL", "Tụ đề", 1, 150_000L)),
                        "Tụ đề cháy",
                        Instant.now().plus(48, ChronoUnit.HOURS)));
        assertThat(rev.serviceRequestId()).isNull();
        assertThat(rev.bookingId()).isEqualTo(directBooking.getId());

        // DB CHECK constraint rejects reschedule_count > 2
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE bookings SET reschedule_count = 3 WHERE id = ?", directBooking.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        // TTL worker transitions AWAITING_PROVIDER > 24h -> AWAITING_ALTERNATIVE (never CANCELLED)
        Instant future25h = Instant.now().plus(25, ChronoUnit.HOURS);
        var summary = ttlWorker.processTimeouts(future25h);
        assertThat(summary.providerTimeouts()).isGreaterThanOrEqualTo(1);
        assertThat(serviceRequestRepository.findById(sr.id()).orElseThrow().getStatus()).isEqualTo("AWAITING_ALTERNATIVE");

        // 6 days later -> warning, 7 days later -> EXPIRED
        var warnSummary = ttlWorker.processTimeouts(future25h.plus(6, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS));
        assertThat(warnSummary.expiryWarnings()).isGreaterThanOrEqualTo(1);
        var expireSummary = ttlWorker.processTimeouts(future25h.plus(7, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS));
        assertThat(expireSummary.expiredRequests()).isGreaterThanOrEqualTo(1);
        assertThat(serviceRequestRepository.findById(sr.id()).orElseThrow().getStatus()).isEqualTo("EXPIRED");
    }
}
