package com.homecare.household.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.homecare.household.client.ProviderOrgClient;
import com.homecare.household.dto.HouseholdDtos.CreateHouseholdRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class HouseholdTenantIsolationPostgresIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    HouseholdDomainService service;

    @MockitoBean
    ProviderOrgClient providerOrgClient;

    @Test
    void isolatesOwnerHouseholdsOnPostgres() {
        service.createHousehold("pg-hh-1", new CreateHouseholdRequest("Home 1", "Addr 1", null));
        service.createHousehold("pg-hh-2", new CreateHouseholdRequest("Home 2", "Addr 2", null));

        var page = service.householdsPage("pg-hh-1", 1, 20);
        assertThat(page.items()).hasSize(1);
        assertThat(page.pagination().totalItems()).isEqualTo(1);
    }
}
