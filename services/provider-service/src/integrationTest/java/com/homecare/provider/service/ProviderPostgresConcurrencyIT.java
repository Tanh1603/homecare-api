package com.homecare.provider.service;

import static java.time.temporal.ChronoUnit.DAYS;
import static java.time.temporal.ChronoUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.provider.dto.ProviderDtos.CreateScheduleWindowRequest;
import com.homecare.provider.entity.TechnicianAllocation;
import com.homecare.provider.organization.entity.OrgRole;
import com.homecare.provider.organization.entity.OrganizationMemberEntity;
import com.homecare.provider.organization.repository.OrganizationMemberRepository;
import com.homecare.provider.repository.TechnicianAllocationRepository;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class ProviderPostgresConcurrencyIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    ProviderDomainService service;

    @Autowired
    TechnicianAllocationRepository allocationRepository;

    @Autowired
    OrganizationMemberRepository organizationMemberRepository;

    @BeforeAll
    static void migrate(@Autowired DataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    @Test
    void enforcesPartialUniqueActiveBookingIndexAndTombstoneOnRealPostgres() {
        Instant start = Instant.now().truncatedTo(SECONDS).plus(70, DAYS);
        Instant end = start.plusSeconds(1800);
        service.createScheduleWindow("me",
                new ActorContext("demo-technician", AccessMode.PERSONAL, null, Set.of(), Set.of(), Set.of(), Set.of()),
                new CreateScheduleWindowRequest(start, start.plusSeconds(3600)));

        UUID bookingId = UUID.randomUUID();
        UUID op1 = UUID.randomUUID();
        var outcome = service.allocateTechnicianForBooking(op1, "op-" + op1, bookingId,
                ProviderDataInitializer.SERVICE_AC_CLEAN_ID, "AC", start, end,
                ProviderDataInitializer.PROVIDER_1_ID, true, ProviderDataInitializer.DEMO_TECH_ID);
        assertThat(outcome.allocated()).isTrue();

        assertThatThrownBy(() -> allocationRepository.saveAndFlush(TechnicianAllocation.builder()
                .id(UUID.randomUUID())
                .intentId("dup-" + UUID.randomUUID())
                .operationId(UUID.randomUUID())
                .bookingId(bookingId)
                .technicianId(ProviderDataInitializer.DEMO_TECH_ID)
                .providerId(ProviderDataInitializer.PROVIDER_1_ID)
                .startsAt(start)
                .endsAt(end)
                .status("ALLOCATED")
                .createdAt(Instant.now())
                .build()))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID tombstoneOp = UUID.randomUUID();
        assertThat(service.releaseBookingByOperation(bookingId, tombstoneOp, "TIMEOUT_TOMBSTONE").released()).isTrue();
        var late = service.allocateTechnicianForBooking(tombstoneOp, "op-" + tombstoneOp, bookingId,
                ProviderDataInitializer.SERVICE_AC_CLEAN_ID, "AC", start, end,
                ProviderDataInitializer.PROVIDER_1_ID, true, ProviderDataInitializer.DEMO_TECH_ID);
        assertThat(late.allocated()).isFalse();
        assertThat(late.failureReason()).isEqualTo("OPERATION_ALREADY_RELEASED");
    }

    @Test
    void enforcesOneToOneActiveTechnicianPartialUniqueIndexOnRealPostgres() {
        String techUserId = "pg-tech-" + UUID.randomUUID();
        Instant now = Instant.now();
        organizationMemberRepository.saveAndFlush(OrganizationMemberEntity.builder()
                .id(UUID.randomUUID())
                .organizationId(UUID.randomUUID())
                .userId(techUserId)
                .role(OrgRole.ORG_TECHNICIAN)
                .status("ACTIVE")
                .joinedAt(now)
                .createdAt(now)
                .build());

        assertThatThrownBy(() -> organizationMemberRepository.saveAndFlush(OrganizationMemberEntity.builder()
                .id(UUID.randomUUID())
                .organizationId(UUID.randomUUID())
                .userId(techUserId)
                .role(OrgRole.ORG_TECHNICIAN)
                .status("ACTIVE")
                .joinedAt(now)
                .createdAt(now)
                .build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
