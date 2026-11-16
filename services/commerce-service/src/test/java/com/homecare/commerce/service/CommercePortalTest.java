package com.homecare.commerce.service;

import static io.grpc.Status.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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

import com.homecare.commerce.client.ProviderOrgClient;
import com.homecare.commerce.dto.CommerceDtos.CreateOrderRequest;
import com.homecare.commerce.dto.CommerceDtos.OrderLineRequest;
import com.homecare.commerce.repository.OrderRepository;
import com.homecare.common.security.VerifiedMembership;
import com.homecare.provider.v1.ProviderAllocationServiceGrpc.ProviderAllocationServiceBlockingStub;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
class CommercePortalTest {
    @Autowired
    WebApplicationContext context;
    @Autowired
    CommerceDomainService service;
    @Autowired
    CommerceOrganizationConsumer consumer;
    @Autowired
    OrderRepository orders;
    @Autowired
    UnpaidOrderExpiryJob expiry;
    @Autowired
    VnpaySandboxStrategy vnpay;
    @MockitoBean
    ProviderOrgClient membership;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void productsUseDbPagination() throws Exception {
        mvc.perform(get("/api/v1/products?size=1&minPrice=0&inStock=true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.pagination.totalItems").value(Matchers.greaterThan(1)));
    }

    @Test
    void membershipOutageReturnsServiceUnavailable() throws Exception {
        var stub = Mockito.mock(ProviderAllocationServiceBlockingStub.class,
                RETURNS_SELF);
        Mockito.when(stub.verifyOrganizationMembership(ArgumentMatchers.any()))
                .thenThrow(UNAVAILABLE.asRuntimeException());
        var client = new ProviderOrgClient();
        ReflectionTestUtils.setField(client, "stub", stub);
        ReflectionTestUtils.setField(client, "workloadToken", "test");
        var merchant = service.getProduct(CommerceDataInitializer.PRODUCT_AC_FILTER_ID).merchantId();
        Mockito.when(membership.verify(merchant, "outage-staff"))
                .thenAnswer(invocation -> client.verify(merchant, "outage-staff"));
        mvc.perform(get("/api/v1/orders").header("X-Organization-Id", merchant)
                .with(jwt().jwt(j -> j.subject("outage-staff").claim("user_roles", List.of("ORGANIZATION")))))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void cancellationRestoresStockExactlyOnceAndRejectsForeignBuyer() throws Exception {
        var product = CommerceDataInitializer.PRODUCT_AC_FILTER_ID;
        int initial = service.getProduct(product).availableQty();
        String user = "cancel-" + UUID.randomUUID();
        var order = service.createOrder(user,
                new CreateOrderRequest(List.of(new OrderLineRequest(product, 2)), "Address"));
        String path = "/api/v1/orders/" + order.id() + "/cancel";
        mvc.perform(post(path).with(jwt().jwt(j -> j.subject("outsider")))).andExpect(status().isForbidden());
        mvc.perform(post(path).with(jwt().jwt(j -> j.subject(user)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        mvc.perform(post(path).with(jwt().jwt(j -> j.subject(user)))).andExpect(status().isOk());
        assertThat(service.getProduct(product).availableQty()).isEqualTo(initial);
    }

    @Test
    void newlyApprovedOrganizationCanCreateAllProductTypes() throws Exception {
        UUID org = UUID.randomUUID();
        consumer.onOrganizationEvent(
                """
                        {"eventId":"%s","eventType":"organization.approved.v1","data":{"organizationId":"%s","name":"New Shop","type":"COMMERCE","ownerUserId":"new-owner"}}
                        """
                        .formatted(UUID.randomUUID(), org).strip());
        Mockito.when(membership.verify(org, "new-owner"))
                .thenReturn(VerifiedMembership.of(org, "new-owner", "ORG_OWNER", true));
        var auth = jwt().jwt(j -> j.subject("new-owner").claim("user_roles", List.of("ORGANIZATION")));
        for (String type : List.of("MAJOR_PART", "SUPPLY", "TOOL")) {
            mvc.perform(post("/api/v1/products").with(auth).header("X-Organization-Id", org)
                    .contentType("application/json").content(
                            """
                                    {"merchantId":"%s","sku":"%s","name":"P","category":"AC","brand":"B","unitPrice":100,"productType":"%s"}
                                    """
                                    .formatted(org, UUID.randomUUID(), type)))
                    .andExpect(status().isCreated());
        }
    }

    @Test
    void customerCannotCreateProducts() throws Exception {
        mvc.perform(post("/api/v1/products").with(jwt()).contentType("application/json").content(
                """
                        {"merchantId":"11111111-1111-1111-1111-111111111111","sku":"TEST","name":"Test","category":"AC","brand":"Test","unitPrice":100}
                        """))
                .andExpect(status().isForbidden());
    }

    @Test
    void organizationStaffCrudAndInventoryAreTenantScoped() throws Exception {
        var merchant = service.getProduct(CommerceDataInitializer.PRODUCT_AC_FILTER_ID).merchantId();
        Mockito.when(membership.verify(merchant, "staff"))
                .thenReturn(VerifiedMembership.of(merchant, "staff", "ORG_ADMIN", true));
        var auth = jwt().jwt(j -> j.subject("staff").claim("user_roles", List.of("ORGANIZATION")));
        String body = """
                {"merchantId":"%s","sku":"%s","name":"Portal product","category":"AC","brand":"Brand","unitPrice":500}
                """.formatted(merchant, UUID.randomUUID());
        var created = mvc.perform(post("/api/v1/products").with(auth).header("X-Organization-Id", merchant)
                .contentType("application/json").content(body)).andExpect(status().isCreated()).andReturn();
        var id = new ObjectMapper().readTree(created.getResponse().getContentAsString()).path("data").path("id")
                .asString();
        mvc.perform(patch("/api/v1/products/" + id + "/inventory").with(auth).header("X-Organization-Id", merchant)
                .contentType("application/json").content("{\"availableQty\":8}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableQty").value(8));
        mvc.perform(patch("/api/v1/products/" + id).with(auth).header("X-Organization-Id", UUID.randomUUID())
                .contentType("application/json").content("{\"unitPrice\":900}")).andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/products/" + id).with(auth).header("X-Organization-Id", merchant)
                .contentType("application/json").content("{\"unitPrice\":900}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unitPrice").value(900));
        mvc.perform(delete("/api/v1/products/" + id).with(auth).header("X-Organization-Id", merchant))
                .andExpect(status().isNoContent());
    }

    @Test
    void expiryRestoresOnlyExpiredUnpaidStockAndLatePaymentCannotConfirmIt() {
        var product = CommerceDataInitializer.PRODUCT_AC_FILTER_ID;
        int initial = service.getProduct(product).availableQty();
        String user = "expire-" + UUID.randomUUID();
        var order = service.createOrder(user,
                new CreateOrderRequest(List.of(new OrderLineRequest(product, 1)), "Address"));
        var attempt = service.createPaymentAttempt(user, order.id());
        var stored = orders.findById(order.id()).orElseThrow();
        stored.setQuoteExpiresAt(Instant.now().minusSeconds(1));
        orders.saveAndFlush(stored);
        expiry.expire();
        expiry.expire();
        assertThat(service.getProduct(product).availableQty()).isEqualTo(initial);
        var params = new HashMap<String, String>();
        params.put("vnp_TxnRef", attempt.txnRef());
        params.put("vnp_Amount", Long.toString(attempt.amount() * 100));
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_TmnCode", "HOMECARE01");
        params.put("vnp_SecureHash", vnpay.signParamsForTest(params));
        assertThat(service.handleVnpayIpn(params).get("RspCode")).isEqualTo("02");
        assertThat(service.getOrder(user, order.id()).status()).isEqualTo("CANCELLED");
        assertThat(service.getProduct(product).availableQty()).isEqualTo(initial);
    }

    @Test
    void orgOperatorWithoutOrgHeaderUsesPersonalOrderAndReturnScope() throws Exception {
        String user = "org-buyer-" + UUID.randomUUID();
        var product = CommerceDataInitializer.PRODUCT_AC_FILTER_ID;
        var ownOrder = service.createOrder(user,
                new CreateOrderRequest(List.of(new OrderLineRequest(product, 1)), "Address"));
        var otherOrder = service.createOrder("other-buyer-" + UUID.randomUUID(),
                new CreateOrderRequest(List.of(new OrderLineRequest(product, 1)), "Address"));
        var orgAuth = jwt().jwt(j -> j.subject(user).claim("user_roles", List.of("ORGANIZATION")));

        mvc.perform(get("/api/v1/orders").with(orgAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(ownOrder.id().toString()));
        mvc.perform(get("/api/v1/orders/" + ownOrder.id() + "/returns").with(orgAuth))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/orders/" + otherOrder.id() + "/returns").with(orgAuth))
                .andExpect(status().isForbidden());
    }

    @Test
    void separatesOrderReadFromFulfillAndAllowsNonOwnerOrgStaffWithinOwnOrg() throws Exception {
        var product = CommerceDataInitializer.PRODUCT_AC_FILTER_ID;
        var merchantOrgId = service.getProduct(product).merchantId();
        var order = service.createOrder("buyer-" + UUID.randomUUID(),
                new CreateOrderRequest(List.of(new OrderLineRequest(product, 1)), "123 Le Loi"));
        UUID otherOrgId = UUID.randomUUID();
        String staffUser = "non-owner-staff-" + UUID.randomUUID();

        // Fulfill-only permission without order:read cannot read orders
        Mockito.when(membership.verify(merchantOrgId, "fulfill-only"))
                .thenReturn(VerifiedMembership.of(merchantOrgId, "fulfill-only", "ORG_STAFF",
                        java.util.Set.of("order:fulfill"), true));
        mvc.perform(get("/api/v1/orders").header("X-Organization-Id", merchantOrgId)
                        .with(jwt().jwt(j -> j.subject("fulfill-only").claim("user_roles", List.of("ORGANIZATION")))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/orders/" + order.id()).header("X-Organization-Id", merchantOrgId)
                        .with(jwt().jwt(j -> j.subject("fulfill-only").claim("user_roles", List.of("ORGANIZATION")))))
                .andExpect(status().isNotFound());

        // Non-owner ORG_STAFF with verified membership reads org orders and is blocked cross-org
        Mockito.when(membership.verify(merchantOrgId, staffUser))
                .thenReturn(VerifiedMembership.of(merchantOrgId, staffUser, "ORG_STAFF", true));
        mvc.perform(get("/api/v1/orders/" + order.id()).header("X-Organization-Id", merchantOrgId)
                        .with(jwt().jwt(j -> j.subject(staffUser).claim("user_roles", List.of("CUSTOMER")))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/orders/" + order.id()).header("X-Organization-Id", otherOrgId)
                        .with(jwt().jwt(j -> j.subject(staffUser).claim("user_roles", List.of("ORGANIZATION")))))
                .andExpect(status().isForbidden());

        // ORG_STAFF without product:manage cannot create product or update inventory, ORG_OWNER can
        mvc.perform(post("/api/v1/products").header("X-Organization-Id", merchantOrgId)
                        .with(jwt().jwt(j -> j.subject(staffUser).claim("user_roles", List.of("ORGANIZATION"))))
                        .contentType("application/json")
                        .content("""
                                {"merchantId":"%s","sku":"SKU-STAFF","name":"Staff Try","category":"AC","brand":"B","unitPrice":100}
                                """.formatted(merchantOrgId)))
                .andExpect(status().isForbidden());
        Mockito.when(membership.verify(merchantOrgId, "owner-user"))
                .thenReturn(VerifiedMembership.of(merchantOrgId, "owner-user", "ORG_OWNER", true));
        mvc.perform(post("/api/v1/products").header("X-Organization-Id", merchantOrgId)
                        .with(jwt().jwt(j -> j.subject("owner-user").claim("user_roles", List.of("ORGANIZATION"))))
                        .contentType("application/json")
                        .content("""
                                {"merchantId":"%s","sku":"SKU-OWNER","name":"Owner Item","category":"AC","brand":"B","unitPrice":100}
                                """.formatted(merchantOrgId)))
                .andExpect(status().isCreated());

        // Order fulfill / return-resolve / cancel policy checks
        Mockito.when(membership.verify(merchantOrgId, "tech-user"))
                .thenReturn(VerifiedMembership.of(merchantOrgId, "tech-user", "ORG_TECHNICIAN", true));
        UUID lineId = order.lines().getFirst().id();
        mvc.perform(post("/api/v1/orders/" + order.id() + "/lines/" + lineId + "/fulfillment")
                        .header("X-Organization-Id", merchantOrgId)
                        .with(jwt().jwt(j -> j.subject("tech-user").claim("user_roles", List.of("ORGANIZATION")))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/orders/" + order.id() + "/cancel")
                        .header("X-Organization-Id", merchantOrgId)
                        .with(jwt().jwt(j -> j.subject("tech-user").claim("user_roles", List.of("ORGANIZATION")))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/orders/" + order.id() + "/cancel")
                        .header("X-Organization-Id", otherOrgId)
                        .with(jwt().jwt(j -> j.subject(staffUser).claim("user_roles", List.of("ORGANIZATION")))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/orders/" + order.id() + "/cancel")
                        .header("X-Organization-Id", merchantOrgId)
                        .with(jwt().jwt(j -> j.subject(staffUser).claim("user_roles", List.of("ORGANIZATION")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }
}
