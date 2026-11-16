package com.homecare.commerce.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.homecare.commerce.client.ProviderOrgClient;
import com.homecare.commerce.dto.CommerceDtos.CreateOrderRequest;
import com.homecare.commerce.dto.CommerceDtos.OrderLineRequest;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.common.security.RbacConstants;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
class CommerceTenantIsolationPostgresIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    CommerceDomainService service;

    @MockitoBean
    ProviderOrgClient providerOrgClient;

    @Test
    void isolatesBuyerOrdersAndPaginationCountsOnPostgres() {
        UUID product = CommerceDataInitializer.PRODUCT_AC_FILTER_ID;
        service.createOrder("pg-buyer-1", new CreateOrderRequest(List.of(new OrderLineRequest(product, 1)), "Addr 1"));
        service.createOrder("pg-buyer-2", new CreateOrderRequest(List.of(new OrderLineRequest(product, 1)), "Addr 2"));

        ActorContext buyer1 = new ActorContext("pg-buyer-1", AccessMode.PERSONAL, null,
                Set.of(RbacConstants.ROLE_CUSTOMER), Set.of(), Set.of(), Set.of());
        var page = service.ordersPage(buyer1, null, null, null, 1, 20);
        assertThat(page.items()).hasSize(1);
        assertThat(page.pagination().totalItems()).isEqualTo(1);
    }
}
