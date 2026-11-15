package com.homecare.commerce.service;

import static io.grpc.Status.UNAVAILABLE;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.homecare.commerce.client.ProviderOrgClient;
import com.homecare.commerce.dto.CommerceDtos.*;
import com.homecare.commerce.repository.OrderRepository;
import com.homecare.provider.v1.ProviderAllocationServiceGrpc.ProviderAllocationServiceBlockingStub;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
class CommercePortalTest {
    @Autowired WebApplicationContext context;
    @Autowired CommerceDomainService service;
    @Autowired OrderRepository orders;
    @Autowired UnpaidOrderExpiryJob expiry;
    @Autowired VnpaySandboxStrategy vnpay;
    @MockitoBean ProviderOrgClient membership;
    MockMvc mvc;
    @BeforeEach void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }
    @Test void productsUseDbPagination() throws Exception {
        mvc.perform(get("/api/v1/products?size=1&minPrice=0&inStock=true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.pagination.totalItems").value(Matchers.greaterThan(1)));
    }

    @Test void membershipOutageReturnsServiceUnavailable() throws Exception {
        var stub = Mockito.mock(ProviderAllocationServiceBlockingStub.class,
                RETURNS_SELF);
        Mockito.when(stub.verifyOrganizationMembership(ArgumentMatchers.any()))
                .thenThrow(UNAVAILABLE.asRuntimeException());
        var client = new ProviderOrgClient();
        ReflectionTestUtils.setField(client, "stub", stub);
        ReflectionTestUtils.setField(client, "workloadToken", "test");
        var merchant = service.getProduct(CommerceDataInitializer.PRODUCT_AC_FILTER_ID).merchantId();
        Mockito.when(membership.canOperate(merchant, "outage-staff"))
                .thenAnswer(invocation -> client.canOperate(merchant, "outage-staff"));
        mvc.perform(get("/api/v1/orders").header("X-Organization-Id", merchant)
                .with(jwt().jwt(j -> j.subject("outage-staff").claim("user_roles", List.of("ORGANIZATION")))))
                .andExpect(status().isServiceUnavailable());
    }
    @Test void cancellationRestoresStockExactlyOnceAndRejectsForeignBuyer() throws Exception {
        var product = CommerceDataInitializer.PRODUCT_AC_FILTER_ID;
        int initial = service.getProduct(product).availableQty();
        String user = "cancel-" + UUID.randomUUID();
        var order = service.createOrder(user, new CreateOrderRequest(List.of(new OrderLineRequest(product, 2)), "Address"));
        String path = "/api/v1/orders/" + order.id() + "/cancel";
        mvc.perform(post(path).with(jwt().jwt(j -> j.subject("outsider")))).andExpect(status().isForbidden());
        mvc.perform(post(path).with(jwt().jwt(j -> j.subject(user)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        mvc.perform(post(path).with(jwt().jwt(j -> j.subject(user)))).andExpect(status().isOk());
        assertThat(service.getProduct(product).availableQty()).isEqualTo(initial);
    }
    @Test void customerCannotCreateProducts() throws Exception {
        mvc.perform(post("/api/v1/products").with(jwt()).contentType("application/json").content("""
                {"merchantId":"11111111-1111-1111-1111-111111111111","sku":"TEST","name":"Test","category":"AC","brand":"Test","unitPrice":100}
                """)).andExpect(status().isForbidden());
    }

    @Test void organizationStaffCrudAndInventoryAreTenantScoped() throws Exception {
        var merchant = service.getProduct(CommerceDataInitializer.PRODUCT_AC_FILTER_ID).merchantId();
        Mockito.when(membership.canOperate(merchant, "staff")).thenReturn(true);
        var auth = jwt().jwt(j -> j.subject("staff").claim("user_roles", List.of("ORGANIZATION")));
        String body = """
            {"merchantId":"%s","sku":"%s","name":"Portal product","category":"AC","brand":"Brand","unitPrice":500}
            """.formatted(merchant, UUID.randomUUID());
        var created = mvc.perform(post("/api/v1/products").with(auth).header("X-Organization-Id", merchant)
                .contentType("application/json").content(body)).andExpect(status().isCreated()).andReturn();
        var id = new ObjectMapper().readTree(created.getResponse().getContentAsString()).path("data").path("id").asText();
        mvc.perform(patch("/api/v1/products/" + id + "/inventory").with(auth).header("X-Organization-Id", merchant)
                .contentType("application/json").content("{\"availableQty\":8}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.availableQty").value(8));
        mvc.perform(patch("/api/v1/products/" + id).with(auth).header("X-Organization-Id", UUID.randomUUID())
                .contentType("application/json").content("{\"unitPrice\":900}")).andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/products/" + id).with(auth).header("X-Organization-Id", merchant)
                .contentType("application/json").content("{\"unitPrice\":900}")).andExpect(status().isOk()).andExpect(jsonPath("$.data.unitPrice").value(900));
        mvc.perform(delete("/api/v1/products/" + id).with(auth).header("X-Organization-Id", merchant)).andExpect(status().isNoContent());
    }

    @Test void expiryRestoresOnlyExpiredUnpaidStockAndLatePaymentCannotConfirmIt() {
        var product = CommerceDataInitializer.PRODUCT_AC_FILTER_ID;
        int initial = service.getProduct(product).availableQty();
        String user = "expire-" + UUID.randomUUID();
        var order = service.createOrder(user, new CreateOrderRequest(List.of(new OrderLineRequest(product, 1)), "Address"));
        var attempt = service.createPaymentAttempt(user, order.id());
        var stored = orders.findById(order.id()).orElseThrow();
        stored.setQuoteExpiresAt(Instant.now().minusSeconds(1)); orders.saveAndFlush(stored);
        expiry.expire(); expiry.expire();
        assertThat(service.getProduct(product).availableQty()).isEqualTo(initial);
        var params = new HashMap<String, String>();
        params.put("vnp_TxnRef", attempt.txnRef()); params.put("vnp_Amount", Long.toString(attempt.amount() * 100));
        params.put("vnp_ResponseCode", "00"); params.put("vnp_TmnCode", "HOMECARE01");
        params.put("vnp_SecureHash", vnpay.signParamsForTest(params));
        assertThat(service.handleVnpayIpn(params).get("RspCode")).isEqualTo("02");
        assertThat(service.getOrder(user, order.id()).status()).isEqualTo("CANCELLED");
        assertThat(service.getProduct(product).availableQty()).isEqualTo(initial);
    }
}
