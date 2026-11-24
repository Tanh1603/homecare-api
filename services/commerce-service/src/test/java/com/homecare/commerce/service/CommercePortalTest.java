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

import com.homecare.commerce.client.HouseholdAddressClient;
import com.homecare.commerce.client.HouseholdAddressClient.ResolvedShippingAddress;
import com.homecare.commerce.client.ProviderOrgClient;
import com.homecare.commerce.dto.CommerceDtos.CreateOrderRequest;
import com.homecare.commerce.dto.CommerceDtos.OrderLineRequest;
import com.homecare.commerce.entity.CommerceOutboxEventEntity;
import com.homecare.commerce.entity.DeviceEligibility;
import com.homecare.commerce.entity.ProductEntity;
import com.homecare.commerce.entity.WarrantyStartTrigger;
import com.homecare.commerce.repository.CommerceOutboxEventRepository;
import com.homecare.commerce.repository.OrderRepository;
import com.homecare.commerce.repository.ProductRepository;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.common.security.VerifiedMembership;
import com.homecare.provider.v1.ProviderAllocationServiceGrpc.ProviderAllocationServiceBlockingStub;

import tools.jackson.databind.JsonNode;
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
    ProductRepository products;
    @Autowired
    CommerceOutboxEventRepository outboxEvents;
    @Autowired
    UnpaidOrderExpiryJob expiry;
    @Autowired
    VnpaySandboxStrategy vnpay;
    @MockitoBean
    ProviderOrgClient membership;
    @MockitoBean
    HouseholdAddressClient householdAddressClient;
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
    void serviceOrgWithoutMerchantEntityGetsEmptyMerchantReturnsInsteadOf403() throws Exception {
        UUID serviceOrgId = UUID.randomUUID();
        String ownerId = "svc-owner-" + UUID.randomUUID();
        Mockito.when(membership.verify(serviceOrgId, ownerId))
                .thenReturn(VerifiedMembership.of(serviceOrgId, ownerId, "ORG_OWNER",
                        java.util.Set.of("order:read", "order:return:resolve"), true));

        mvc.perform(get("/api/v1/merchant/returns")
                        .header("X-Organization-Id", serviceOrgId)
                        .with(jwt().jwt(j -> j.subject(ownerId).claim("user_roles", List.of("ORGANIZATION")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.pagination.totalItems").value(0));
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

    @Test
    void deliverOrderLinePublishesUnitKeysEligibilityAndModelWithoutUsingProductType() throws Exception {
        UUID merchantOrgId = CommerceDataInitializer.MERCHANT_1_ID;
        String ownerUser = "m2-owner-" + UUID.randomUUID();
        Mockito.when(membership.verify(merchantOrgId, ownerUser))
                .thenReturn(VerifiedMembership.of(merchantOrgId, ownerUser, "ORG_OWNER", true));
        var orgAuth = jwt().jwt(j -> j.subject(ownerUser).claim("user_roles", List.of("ORGANIZATION")));

        var created = mvc.perform(post("/api/v1/products")
                        .with(orgAuth)
                        .header("X-Organization-Id", merchantOrgId)
                        .contentType("application/json")
                        .content("""
                                {
                                  "merchantId": "%s",
                                  "sku": "SKU-%s",
                                  "name": "Daikin Inverter 1HP",
                                  "category": "AC",
                                  "brand": "Daikin",
                                  "productType": "LARGE_APPLIANCE",
                                  "warrantyMonths": 24,
                                  "unitPrice": 9500000,
                                  "model": "Daikin-FTKY25",
                                  "deviceEligibility": "REQUIRED",
                                  "warrantyStartTrigger": "INSTALLATION_COMPLETED"
                                }
                                """.formatted(merchantOrgId, UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.model").value("Daikin-FTKY25"))
                .andExpect(jsonPath("$.data.deviceEligibility").value("REQUIRED"))
                .andExpect(jsonPath("$.data.warrantyStartTrigger").value("INSTALLATION_COMPLETED"))
                .andReturn();

        UUID productId = UUID.fromString(new ObjectMapper()
                .readTree(created.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asString());
        mvc.perform(patch("/api/v1/products/" + productId + "/inventory")
                        .with(orgAuth)
                        .header("X-Organization-Id", merchantOrgId)
                        .contentType("application/json")
                        .content("{\"availableQty\":10}"))
                .andExpect(status().isOk());

        String buyer = "buyer-" + UUID.randomUUID();
        var order = service.createOrder(buyer,
                new CreateOrderRequest(List.of(new OrderLineRequest(productId, 2)), "12 Nguyen Hue"));
        payOrderViaVnpay(buyer, order.id());
        UUID lineId = order.lines().getFirst().id();

        mvc.perform(post("/api/v1/orders/" + order.id() + "/lines/" + lineId + "/fulfillment")
                        .with(orgAuth)
                        .header("X-Organization-Id", merchantOrgId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELIVERED"));

        CommerceOutboxEventEntity outbox = findDeliveredOutboxEvent(order.id());
        JsonNode data = new ObjectMapper().readTree(outbox.getPayloadJson()).path("data");
        assertThat(data.path("productType").asString()).isEqualTo("LARGE_APPLIANCE");
        assertThat(data.path("model").asString()).isEqualTo("Daikin-FTKY25");
        assertThat(data.path("deviceEligibility").asString()).isEqualTo("REQUIRED");
        assertThat(data.path("warrantyStartTrigger").asString()).isEqualTo("INSTALLATION_COMPLETED");
        assertThat(data.path("quantityDelivered").asInt()).isEqualTo(2);
        assertThat(data.path("deliveredAt").asString()).isNotBlank();
        assertThat(data.path("unitKeys")).hasSize(2);
        assertThat(data.path("unitKeys").get(0).asString()).isEqualTo(lineId + ":1");
        assertThat(data.path("unitKeys").get(1).asString()).isEqualTo(lineId + ":2");
    }

    @Test
    void createProductAppliesDefaultsAndNormalizesBlankModelToNull() throws Exception {
        UUID merchantOrgId = CommerceDataInitializer.MERCHANT_1_ID;
        String ownerUser = "m2-defaults-" + UUID.randomUUID();
        Mockito.when(membership.verify(merchantOrgId, ownerUser))
                .thenReturn(VerifiedMembership.of(merchantOrgId, ownerUser, "ORG_OWNER", true));
        var orgAuth = jwt().jwt(j -> j.subject(ownerUser).claim("user_roles", List.of("ORGANIZATION")));

        mvc.perform(post("/api/v1/products")
                        .with(orgAuth)
                        .header("X-Organization-Id", merchantOrgId)
                        .contentType("application/json")
                        .content("""
                                {"merchantId":"%s","sku":"SKU-%s","name":"Default Item","category":"AC","brand":"Daikin","unitPrice":100000}
                                """.formatted(merchantOrgId, UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.model").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.deviceEligibility").value("NONE"))
                .andExpect(jsonPath("$.data.warrantyStartTrigger").value("DELIVERY_DATE"));

        var blankResult = mvc.perform(post("/api/v1/products")
                        .with(orgAuth)
                        .header("X-Organization-Id", merchantOrgId)
                        .contentType("application/json")
                        .content("""
                                {"merchantId":"%s","sku":"SKU-%s","name":"Blank Model","category":"AC","brand":"Daikin","unitPrice":100000,"model":"   "}
                                """.formatted(merchantOrgId, UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.model").value(Matchers.nullValue()))
                .andReturn();
        UUID blankProductId = UUID.fromString(new ObjectMapper()
                .readTree(blankResult.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asString());
        assertThat(products.findById(blankProductId).orElseThrow().getModel()).isNull();

        mvc.perform(post("/api/v1/products")
                        .with(orgAuth)
                        .header("X-Organization-Id", merchantOrgId)
                        .contentType("application/json")
                        .content("""
                                {"merchantId":"%s","sku":"SKU-%s","name":"Trimmed Model","category":"AC","brand":"Daikin","unitPrice":100000,"model":"  Daikin-FTKY25  "}
                                """.formatted(merchantOrgId, UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.model").value("Daikin-FTKY25"));

        mvc.perform(post("/api/v1/products")
                        .with(orgAuth)
                        .header("X-Organization-Id", merchantOrgId)
                        .contentType("application/json")
                        .content("""
                                {"merchantId":"%s","sku":"SKU-%s","name":"Too Long Model","category":"AC","brand":"Daikin","unitPrice":100000,"model":"%s"}
                                """.formatted(merchantOrgId, UUID.randomUUID(), "M".repeat(256))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void partialPatchPreservesModelDeviceEligibilityAndWarrantyStartTrigger() throws Exception {
        UUID merchantOrgId = CommerceDataInitializer.MERCHANT_1_ID;
        String ownerUser = "m2-patch-" + UUID.randomUUID();
        Mockito.when(membership.verify(merchantOrgId, ownerUser))
                .thenReturn(VerifiedMembership.of(merchantOrgId, ownerUser, "ORG_OWNER", true));
        var orgAuth = jwt().jwt(j -> j.subject(ownerUser).claim("user_roles", List.of("ORGANIZATION")));

        var created = mvc.perform(post("/api/v1/products")
                        .with(orgAuth)
                        .header("X-Organization-Id", merchantOrgId)
                        .contentType("application/json")
                        .content("""
                                {
                                  "merchantId": "%s",
                                  "sku": "SKU-%s",
                                  "name": "Patch Product",
                                  "category": "AC",
                                  "brand": "Daikin",
                                  "unitPrice": 500000,
                                  "model": "Daikin-FTKY25",
                                  "deviceEligibility": "OPTIONAL",
                                  "warrantyStartTrigger": "CUSTOMER_ACTIVATION"
                                }
                                """.formatted(merchantOrgId, UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn();
        UUID productId = UUID.fromString(new ObjectMapper()
                .readTree(created.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asString());

        mvc.perform(patch("/api/v1/products/" + productId)
                        .with(orgAuth)
                        .header("X-Organization-Id", merchantOrgId)
                        .contentType("application/json")
                        .content("{\"unitPrice\":750000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unitPrice").value(750000))
                .andExpect(jsonPath("$.data.model").value("Daikin-FTKY25"))
                .andExpect(jsonPath("$.data.deviceEligibility").value("OPTIONAL"))
                .andExpect(jsonPath("$.data.warrantyStartTrigger").value("CUSTOMER_ACTIVATION"));

        ProductEntity persisted = products.findById(productId).orElseThrow();
        assertThat(persisted.getModel()).isEqualTo("Daikin-FTKY25");
        assertThat(persisted.getDeviceEligibility()).isEqualTo(DeviceEligibility.OPTIONAL);
        assertThat(persisted.getWarrantyStartTrigger()).isEqualTo(WarrantyStartTrigger.CUSTOMER_ACTIVATION);

        mvc.perform(patch("/api/v1/products/" + productId)
                        .with(orgAuth)
                        .header("X-Organization-Id", merchantOrgId)
                        .contentType("application/json")
                        .content("{\"model\":\"   \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.model").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.deviceEligibility").value("OPTIONAL"))
                .andExpect(jsonPath("$.data.warrantyStartTrigger").value("CUSTOMER_ACTIVATION"));
    }

    @Test
    void doesNotInferDeviceEligibilityOrModelFromProductTypeOrWarrantyMonths() throws Exception {
        UUID merchantOrgId = CommerceDataInitializer.MERCHANT_1_ID;
        String ownerUser = "m2-no-infer-" + UUID.randomUUID();
        Mockito.when(membership.verify(merchantOrgId, ownerUser))
                .thenReturn(VerifiedMembership.of(merchantOrgId, ownerUser, "ORG_OWNER", true));
        Mockito.when(membership.canOperate(merchantOrgId, ownerUser)).thenReturn(true);
        var orgAuth = jwt().jwt(j -> j.subject(ownerUser).claim("user_roles", List.of("ORGANIZATION")));

        var largeApplianceCreated = mvc.perform(post("/api/v1/products")
                        .with(orgAuth)
                        .header("X-Organization-Id", merchantOrgId)
                        .contentType("application/json")
                        .content("""
                                {
                                  "merchantId": "%s",
                                  "sku": "SKU-%s",
                                  "name": "Large Appliance No Model",
                                  "category": "AC",
                                  "brand": "Panasonic",
                                  "productType": "LARGE_APPLIANCE",
                                  "warrantyMonths": 24,
                                  "unitPrice": 8000000
                                }
                                """.formatted(merchantOrgId, UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.productType").value("LARGE_APPLIANCE"))
                .andExpect(jsonPath("$.data.warrantyMonths").value(24))
                .andExpect(jsonPath("$.data.model").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.deviceEligibility").value("NONE"))
                .andExpect(jsonPath("$.data.warrantyStartTrigger").value("DELIVERY_DATE"))
                .andReturn();

        mvc.perform(post("/api/v1/products")
                        .with(orgAuth)
                        .header("X-Organization-Id", merchantOrgId)
                        .contentType("application/json")
                        .content("""
                                {
                                  "merchantId": "%s",
                                  "sku": "SKU-%s",
                                  "name": "Small Part With Warranty",
                                  "category": "AC",
                                  "brand": "Panasonic",
                                  "productType": "SMALL_PART",
                                  "warrantyMonths": 12,
                                  "unitPrice": 200000
                                }
                                """.formatted(merchantOrgId, UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.model").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.deviceEligibility").value("NONE"));

        UUID productId = UUID.fromString(new ObjectMapper()
                .readTree(largeApplianceCreated.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asString());
        mvc.perform(patch("/api/v1/products/" + productId + "/inventory")
                        .with(orgAuth)
                        .header("X-Organization-Id", merchantOrgId)
                        .contentType("application/json")
                        .content("{\"availableQty\":5}"))
                .andExpect(status().isOk());

        String buyer = "buyer-no-infer-" + UUID.randomUUID();
        var order = service.createOrder(buyer,
                new CreateOrderRequest(List.of(new OrderLineRequest(productId, 1)), "Address"));
        payOrderViaVnpay(buyer, order.id());
        UUID lineId = order.lines().getFirst().id();
        service.fulfillOrderLine(ownerUser, order.id(), lineId, merchantOrgId);

        CommerceOutboxEventEntity outbox = findDeliveredOutboxEvent(order.id());
        JsonNode data = new ObjectMapper().readTree(outbox.getPayloadJson()).path("data");
        assertThat(data.path("productType").asString()).isEqualTo("LARGE_APPLIANCE");
        assertThat(data.path("warrantyMonths").asInt()).isEqualTo(24);
        assertThat(data.path("model").isNull()).isTrue();
        assertThat(data.path("deviceEligibility").asString()).isEqualTo("NONE");
        assertThat(data.path("warrantyStartTrigger").asString()).isEqualTo("DELIVERY_DATE");
    }

    @Test
    void fulfillOrderLineHandlesMissingProductSafelyWithoutInference() throws Exception {
        UUID merchantOrgId = CommerceDataInitializer.MERCHANT_1_ID;
        String ownerUser = "m2-missing-product-" + UUID.randomUUID();
        Mockito.when(membership.verify(merchantOrgId, ownerUser))
                .thenReturn(VerifiedMembership.of(merchantOrgId, ownerUser, "ORG_OWNER", true));
        Mockito.when(membership.canOperate(merchantOrgId, ownerUser)).thenReturn(true);
        var orgAuth = jwt().jwt(j -> j.subject(ownerUser).claim("user_roles", List.of("ORGANIZATION")));

        var created = mvc.perform(post("/api/v1/products")
                        .with(orgAuth)
                        .header("X-Organization-Id", merchantOrgId)
                        .contentType("application/json")
                        .content("""
                                {
                                  "merchantId": "%s",
                                  "sku": "SKU-%s",
                                  "name": "Temp Product",
                                  "category": "AC",
                                  "brand": "LG",
                                  "productType": "LARGE_APPLIANCE",
                                  "unitPrice": 3000000,
                                  "model": "LG-V10",
                                  "deviceEligibility": "REQUIRED"
                                }
                                """.formatted(merchantOrgId, UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn();
        UUID productId = UUID.fromString(new ObjectMapper()
                .readTree(created.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asString());
        mvc.perform(patch("/api/v1/products/" + productId + "/inventory")
                        .with(orgAuth)
                        .header("X-Organization-Id", merchantOrgId)
                        .contentType("application/json")
                        .content("{\"availableQty\":5}"))
                .andExpect(status().isOk());

        String buyer = "buyer-missing-" + UUID.randomUUID();
        var order = service.createOrder(buyer,
                new CreateOrderRequest(List.of(new OrderLineRequest(productId, 2)), "Address"));
        payOrderViaVnpay(buyer, order.id());
        products.deleteById(productId);
        products.flush();

        UUID lineId = order.lines().getFirst().id();
        service.fulfillOrderLine(ownerUser, order.id(), lineId, merchantOrgId);

        CommerceOutboxEventEntity outbox = findDeliveredOutboxEvent(order.id());
        JsonNode data = new ObjectMapper().readTree(outbox.getPayloadJson()).path("data");
        assertThat(data.path("model").isNull()).isTrue();
        assertThat(data.path("deviceEligibility").asString()).isEqualTo("NONE");
        assertThat(data.path("warrantyStartTrigger").asString()).isEqualTo("DELIVERY_DATE");
        assertThat(data.path("quantityDelivered").asInt()).isEqualTo(2);
        assertThat(data.path("unitKeys")).hasSize(2);
        assertThat(data.path("unitKeys").get(0).asString()).isEqualTo(lineId + ":1");
        assertThat(data.path("unitKeys").get(1).asString()).isEqualTo(lineId + ":2");
    }

    @Test
    void deliverOrderLineEmitsNonPiiShippingFallbackAndOmitsSensitiveAddressPii() throws Exception {
        UUID merchantOrgId = CommerceDataInitializer.MERCHANT_1_ID;
        String ownerUser = "m2-pii-" + UUID.randomUUID();
        Mockito.when(membership.verify(merchantOrgId, ownerUser))
                .thenReturn(VerifiedMembership.of(merchantOrgId, ownerUser, "ORG_OWNER", true));
        Mockito.when(membership.canOperate(merchantOrgId, ownerUser)).thenReturn(true);

        String buyer = "buyer-pii-" + UUID.randomUUID();
        UUID addressId = UUID.randomUUID();
        Mockito.when(householdAddressClient.resolveUserAddress(addressId, buyer))
                .thenReturn(new ResolvedShippingAddress(
                        addressId,
                        "Nguyen Van Secret",
                        "0901234567",
                        "123 Secret Street",
                        "26740",
                        "Phường Bến Nghé",
                        "79",
                        "Thành phố Hồ Chí Minh",
                        "123 Secret Street, Phường Bến Nghé, Thành phố Hồ Chí Minh",
                        10.7712,
                        106.7012));

        var order = service.createOrder(buyer,
                new CreateOrderRequest(
                        List.of(new OrderLineRequest(CommerceDataInitializer.PRODUCT_AC_FILTER_ID, 1)),
                        null,
                        addressId));
        payOrderViaVnpay(buyer, order.id());
        UUID lineId = order.lines().getFirst().id();
        service.fulfillOrderLine(ownerUser, order.id(), lineId, merchantOrgId);

        CommerceOutboxEventEntity outbox = findDeliveredOutboxEvent(order.id());
        String payloadJson = outbox.getPayloadJson();
        JsonNode data = new ObjectMapper().readTree(payloadJson).path("data");

        assertThat(data.path("shippingAddressId").asString()).isEqualTo(addressId.toString());
        assertThat(data.path("shippingProvinceName").asString()).isEqualTo("Thành phố Hồ Chí Minh");
        assertThat(data.path("shippingWardName").asString()).isEqualTo("Phường Bến Nghé");

        for (String forbiddenField : List.of(
                "shippingAddress",
                "shippingFormattedAddress",
                "shippingAddressLine",
                "shippingRecipientName",
                "shippingPhone",
                "shippingLatitude",
                "shippingLongitude")) {
            assertThat(data.has(forbiddenField)).isFalse();
        }
        assertThat(payloadJson).doesNotContain("0901234567");
        assertThat(payloadJson).doesNotContain("123 Secret Street");
        assertThat(payloadJson).doesNotContain("Nguyen Van Secret");
        assertThat(payloadJson).doesNotContain("10.7712");
        assertThat(payloadJson).doesNotContain("106.7012");
    }

    @Test
    void unknownProductEnumsReturnBadRequestNeverInternalServerError() throws Exception {
        UUID merchantOrgId = CommerceDataInitializer.MERCHANT_1_ID;
        String ownerUser = "m2-bad-enum-" + UUID.randomUUID();
        Mockito.when(membership.verify(merchantOrgId, ownerUser))
                .thenReturn(VerifiedMembership.of(merchantOrgId, ownerUser, "ORG_OWNER", true));
        var orgAuth = jwt().jwt(j -> j.subject(ownerUser).claim("user_roles", List.of("ORGANIZATION")));

        mvc.perform(post("/api/v1/products")
                        .with(orgAuth)
                        .header("X-Organization-Id", merchantOrgId)
                        .contentType("application/json")
                        .content("""
                                {"merchantId":"%s","sku":"SKU-%s","name":"Bad Eligibility","category":"AC","brand":"Daikin","unitPrice":100000,"deviceEligibility":"ALWAYS"}
                                """.formatted(merchantOrgId, UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));

        mvc.perform(post("/api/v1/products")
                        .with(orgAuth)
                        .header("X-Organization-Id", merchantOrgId)
                        .contentType("application/json")
                        .content("""
                                {"merchantId":"%s","sku":"SKU-%s","name":"Bad Trigger","category":"AC","brand":"Daikin","unitPrice":100000,"warrantyStartTrigger":"PURCHASE"}
                                """.formatted(merchantOrgId, UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));

        var created = mvc.perform(post("/api/v1/products")
                        .with(orgAuth)
                        .header("X-Organization-Id", merchantOrgId)
                        .contentType("application/json")
                        .content("""
                                {"merchantId":"%s","sku":"SKU-%s","name":"Valid Product","category":"AC","brand":"Daikin","unitPrice":100000}
                                """.formatted(merchantOrgId, UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn();
        UUID productId = UUID.fromString(new ObjectMapper()
                .readTree(created.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asString());

        mvc.perform(patch("/api/v1/products/" + productId)
                        .with(orgAuth)
                        .header("X-Organization-Id", merchantOrgId)
                        .contentType("application/json")
                        .content("{\"deviceEligibility\":\"INVALID_VALUE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));

        mvc.perform(patch("/api/v1/products/" + productId)
                        .with(orgAuth)
                        .header("X-Organization-Id", merchantOrgId)
                        .contentType("application/json")
                        .content("{\"warrantyStartTrigger\":\"INVALID_TRIGGER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
    }

    @Test
    void deliverLargeQuantityOrderLinePersistsOutboxPayloadExceeding4000Chars() throws Exception {
        UUID merchantOrgId = CommerceDataInitializer.MERCHANT_1_ID;
        String ownerUser = "m2-large-qty-" + UUID.randomUUID();
        Mockito.when(membership.verify(merchantOrgId, ownerUser))
                .thenReturn(VerifiedMembership.of(merchantOrgId, ownerUser, "ORG_OWNER", true));
        Mockito.when(membership.canOperate(merchantOrgId, ownerUser)).thenReturn(true);
        var orgAuth = jwt().jwt(j -> j.subject(ownerUser).claim("user_roles", List.of("ORGANIZATION")));

        var created = mvc.perform(post("/api/v1/products")
                        .with(orgAuth)
                        .header("X-Organization-Id", merchantOrgId)
                        .contentType("application/json")
                        .content("""
                                {
                                  "merchantId": "%s",
                                  "sku": "SKU-%s",
                                  "name": "Bulk AC Unit",
                                  "category": "AC",
                                  "brand": "Daikin",
                                  "productType": "LARGE_APPLIANCE",
                                  "unitPrice": 5000000,
                                  "model": "Daikin-Bulk-120",
                                  "deviceEligibility": "REQUIRED",
                                  "warrantyStartTrigger": "DELIVERY_DATE"
                                }
                                """.formatted(merchantOrgId, UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn();
        UUID productId = UUID.fromString(new ObjectMapper()
                .readTree(created.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asString());
        mvc.perform(patch("/api/v1/products/" + productId + "/inventory")
                        .with(orgAuth)
                        .header("X-Organization-Id", merchantOrgId)
                        .contentType("application/json")
                        .content("{\"availableQty\":150}"))
                .andExpect(status().isOk());

        String buyer = "buyer-bulk-" + UUID.randomUUID();
        var order = service.createOrder(buyer,
                new CreateOrderRequest(List.of(new OrderLineRequest(productId, 120)), "Bulk Address"));
        payOrderViaVnpay(buyer, order.id());
        UUID lineId = order.lines().getFirst().id();

        service.fulfillOrderLine(ownerUser, order.id(), lineId, merchantOrgId);

        CommerceOutboxEventEntity outbox = findDeliveredOutboxEvent(order.id());
        assertThat(outbox.getPayloadJson().length()).isGreaterThan(4000);
        JsonNode data = new ObjectMapper().readTree(outbox.getPayloadJson()).path("data");
        assertThat(data.path("quantityDelivered").asInt()).isEqualTo(120);
        assertThat(data.path("unitKeys")).hasSize(120);
        assertThat(data.path("unitKeys").get(0).asString()).isEqualTo(lineId + ":1");
        assertThat(data.path("unitKeys").get(119).asString()).isEqualTo(lineId + ":120");
    }

    private void payOrderViaVnpay(String buyerProfileId, UUID orderId) {
        var attempt = service.createPaymentAttempt(buyerProfileId, orderId);
        var params = new HashMap<String, String>();
        params.put("vnp_TxnRef", attempt.txnRef());
        params.put("vnp_Amount", Long.toString(attempt.amount() * 100));
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_TmnCode", "HOMECARE01");
        params.put("vnp_SecureHash", vnpay.signParamsForTest(params));
        assertThat(service.handleVnpayIpn(params).get("RspCode")).isEqualTo("00");
    }

    private CommerceOutboxEventEntity findDeliveredOutboxEvent(UUID orderId) {
        return outboxEvents.findAll().stream()
                .filter(e -> orderId.equals(e.getAggregateId())
                        && DomainEventContracts.ORDER_LINE_DELIVERED.equals(e.getEventType()))
                .findFirst()
                .orElseThrow();
    }
}
